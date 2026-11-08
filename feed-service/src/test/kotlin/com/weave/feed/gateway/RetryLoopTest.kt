package com.weave.feed.gateway

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 재시도 루프 (add-retry-policy design 결정 1, 3, 5). Spring 없이 가짜 시도 함수와 실제 스케줄러로 돌린다.
 *
 * `retry-policy` 스펙 "남은 시간 예산 안에서만 재시도한다"의 "기다리는 동안 호출이 먼저 끝났다"를 여기서 본다. 나머지는 결정 순서
 * (시도 횟수 → 남은 시간 → 재시도 예산)와 결정 수, 다시 부르지 않을 때 넘기는 실패다. 통합 동작은 `FeedRetryScenarioTest`가 본다.
 */
class RetryLoopTest :
    FunSpec({
        val scheduler = ScheduledThreadPoolExecutor(1)
        afterSpec { scheduler.shutdownNow() }

        fun loop(
            settings: RetryLoop.Settings?,
            budget: RetryBudget? = null,
            decisions: MutableList<RetryOutcome> = CopyOnWriteArrayList(),
        ) = RetryLoop("like", settings, budget, scheduler, Clock.systemUTC()) { decisions += it }

        test("F-005 기다리는 동안 호출이 먼저 끝났다: 기본 간격 200ms를 기다리는 동안 결과를 다른 쪽이 먼저 끝내고 300ms가 지나면 두 번째 시도는 보내지 않는다") {
            val attempts = AtomicInteger()
            val failed = CopyOnWriteArrayList<Throwable>()

            val result =
                loop(settings(maxAttempts = 3, interval = Duration.ofMillis(200))).run(
                    deadline = null,
                    inCaller = { it() },
                    attempt = { attempts.incrementAndGet().let { failedWith<String>(unavailable()) } },
                    failed = { failed += it },
                )
            attempts.get() shouldBe 1
            result.completeExceptionally(TimeBudgetExhaustedException("like")) shouldBe true
            Thread.sleep(300)

            attempts.get() shouldBe 1
            failed.shouldBeEmpty()
        }

        test("F-005 다시 부른 시도가 성공하면 그 값으로 끝나고 결정은 retried 하나다") {
            val attempts = AtomicInteger()
            val decisions = CopyOnWriteArrayList<RetryOutcome>()

            val result =
                loop(settings(maxAttempts = 3), decisions = decisions).run(
                    deadline = null,
                    inCaller = { it() },
                    attempt = {
                        if (attempts.incrementAndGet() == 1) failedWith(unavailable()) else CompletableFuture.completedFuture("likes")
                    },
                    failed = {},
                )

            result.get(2, TimeUnit.SECONDS) shouldBe "likes"
            attempts.get() shouldBe 2
            decisions shouldBe listOf(RetryOutcome.RETRIED)
        }

        test("F-005 최대 시도 횟수에 닿으면 마지막 실패로 끝내고 실패를 한 번 알린다") {
            val attempts = AtomicInteger()
            val decisions = CopyOnWriteArrayList<RetryOutcome>()
            val failed = CopyOnWriteArrayList<Throwable>()
            val last = unavailable()

            val result =
                loop(settings(maxAttempts = 3), decisions = decisions).run(
                    deadline = null,
                    inCaller = { it() },
                    attempt = { failedWith<String>(if (attempts.incrementAndGet() == 3) last else unavailable()) },
                    failed = { failed += it },
                )

            shouldThrow<ExecutionException> { result.get(2, TimeUnit.SECONDS) }.cause shouldBeSameInstanceAs last
            attempts.get() shouldBe 3
            decisions shouldBe listOf(RetryOutcome.RETRIED, RetryOutcome.RETRIED, RetryOutcome.ATTEMPTS_EXHAUSTED)
            failed shouldBe listOf(last)
        }

        test("F-005 재시도하지 않는 실패(404, 서킷 열림)는 다시 부르지 않고 결정으로 세지 않는다") {
            val openCircuit = CircuitBreaker.ofDefaults("like").apply { transitionToOpenState() }
            val circuitOpen = CallNotPermittedException.createCallNotPermittedException(openCircuit)
            listOf(HttpClientErrorException(HttpStatus.NOT_FOUND), circuitOpen).forEach { error ->
                val attempts = AtomicInteger()
                val decisions = CopyOnWriteArrayList<RetryOutcome>()

                val result =
                    loop(settings(maxAttempts = 3), decisions = decisions).run(
                        deadline = null,
                        inCaller = { it() },
                        attempt = { attempts.incrementAndGet().let { failedWith<String>(error) } },
                        failed = {},
                    )

                shouldThrow<ExecutionException> { result.get(2, TimeUnit.SECONDS) }.cause shouldBeSameInstanceAs error
                attempts.get() shouldBe 1
                decisions.shouldBeEmpty()
            }
        }

        test("F-005 남은 시간이 시도 시간 상한과 기다릴 시간의 합보다 짧으면 다시 부르지 않고 deadline으로 센다") {
            val attempts = AtomicInteger()
            val decisions = CopyOnWriteArrayList<RetryOutcome>()

            val result =
                loop(settings(maxAttempts = 3, attemptTimeout = Duration.ofMillis(200)), decisions = decisions).run(
                    deadline = Instant.now().plusMillis(150),
                    inCaller = { it() },
                    attempt = { attempts.incrementAndGet().let { failedWith<String>(unavailable()) } },
                    failed = {},
                )

            shouldThrow<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
            attempts.get() shouldBe 1
            decisions shouldBe listOf(RetryOutcome.DEADLINE)
        }

        test("F-005 재시도 예산이 다 되면 다시 부르지 않고 budget_exhausted로 센다") {
            val attempts = AtomicInteger()
            val decisions = CopyOnWriteArrayList<RetryOutcome>()
            // 최솟값 1, 비율 0: 창 안에서 재시도 하나만 허용한다
            val budget = RetryBudget(ratio = 0.0, minRetries = 1, window = Duration.ofSeconds(10), clock = Clock.systemUTC())

            val result =
                loop(settings(maxAttempts = 3), budget = budget, decisions = decisions).run(
                    deadline = null,
                    inCaller = { it() },
                    attempt = { attempts.incrementAndGet().let { failedWith<String>(unavailable()) } },
                    failed = {},
                )

            shouldThrow<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
            attempts.get() shouldBe 2
            decisions shouldBe listOf(RetryOutcome.RETRIED, RetryOutcome.BUDGET_EXHAUSTED)
        }

        test("F-005 재시도 설정이 없는 의존성(ad)은 다시 부르지 않고 결정으로 세지 않는다") {
            val attempts = AtomicInteger()
            val decisions = CopyOnWriteArrayList<RetryOutcome>()

            val result =
                loop(settings = null, decisions = decisions).run(
                    deadline = null,
                    inCaller = { it() },
                    attempt = { attempts.incrementAndGet().let { failedWith<String>(unavailable()) } },
                    failed = {},
                )

            shouldThrow<ExecutionException> { result.get(2, TimeUnit.SECONDS) }
            attempts.get() shouldBe 1
            decisions.shouldBeEmpty()
        }

        test("F-005 시도 함수가 바로 던지면 그 예외를 시도의 실패로 본다") {
            val hold = RetryAfterHoldException("like", Instant.now().plusSeconds(1))

            val result =
                loop(settings(maxAttempts = 3)).run<String>(
                    deadline = null,
                    inCaller = { it() },
                    attempt = { throw hold },
                    failed = {},
                )

            shouldThrow<ExecutionException> { result.get(2, TimeUnit.SECONDS) }.cause shouldBeSameInstanceAs hold
        }
    })

private fun settings(
    maxAttempts: Int,
    interval: Duration = Duration.ofMillis(10),
    attemptTimeout: Duration = Duration.ofSeconds(1),
) = RetryLoop.Settings(maxAttempts, attemptTimeout) { _, _ -> interval }

private fun unavailable() = HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)

private fun <T> failedWith(error: Throwable): CompletionStage<T> = CompletableFuture.failedFuture(error)
