package com.weave.ad

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

/** 요청 한도의 고정 창과 `Retry-After` 계산(design 결정 3, F-007). */
class AdQuotaTest :
    FunSpec({
        lateinit var clock: AdjustableClock
        lateinit var quota: AdQuota

        beforeTest {
            clock = AdjustableClock().apply { freeze() }
            quota = AdQuota(clock)
        }

        test("F-007 꺼져 있으면 모든 요청을 허용한다") {
            List(1_000) { quota.tryAcquire() }.all { it == null } shouldBe true
            quota.status() shouldBe AdQuota.QuotaStatus(enabled = false)
        }

        test("F-007 창 안에서 한도까지 허용하고, 거절은 세지 않고, 다음 창에서 다시 센다") {
            quota.enable(limit = 2, windowSeconds = 10)

            quota.tryAcquire().shouldBeNull()
            quota.tryAcquire().shouldBeNull()
            clock.advance(Duration.ofMillis(9_999))
            quota.tryAcquire() shouldBe 1L
            quota.tryAcquire() shouldBe 1L

            clock.advance(Duration.ofMillis(1))
            quota.tryAcquire().shouldBeNull()
            quota.tryAcquire().shouldBeNull()
            quota.tryAcquire() shouldBe 10L
        }

        test("F-007 Retry-After는 창 끝까지 남은 초를 올림한다") {
            quota.enable(limit = 0, windowSeconds = 10)

            quota.tryAcquire() shouldBe 10L
            clock.advance(Duration.ofMillis(3_500))
            quota.tryAcquire() shouldBe 7L
            clock.advance(Duration.ofSeconds(17))
            quota.tryAcquire() shouldBe 10L
        }

        test("F-007 다시 켜면 그 시각부터 새 창이다") {
            quota.enable(limit = 1, windowSeconds = 10)
            quota.tryAcquire().shouldBeNull()
            clock.advance(Duration.ofSeconds(4))

            quota.enable(limit = 1, windowSeconds = 10) shouldBe AdQuota.QuotaStatus(true, 1, 10)

            quota.tryAcquire().shouldBeNull()
            quota.tryAcquire() shouldBe 10L
        }

        test("F-007 끄면 한도 없이 허용한다") {
            quota.enable(limit = 0, windowSeconds = 10)

            quota.disable() shouldBe AdQuota.QuotaStatus(enabled = false)

            quota.tryAcquire().shouldBeNull()
        }

        test("F-007 요청 수가 음수이거나 창이 1초보다 짧으면 거절하고 한도는 그대로다") {
            quota.enable(limit = 3, windowSeconds = 10)

            shouldThrow<IllegalArgumentException> { quota.enable(limit = -1, windowSeconds = 10) }
            shouldThrow<IllegalArgumentException> { quota.enable(limit = 3, windowSeconds = 0) }

            quota.status() shouldBe AdQuota.QuotaStatus(true, 3, 10)
        }
    })
