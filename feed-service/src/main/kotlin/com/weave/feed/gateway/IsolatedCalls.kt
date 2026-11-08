package com.weave.feed.gateway

import com.weave.feed.gateway.IsolationProperties.BulkheadType
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.core.functions.Either
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.micrometer.context.ContextSnapshot
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * 하위 서비스 조회를 격리해 부르는 유일한 경로 (add-call-isolation design 결정 1, 5, 7, add-retry-policy design 결정 1).
 *
 * 의존성마다 재시도 루프 → Retry-After 대기 → 서킷 브레이커 → 호출 시간 상한(TimeLimiter) → ThreadPool 벌크헤드 → 호출 순서로 감싼다.
 * 순서는 [call] 한 곳에만 있다. 시간 상한은 벌크헤드 대기까지 포함하고, 열린 서킷은 벌크헤드 자리를 쓰지 않는다. 인스턴스는 스타터가
 * `resilience4j.*` 설정으로 만든 레지스트리에서 의존성 이름으로 꺼낸다.
 *
 * 재시도([RetryLoop])는 가장 바깥이라 시도 하나하나가 대기·서킷·시간 상한·벌크헤드를 다시 거친다. 설정은 `resilience4j.retry.instances`에서
 * 꺼내고(인스턴스가 없는 의존성은 다시 부르지 않는다), 마감은 [RequestDeadline], 재시도 예산은 `weave.retry.budget.*`다. 다음 시도는
 * [retryScheduler] 스레드에서 부른 스레드의 trace 문맥과 MDC를 펼친 채 시작한다. 시도가 429로 끝나면 `Retry-After`로 그 의존성의 대기를
 * 시작하고([RetryAfterHolds]), 대기 중인 시도는 서킷을 거치지 않고 `retry_after`로 바로 끝난다.
 *
 * 실패 로그(`downstream_call_failed`)는 마지막 시도의 실패로 한 번만 남긴다. 시간 상한이 먼저 끝낸 호출이 나중에 응답 타임아웃으로
 * 실패해도 그 실패는 버린다. 시간 상한 초과는 [timeoutScheduler] 스레드에서 끝나므로, 호출한 스레드의 MDC(`traceId`)를 받아 두고
 * 그 MDC로 로그를 남긴다.
 *
 * 벌크헤드 거절 수는 Resilience4j 지표에 없어 `weave.bulkhead.rejected.calls`(태그 `name`)로 센다. 대시보드 질의가 장애 없이도
 * 시계열을 받게 의존성마다 0부터 등록한다(design Open Questions).
 *
 * 요청 스레드에서 동기로 부르는 호출(좋아요 토글)은 [callBlocking]으로 부른다(design 결정 6).
 *
 * `weave.isolation.bulkhead-type: semaphore`면 ThreadPool 벌크헤드 대신 같은 이름의 Semaphore 벌크헤드로 자리를 잡고
 * [taskExecutor](`applicationTaskExecutor`)에서 부른다(design 결정 11). 감싸는 순서와 실패 로그는 같다. 이때 호출 스레드의 trace는
 * 공유 풀의 `propagate-context`만으로 잇고, 호출 자체에는 따로 문맥을 펼치지 않는다(전파를 끄면 끊기는 것을 기록으로 남긴다).
 * Semaphore 모드는 가상 스레드(`spring.threads.virtual.enabled: true`)와 함께 쓰는 비교 실험용이다. 가상 스레드 없이 켜면
 * `applicationTaskExecutor`가 Boot 기본값(코어 8, 대기열 무제한)이라, 벌크헤드 자리를 잡은 호출이 그 대기열에서 기다리며 시간 상한을 쓴다.
 *
 * 시간 상한 초과는 [timeoutScheduler] 스레드 하나가 처리한다. 상한이 한꺼번에 몰리면(GC 뒤 등) 서킷 기록, 실패 로그, 이어 붙인 일이
 * 그 스레드에서 차례로 돈다. 실험(400 RPS)에서는 문제가 보이지 않아 지켜볼 항목으로만 둔다.
 */
