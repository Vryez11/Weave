package com.weave.feed.gateway

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 의존성 하나의 재시도 루프 (add-retry-policy design 결정 1, 3, 5).
 *
 * 시도([run]의 `attempt`)가 [RetryPolicy.retryable]한 실패로 끝나면 시도 횟수 → 남은 시간 예산 → 재시도 예산 순서로 보고, 모두 되면 간격만큼
 * 뒤에 [scheduler]에서 다음 시도를 시작한다. 기다리는 동안은 스레드도 벌크헤드 자리도 잡지 않는다. 다시 부르지 않기로 하면 마지막 실제 실패로
 * 결과를 끝낸다. 결정은 재시도할 수 있는 실패마다 하나씩 [decided]로 알린다([RetryOutcome]). 재시도하지 않는 실패는 결정이 아니다.
 *
 * 남은 시간 예산: `마감 − 지금 ≥ 시도 시간 상한 + 기다릴 시간`일 때만 다시 부른다. 그래서 다시 부른 시도도 마감 전에 끝난다. 마감이 없으면 보지 않는다.
 * 결과를 다른 쪽(요청 시간 예산)이 먼저 끝냈으면 기다리던 재시도를 보내지 않고, 그 뒤에 끝난 시도의 실패는 결정으로 세지 않는다.
 *
 * [settings]가 null이면(재시도 인스턴스가 없는 의존성, ad) 다시 부르지 않고 결정도 세지 않는다.
 */
internal class RetryLoop(
    private val dependency: String,
    private val settings: Settings?,
    private val budget: RetryBudget?,
    private val scheduler: ScheduledExecutorService,
    private val clock: Clock,
    private val decided: (RetryOutcome) -> Unit = {},
) {
    /**
     * @property maxAttempts 첫 시도를 포함한 최대 시도 횟수
     * @property attemptTimeout 시도 하나의 시간 상한(TimeLimiter)
     * @property interval n번째 시도가 [Throwable]로 실패한 뒤 다음 시도까지 기다릴 시간
     */
    class Settings(
        val maxAttempts: Int,
        val attemptTimeout: Duration,
        val interval: (attempt: Int, error: Throwable) -> Duration,
    )

    /**
     * [attempt]로 첫 시도를 바로 시작하고, 재시도까지 마친 결과를 돌려준다.
     *
     * 원래 호출 하나를 재시도 예산에 센다. [inCaller]는 부른 스레드의 trace 문맥과 MDC를 펼쳐 블록을 돌린다. 다음 시도의 시작, 재시도 로그,
     * [failed]를 그 안에서 부른다. [failed]는 이 루프가 결과를 실패로 끝냈을 때만 한 번 부른다(먼저 끝낸 쪽이 실패 로그를 남긴다).
     */
    fun <T> run(
        deadline: Instant?,
        inCaller: (() -> Unit) -> Unit,
        attempt: () -> CompletionStage<T>,
        failed: (Throwable) -> Unit,
    ): CompletableFuture<T> {
        budget?.recordCall()
        val result = CompletableFuture<T>()
        Attempts(result, deadline, inCaller, attempt, failed).start(1)
        return result
    }

    private inner class Attempts<T>(
        private val result: CompletableFuture<T>,
        private val deadline: Instant?,
        private val inCaller: (() -> Unit) -> Unit,
        private val attempt: () -> CompletionStage<T>,
        private val failed: (Throwable) -> Unit,
    ) {
        fun start(number: Int) {
            val stage =
                try {
                    attempt()
                } catch (e: Exception) {
                    CompletableFuture.failedFuture(e)
                }
            stage.whenComplete { value, error ->
                when {
                    error == null -> result.complete(value)
                    // 요청 시간 예산이 먼저 끝냈다. 실패 로그도 그쪽이 남겼다
                    result.isDone -> Unit
                    else -> afterFailure(number, error)
                }
            }
        }

        private fun afterFailure(
            number: Int,
            error: Throwable,
        ) {
            val wait = nextWait(number, error, deadline)
            if (wait == null) {
                finish(error)
                return
            }
            inCaller { logRetried(number + 1, error, wait) }
            try {
                scheduler.schedule(
                    { if (!result.isDone) inCaller { start(number + 1) } },
                    wait.toNanos(),
                    TimeUnit.NANOSECONDS,
                )
            } catch (_: RejectedExecutionException) {
                // 종료 중이라 예약할 수 없다. 마지막 실패로 끝낸다
                finish(error)
            }
        }

        private fun finish(error: Throwable) {
            if (result.completeExceptionally(error)) inCaller { failed(error) }
        }
    }

    /** [number]번째 시도가 [error]로 끝난 뒤 다음 시도까지 기다릴 시간. 다시 부르지 않으면 null이다. */
    private fun nextWait(
        number: Int,
        error: Throwable,
        deadline: Instant?,
    ): Duration? {
        val settings = settings ?: return null
        if (!RetryPolicy.retryable(error)) return null
        if (number >= settings.maxAttempts) return decline(RetryOutcome.ATTEMPTS_EXHAUSTED)
        val wait = settings.interval(number, error)
        // 시간을 먼저 본다. 보내지 않을 재시도로 예산을 쓰지 않는다
        if (deadline != null && Duration.between(clock.instant(), deadline) < settings.attemptTimeout + wait) {
            return decline(RetryOutcome.DEADLINE)
        }
        if (budget != null && !budget.tryAcquireRetry()) return decline(RetryOutcome.BUDGET_EXHAUSTED)
        decided(RetryOutcome.RETRIED)
        return wait
    }

    private fun decline(outcome: RetryOutcome): Duration? {
        decided(outcome)
        return null
    }

    /** `downstream_call_retried` WARN 한 줄 (design 결정 8). 예외 객체는 로거에 넘기지 않는다. */
    private fun logRetried(
        attempt: Int,
        error: Throwable,
        wait: Duration,
    ) {
        val cause = DownstreamFailure.of(error).cause
        logger
            .atWarn()
            .addKeyValue("event", "downstream_call_retried")
            .addKeyValue("dependency", dependency)
            .addKeyValue("attempt", attempt)
            .addKeyValue("cause", cause)
            .addKeyValue("waitMs", wait.toMillis())
            .log("{} 다시 부름({}번째 시도): {}", dependency, attempt, cause)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RetryLoop::class.java)
    }
}

/** 재시도할 수 있는 실패 하나에 대한 결정. 지표 `weave.retry.calls`의 `outcome` 태그다 (design 결정 9). */
enum class RetryOutcome(
    val tag: String,
) {
    /** 다시 불렀다. */
    RETRIED("retried"),

    /** 재시도 예산이 다 돼 다시 부르지 않았다. */
    BUDGET_EXHAUSTED("budget_exhausted"),

    /** 남은 시간 예산이 시도 시간 상한과 기다릴 시간보다 짧아 다시 부르지 않았다. */
    DEADLINE("deadline"),

    /** 최대 시도 횟수에 닿았다. */
    ATTEMPTS_EXHAUSTED("attempts_exhausted"),
}
