package com.weave.feed.gateway

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * add-retry-policy design 결정 5: 창 안의 원래 호출 수에 비율을 곱한 만큼(최솟값 이상)만 다시 부른다.
 *
 * 칸이 실제 시간에 흔들리지 않게 손으로만 움직이는 시계([ManualClock])를 쓴다. 시각은 0ms에서 시작한다.
 */
class RetryBudgetTest :
    FunSpec({
        val window = Duration.ofSeconds(1)

        test("F-005 창 안의 원래 호출과 재시도를 센다: 비율 0.5에 원래 호출 4개면 재시도 2개까지 허용하고, 창 끝까지 그대로다") {
            val clock = ManualClock()
            val budget = RetryBudget(0.5, 0, window, clock)
            repeat(4) { budget.recordCall() }

            withClue("첫 재시도") { budget.tryAcquireRetry() shouldBe true }
            withClue("둘째 재시도") { budget.tryAcquireRetry() shouldBe true }
            withClue("셋째 재시도") { budget.tryAcquireRetry() shouldBe false }

            clock.at(1_000)
            withClue("창(1초)이 막 지난 시각에도 예전 호출과 재시도를 센다") { budget.tryAcquireRetry() shouldBe false }
        }

        test("F-005 창 밖의 칸은 세지 않는다: 창이 지나면 예전 재시도가 빠지고 새 원래 호출만큼 다시 허용한다") {
            val clock = ManualClock()
            val budget = RetryBudget(0.5, 0, window, clock)
            repeat(4) { budget.recordCall() }
            repeat(2) { budget.tryAcquireRetry() }

            clock.at(1_100)
            withClue("창 안에 원래 호출이 없다") { budget.tryAcquireRetry() shouldBe false }
            repeat(2) { budget.recordCall() }
            withClue("새 원래 호출 2개의 재시도") { budget.tryAcquireRetry() shouldBe true }
            withClue("새 원래 호출 2개의 둘째 재시도") { budget.tryAcquireRetry() shouldBe false }
        }

        test("F-005 칸 경계: 99ms와 100ms의 호출은 다른 칸이라, 1,099ms에는 둘 다 세고 1,100ms에는 뒤 것만 센다") {
            fun budgetWithCallsAtSlotBoundary(): Pair<ManualClock, RetryBudget> {
                val clock = ManualClock()
                val budget = RetryBudget(1.0, 0, window, clock)
                clock.at(99)
                budget.recordCall()
                clock.at(100)
                budget.recordCall()
                return clock to budget
            }

            val (beforeBoundary, stillCounted) = budgetWithCallsAtSlotBoundary()
            beforeBoundary.at(1_099)
            withClue("1,099ms 첫 재시도") { stillCounted.tryAcquireRetry() shouldBe true }
            withClue("1,099ms 둘째 재시도") { stillCounted.tryAcquireRetry() shouldBe true }
            withClue("1,099ms 셋째 재시도") { stillCounted.tryAcquireRetry() shouldBe false }

            val (afterBoundary, oneExpired) = budgetWithCallsAtSlotBoundary()
            afterBoundary.at(1_100)
            withClue("1,100ms 첫 재시도") { oneExpired.tryAcquireRetry() shouldBe true }
            withClue("1,100ms 둘째 재시도") { oneExpired.tryAcquireRetry() shouldBe false }
        }

        test("F-005 창이 칸으로 나누어떨어지지 않으면 올려서 센다: 창 150ms면 0ms의 호출을 299ms까지 센다") {
            val clock = ManualClock()
            val budget = RetryBudget(1.0, 0, Duration.ofMillis(150), clock)
            budget.recordCall()

            clock.at(299)
            withClue("299ms") { budget.tryAcquireRetry() shouldBe true }
            clock.at(300)
            withClue("300ms") { budget.tryAcquireRetry() shouldBe false }
        }

        test("F-005 최솟값과 비율 중 큰 값: 최솟값 3, 비율 0.1이면 원래 호출 10개에 3개, 100개에 10개를 허용한다") {
            val budget = RetryBudget(0.1, 3, Duration.ofSeconds(10), ManualClock())
            repeat(10) { budget.recordCall() }
            withClue("원래 호출 10개: max(3, floor(1.0)) = 3") { budget.acquireAll() shouldBe 3 }

            repeat(90) { budget.recordCall() }
            withClue("원래 호출 100개: max(3, floor(10.0)) = 10, 이미 3개를 썼다") { budget.acquireAll() shouldBe 7 }
        }

        test("F-005 원래 호출이 없어도 최솟값만큼은 다시 부른다") {
            val budget = RetryBudget(0.1, 2, Duration.ofSeconds(10), ManualClock())
            budget.acquireAll() shouldBe 2
        }

        test("F-005 동시에 차감해도 넘지 않는다: 원래 호출 1,000개, 비율 0.1에 스레드 16개가 100번씩 다시 부르려 해도 100개만 허용한다") {
            val budget = RetryBudget(0.1, 0, Duration.ofSeconds(10), ManualClock())
            repeat(1_000) { budget.recordCall() }
            val acquired = AtomicInteger()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(THREADS)
            try {
                repeat(THREADS) {
                    pool.execute {
                        start.await()
                        repeat(100) { if (budget.tryAcquireRetry()) acquired.incrementAndGet() }
                    }
                }
                start.countDown()
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
            } finally {
                pool.shutdownNow()
            }

            acquired.get() shouldBe 100
        }

        test("F-005 잘못된 값은 만들 때 거절한다") {
            shouldThrow<IllegalArgumentException> { RetryBudget(-0.1, 0, window, ManualClock()) }
            shouldThrow<IllegalArgumentException> { RetryBudget(0.1, -1, window, ManualClock()) }
            shouldThrow<IllegalArgumentException> { RetryBudget(0.1, 0, Duration.ZERO, ManualClock()) }
        }

        test("F-005 weave.retry.budget의 세 값이 모두 있을 때만 예산을 만든다") {
            val clock = ManualClock()
            RetryProperties.Budget().newBudget(clock).shouldBeNull()
            RetryProperties.Budget(ratio = 0.1, minRetries = 10).newBudget(clock).shouldBeNull()
            RetryProperties.Budget(ratio = 0.1, window = window).newBudget(clock).shouldBeNull()
            RetryProperties.Budget(minRetries = 10, window = window).newBudget(clock).shouldBeNull()
            RetryProperties.Budget(ratio = 0.1, minRetries = 10, window = window).newBudget(clock).shouldNotBeNull()
        }
    })

private const val THREADS = 16

/** 허용하는 재시도를 모두 차감하고 그 수를 돌려준다. */
private fun RetryBudget.acquireAll(): Int {
    var acquired = 0
    while (tryAcquireRetry()) acquired++
    return acquired
}

/** [at]으로만 움직이는 시계. */
private class ManualClock : Clock() {
    @Volatile
    private var millis = 0L

    fun at(millis: Long) {
        this.millis = millis
    }

    override fun instant(): Instant = Instant.ofEpochMilli(millis)

    override fun millis(): Long = millis

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