@Component
@EnableConfigurationProperties(IsolationProperties::class, RetryProperties::class, RetryAfterProperties::class)
class IsolatedCalls(
    circuitBreakers: CircuitBreakerRegistry,
    timeLimiters: TimeLimiterRegistry,
    bulkheads: ThreadPoolBulkheadRegistry,
    semaphoreBulkheads: BulkheadRegistry,
    retries: RetryRegistry,
    meterRegistry: MeterRegistry,
    properties: IsolationProperties,
    retryProperties: RetryProperties,
    private val retryAfterProperties: RetryAfterProperties,
    private val clock: Clock,
    @Qualifier("applicationTaskExecutor") private val taskExecutor: Executor,
) : DisposableBean {
    private val bulkheadType = properties.bulkheadType

    private val timeoutScheduler =
        ScheduledThreadPoolExecutor(1) { Thread(it, "isolation-timeout").apply { isDaemon = true } }
            // 시간 안에 끝난 호출의 시간 초과 작업을 바로 지운다. 지우지 않으면 상한이 지날 때까지 대기열에 남는다
            .apply { removeOnCancelPolicy = true }

    /** 재시도 간격만큼 뒤에 다음 시도를 시작한다. 기다리는 동안 호출 스레드도 벌크헤드 자리도 잡지 않는다(add-retry-policy design 결정 1). */
    private val retryScheduler =
        ScheduledThreadPoolExecutor(1) { Thread(it, "isolation-retry").apply { isDaemon = true } }

    private val holds = RetryAfterHolds(clock)

    private val isolations =
        DEPENDENCIES.associateWith { dependency ->
            Isolation(
                circuitBreakers.circuitBreaker(dependency),
                timeLimiters.timeLimiter(dependency),
                bulkheads.bulkhead(dependency),
                semaphoreBulkheads.bulkhead(dependency),
                retryLoop(dependency, retries, timeLimiters, retryProperties, meterRegistry),
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
            isolation.semaphore.eventPublisher.onCallRejected { rejected.increment() }
        }
    }

    /**
     * [dependency]의 격리 안에서 [block]을 부른다. [block]은 벌크헤드 스레드(Semaphore 모드면 `applicationTaskExecutor`)에서 돈다.
     *
     * 재시도할 수 있는 실패면 재시도 정책대로 [block]을 다시 부른다. 마지막 시도가 실패하면 `cause`가 `timeout`, `circuit_open`,
     * `bulkhead_full`, `retry_after`, 또는 [block]의 예외를 [DownstreamFailure]가 분류한 값인 실패 로그를 한 줄 남기고 실패를 그대로 넘긴다.
     * 돌려준 future를 먼저 끝내면(예: 요청 시간 예산) 그 뒤의 실패는 로그로 남기지 않고 기다리던 재시도도 보내지 않는다.
     * 로그는 future를 실제로 끝낸 쪽이 끝낸 뒤에 남긴다.
     */
    fun <T> call(
        dependency: String,
        block: () -> T,
    ): CompletableFuture<T> {
        val isolation = isolations.getValue(dependency)
        val callerMdc: Map<String, String>? = MDC.getCopyOfContextMap()
        val callerContext = traceContexts.captureAll()
        val deadline = RequestDeadline.current()
        val inBulkhead =
            when (bulkheadType) {
                BulkheadType.THREAD_POOL -> Supplier<CompletionStage<T>> { isolation.bulkhead.executeSupplier(block) }
                // 자리는 부른 스레드에서 바로 잡고(없으면 기다리지 않고 거절), 호출이 실제로 끝날 때 놓는다. 시간 상한이 먼저 끝내도
                // 스레드가 응답 타임아웃까지 묶여 있는 동안은 자리를 잡고 있어 ThreadPool 벌크헤드와 같다
                BulkheadType.SEMAPHORE ->
                    Supplier<CompletionStage<T>> {
                        isolation.semaphore.executeCompletionStage { CompletableFuture.supplyAsync(block, taskExecutor) }
                    }
            }
        // 서킷이 결과를 기록하고(상태 전이 로그), 결과에 이어 붙인 일(post 뒤의 user·like 호출)이 돌 때 부른 스레드의 trace를
        // 잇게 한다. 시간 상한의 결과는 전파기의 문맥을 이미 닫은 벌크헤드 스레드나 시간 상한 스레드에서 끝난다 (design 결정 8, 9)
        val limited =
            Supplier<CompletionStage<T>> {
                isolation.timeLimiter
                    .executeCompletionStage(timeoutScheduler, inBulkhead)
                    .completingIn(callerContext, callerMdc)
            }
        // 대기 중이면 서킷에 닿지 않고 바로 끝난다. 429로 끝난 시도는 결과를 넘기기 전에 대기를 시작한다(add-retry-policy design 결정 4)
        val attempt = {
            holds.check(dependency)
            isolation.circuitBreaker
                .executeCompletionStage(limited)
                .whenComplete { _, error -> if (error != null) holdOnTooManyRequests(dependency, error) }
        }
        // 로그는 future를 실제로 끝낸 쪽만 남긴다. 요청 시간 예산(RequestBudget.exhaust)도 같은 규칙이라, 시간 상한과 예산 마감이
        // 거의 같은 순간에 와도 같은 호출의 실패 줄은 하나다. 그래서 실패 로그가 피드 응답보다 조금 늦을 수 있다
        return isolation.retryLoop.run(
            deadline = deadline,
            inCaller = { block -> callerContext.setThreadLocals().use { withMdc(callerMdc, block) } },
            attempt = attempt,
            failed = { error -> DownstreamFailure.log(dependency, error) },
        )
    }

    /**
     * [dependency]의 서킷 브레이커 → Semaphore 벌크헤드 [bulkhead] → [block] 순서로 부른 스레드에서 바로 부른다(design 결정 6).
     *
     * 호출 시간 상한은 걸지 않는다. 동기 호출에 상한을 걸려면 다른 스레드로 넘겨야 하고, 그룹의 응답 타임아웃이 같은 역할을 한다.
     * 벌크헤드는 기다리지 않고 거절한다. 실패하면 [call]과 같은 실패 로그를 한 줄 남기고 실패를 그대로 던진다. 다시 부르지 않는다
     * (좋아요 토글은 멱등하지 않다, add-retry-policy design 결정 1).
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
        retryScheduler.shutdownNow()
    }

    /** 429면 `Retry-After`만큼(없으면 기본 대기) [dependency]를 부르지 않는다. 이미 더 늦게까지 대기 중이면 그대로 둔다. */
    private fun holdOnTooManyRequests(
        dependency: String,
        error: Throwable,
    ) {
        val response = DownstreamFailure.unwrap(error) as? RestClientResponseException ?: return
        if (response.statusCode.value() != TOO_MANY_REQUESTS) return
        val hold =
            RetryAfter.parse(
                response.responseHeaders?.getFirst(RetryAfter.HEADER),
                clock.instant(),
                retryAfterProperties.defaultHold,
                retryAfterProperties.maxHold,
            )
        holds.hold(dependency, hold)
    }

    /**
     * [dependency]의 재시도 루프. 재시도 인스턴스(`resilience4j.retry.instances.<의존성>`)가 없으면 다시 부르지 않는다. 시도 시간 상한은 그
     * 의존성의 TimeLimiter 값이다. 재시도 결정은 `weave.retry.calls`(태그 `name`, `outcome`)로 세고, 결과마다 0부터 등록한다
     * (add-retry-policy design 결정 9).
     */
    private fun retryLoop(
        dependency: String,
        retries: RetryRegistry,
        timeLimiters: TimeLimiterRegistry,
        retryProperties: RetryProperties,
        meterRegistry: MeterRegistry,
    ): RetryLoop {
        val settings =
            retries.find(dependency).map { retry ->
                val config = retry.retryConfig
                val interval = config.getIntervalBiFunction<Any?>()
                RetryLoop.Settings(
                    maxAttempts = config.maxAttempts,
                    attemptTimeout = timeLimiters.timeLimiter(dependency).timeLimiterConfig.timeoutDuration,
                    interval = { attempt, error -> Duration.ofMillis(interval.apply(attempt, Either.left(error))) },
                )
            }
        val decisions =
            RetryOutcome.entries.associateWith { outcome ->
                Counter
                    .builder("weave.retry.calls")
                    .description("재시도할 수 있는 실패에 대한 재시도 결정 수")
                    .tag("name", dependency)
                    .tag("outcome", outcome.tag)
                    .register(meterRegistry)
            }
        return RetryLoop(
            dependency = dependency,
            settings = settings.orElse(null),
            budget = retryProperties.budget.newBudget(clock),
            scheduler = retryScheduler,
            clock = clock,
            decided = { decisions.getValue(it).increment() },
        )
    }

    private class Isolation(
        val circuitBreaker: CircuitBreaker,
        val timeLimiter: TimeLimiter,
        val bulkhead: ThreadPoolBulkhead,
        val semaphore: Bulkhead,
        val retryLoop: RetryLoop,
    )

    companion object {
        /** 격리하는 의존성. HTTP 서비스 그룹 이름, Resilience4j 인스턴스 이름과 같다. */
        val DEPENDENCIES = listOf("post", "user", "like", "recommendation", "ad")

        /** 좋아요 토글의 Semaphore 벌크헤드 이름. 읽기 벌크헤드(`like`)와 자리를 나눈다(design 결정 6). */
        const val LIKE_TOGGLE = "like-toggle"

        /** [callBlocking]이 쓰는 Semaphore 벌크헤드. `resilience4j.bulkhead.instances`의 이름이다. */
        val BLOCKING_BULKHEADS = listOf(LIKE_TOGGLE)

        private const val TOO_MANY_REQUESTS = 429
    }
}

/**
 * 서킷 브레이커의 실패 판정 (design 결정 7). `resilience4j.circuitbreaker.configs.default.record-failure-predicate`로 등록하고,
 * Resilience4j가 인자 없는 생성자로 만든다. 실패 로그의 `cause`와 같은 분류([DownstreamFailure.countsAsCircuitFailure])를 쓴다.
 */
class CircuitFailurePredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean = DownstreamFailure.countsAsCircuitFailure(error)
}

/**
 * 이 stage가 끝나면 [context]와 [mdc]를 펼친 채로 끝나는 future를 돌려준다. 돌려준 future에 걸어 둔 일(서킷의 결과 기록, 그 뒤의
 * 결과 전달)은 끝내는 스레드에서 바로 돌므로 같은 문맥을 본다.
 */
private fun <T> CompletionStage<T>.completingIn(
    context: ContextSnapshot,
    mdc: Map<String, String>?,
): CompletionStage<T> {
    val completed = CompletableFuture<T>()
    whenComplete { value, error ->
        context.setThreadLocals().use {
            withMdc(mdc) {
                if (error == null) completed.complete(value) else completed.completeExceptionally(error)
            }
        }
    }
    return completed
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
