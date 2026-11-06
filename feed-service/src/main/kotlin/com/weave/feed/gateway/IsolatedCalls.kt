package com.weave.feed.gateway

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * 하위 서비스 조회를 격리해 부르는 유일한 경로 (add-call-isolation design 결정 1, 5, 7).
 *
 * 의존성마다 서킷 브레이커 → 호출 시간 상한(TimeLimiter) → ThreadPool 벌크헤드 → 호출 순서로 감싼다. 순서는 [call] 한 곳에만 있다.
 * 시간 상한은 벌크헤드 대기까지 포함하고, 열린 서킷은 벌크헤드 자리를 쓰지 않는다. 인스턴스는 스타터가 `resilience4j.*` 설정으로
 * 만든 레지스트리에서 의존성 이름으로 꺼낸다.
 *
 * 실패 로그(`downstream_call_failed`)는 감싼 future의 결과에서 한 번만 남긴다. 시간 상한이 먼저 끝낸 호출이 나중에 응답 타임아웃으로
 * 실패해도 그 실패는 버린다. 시간 상한 초과는 [timeoutScheduler] 스레드에서 끝나므로, 호출한 스레드의 MDC(`traceId`)를 받아 두고
 * 그 MDC로 로그를 남긴다.
 *
 * 벌크헤드 거절 수는 Resilience4j 지표에 없어 `weave.bulkhead.rejected.calls`(태그 `name`)로 센다. 대시보드 질의가 장애 없이도
 * 시계열을 받게 의존성마다 0부터 등록한다(design Open Questions).
 *
 * 요청 스레드에서 동기로 부르는 호출(좋아요 토글)은 [callBlocking]으로 부른다(design 결정 6).
 */
@Component
class IsolatedCalls(
    circuitBreakers: CircuitBreakerRegistry,
    timeLimiters: TimeLimiterRegistry,
    bulkheads: ThreadPoolBulkheadRegistry,
    semaphoreBulkheads: BulkheadRegistry,
    meterRegistry: MeterRegistry,
) : DisposableBean {
    private val timeoutScheduler =
        ScheduledThreadPoolExecutor(1) { Thread(it, "isolation-timeout").apply { isDaemon = true } }
            // 시간 안에 끝난 호출의 시간 초과 작업을 바로 지운다. 지우지 않으면 상한이 지날 때까지 대기열에 남는다
            .apply { removeOnCancelPolicy = true }

    private val isolations =
        DEPENDENCIES.associateWith { dependency ->
            Isolation(
                circuitBreakers.circuitBreaker(dependency),
                timeLimiters.timeLimiter(dependency),
                bulkheads.bulkhead(dependency),
            )
        }

    private val circuitBreakers = isolations.mapValues { (_, isolation) -> isolation.circuitBreaker }

    private val blockingBulkheads = BLOCKING_BULKHEADS.associateWith { semaphoreBulkheads.bulkhead(it) }

    init {
        isolations.forEach { (dependency, isolation) ->
            val rejected =
                Counter
                    .builder("weave.bulkhead.rejected.calls")
                    .description("벌크헤드가 자리가 없어 거절한 하위 서비스 호출 수")
                    .tag("name", dependency)
                    .register(meterRegistry)
            isolation.bulkhead.eventPublisher.onCallRejected { rejected.increment() }
        }
    }

    /**
     * [dependency]의 격리 안에서 [block]을 부른다. [block]은 벌크헤드 스레드에서 돈다.
     *
     * 실패하면 `cause`가 `timeout`, `circuit_open`, `bulkhead_full`, 또는 [block]의 예외를 [DownstreamFailure]가 분류한 값인
     * 실패 로그를 한 줄 남기고 실패를 그대로 넘긴다. 돌려준 future를 먼저 끝내면(예: 요청 시간 예산) 그 뒤의 실패는 로그로 남기지 않는다.
     */
    fun <T> call(
        dependency: String,
        block: () -> T,
    ): CompletableFuture<T> {
        val isolation = isolations.getValue(dependency)
        val callerMdc: Map<String, String>? = MDC.getCopyOfContextMap()
        val callerContext = traceContexts.captureAll()
        val inBulkhead = Supplier<CompletionStage<T>> { isolation.bulkhead.executeSupplier(block) }
        val result = CompletableFuture<T>()
        isolation.circuitBreaker
            .executeCompletionStage { isolation.timeLimiter.executeCompletionStage(timeoutScheduler, inBulkhead) }
            .whenComplete { value, error ->
                // 결과에 이어 붙인 일(post 뒤의 user·like 호출)이 부른 스레드의 trace를 잇게 한다. 여기는 벌크헤드 스레드가
                // 전파기의 문맥을 이미 닫은 뒤이거나 시간 상한 스레드다 (design 결정 8)
                callerContext.setThreadLocals().use {
                    when {
                        error == null -> result.complete(value)
                        // 로그를 먼저 남겨 지금처럼 피드 응답보다 실패 로그가 앞선다
                        !result.isDone -> {
                            withMdc(callerMdc) { DownstreamFailure.log(dependency, error) }
                            result.completeExceptionally(error)
                        }
                    }
                }
            }
        return result
    }

    /**
     * [dependency]의 서킷 브레이커 → Semaphore 벌크헤드 [bulkhead] → [block] 순서로 부른 스레드에서 바로 부른다(design 결정 6).
     *
     * 호출 시간 상한은 걸지 않는다. 동기 호출에 상한을 걸려면 다른 스레드로 넘겨야 하고, 그룹의 응답 타임아웃이 같은 역할을 한다.
     * 벌크헤드는 기다리지 않고 거절한다. 실패하면 [call]과 같은 실패 로그를 한 줄 남기고 실패를 그대로 던진다.
     */
    fun <T> callBlocking(
        dependency: String,
        bulkhead: String,
        block: () -> T,
    ): T =
        try {
            val semaphore = blockingBulkheads.getValue(bulkhead)
            circuitBreakers.getValue(dependency).executeSupplier { semaphore.executeSupplier(block) }
        } catch (e: Exception) {
            DownstreamFailure.log(dependency, e)
            throw e
        }

    override fun destroy() {
        timeoutScheduler.shutdownNow()
    }

    private class Isolation(
        val circuitBreaker: CircuitBreaker,
        val timeLimiter: TimeLimiter,
        val bulkhead: ThreadPoolBulkhead,
    )

    companion object {
        /** 격리하는 의존성. HTTP 서비스 그룹 이름, Resilience4j 인스턴스 이름과 같다. */
        val DEPENDENCIES = listOf("post", "user", "like", "recommendation", "ad")

        /** 좋아요 토글의 Semaphore 벌크헤드 이름. 읽기 벌크헤드(`like`)와 자리를 나눈다(design 결정 6). */
        const val LIKE_TOGGLE = "like-toggle"

        /** [callBlocking]이 쓰는 Semaphore 벌크헤드. `resilience4j.bulkhead.instances`의 이름이다. */
        val BLOCKING_BULKHEADS = listOf(LIKE_TOGGLE)
    }
}

/**
 * 서킷 브레이커의 실패 판정 (design 결정 7). `resilience4j.circuitbreaker.configs.default.record-failure-predicate`로 등록하고,
 * Resilience4j가 인자 없는 생성자로 만든다. 실패 로그의 `cause`와 같은 분류([DownstreamFailure.countsAsCircuitFailure])를 쓴다.
 */
class CircuitFailurePredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean = DownstreamFailure.countsAsCircuitFailure(error)
}

private inline fun withMdc(
    context: Map<String, String>?,
    block: () -> Unit,
) {
    val previous = MDC.getCopyOfContextMap()
    if (context == null) MDC.clear() else MDC.setContextMap(context)
    try {
        block()
    } finally {
        if (previous == null) MDC.clear() else MDC.setContextMap(previous)
    }
}
