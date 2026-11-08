package com.weave.feed.gateway

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * add-retry-policy design 결정 4, 8: 의존성마다 대기 끝 시각을 두고, 더 늦은 시각으로만 늘리며, 대기를 시작할 때만 끝 시각을 알린다.
 *
 * 대기 경계가 실제 시간에 흔들리지 않게 손으로만 움직이는 시계([HoldClock])를 쓴다. 시각은 0ms에서 시작한다.
 */
class RetryAfterHoldsTest :
    FunSpec({
        test("F-005 대기 중에는 막고 끝 시각이 지나면 푼다: 2초 대기면 1,999ms까지 retry_after로 막고 2,000ms에는 부른다") {
            val clock = HoldClock()
            val holds = RetryAfterHolds(clock)

            holds.hold("ad", Duration.ofSeconds(2)) shouldBe Instant.ofEpochMilli(2_000)

            clock.at(1_999)
            val rejected = shouldThrow<RetryAfterHoldException> { holds.check("ad") }
            rejected.holdUntil shouldBe Instant.ofEpochMilli(2_000)
            DownstreamFailure.of(rejected).cause shouldBe "retry_after"

            clock.at(2_000)
            shouldNotThrowAny { holds.check("ad") }
        }

        test("F-005 의존성마다 따로다: ad가 대기 중이어도 recommendation은 막지 않는다") {
            val holds = RetryAfterHolds(HoldClock())
            holds.hold("ad", Duration.ofSeconds(2))

            shouldNotThrowAny { holds.check("recommendation") }
        }

        test("F-005 대기 중에 429를 다시 받으면 더 늦은 시각으로만 늘린다: 짧은 대기는 끝 시각을 당기지 않는다") {
            val clock = HoldClock()
            val holds = RetryAfterHolds(clock)
            holds.hold("ad", Duration.ofSeconds(2))

            clock.at(1_000)
            withClue("1초 뒤 3초 대기는 4,000ms로 늘린다") { holds.hold("ad", Duration.ofSeconds(3)).shouldBeNull() }
            clock.at(1_500)
            withClue("1.5초 뒤 1초 대기는 4,000ms를 당기지 않는다") { holds.hold("ad", Duration.ofSeconds(1)).shouldBeNull() }

            clock.at(3_999)
            shouldThrow<RetryAfterHoldException> { holds.check("ad") }.holdUntil shouldBe Instant.ofEpochMilli(4_000)
            clock.at(4_000)
            shouldNotThrowAny { holds.check("ad") }
        }

        test("F-005 F-004 대기를 시작할 때만 끝 시각을 돌려준다: 대기가 끝난 뒤의 429는 다시 시작이다") {
            val clock = HoldClock()
            val holds = RetryAfterHolds(clock)

            withClue("처음") { holds.hold("ad", Duration.ofSeconds(1)) shouldBe Instant.ofEpochMilli(1_000) }
            withClue("대기 중") { holds.hold("ad", Duration.ofSeconds(5)).shouldBeNull() }

            clock.at(6_000)
            withClue("대기가 끝난 뒤") { holds.hold("ad", Duration.ofSeconds(1)) shouldBe Instant.ofEpochMilli(7_000) }
        }

        test("F-005 0 이하의 대기는 시작하지 않는다(최대 대기 0으로 끈 경우)") {
            val holds = RetryAfterHolds(HoldClock())

            holds.hold("ad", Duration.ZERO).shouldBeNull()
            shouldNotThrowAny { holds.check("ad") }
            holds.remaining("ad") shouldBe Duration.ZERO
        }

        test("F-005 남은 대기 시간: 대기 중이면 끝 시각까지, 대기 중이 아니거나 끝났으면 0이다") {
            val clock = HoldClock()
            val holds = RetryAfterHolds(clock)
            holds.remaining("ad") shouldBe Duration.ZERO

            holds.hold("ad", Duration.ofSeconds(2))
            clock.at(500)
            holds.remaining("ad") shouldBe Duration.ofMillis(1_500)

            clock.at(2_500)
            holds.remaining("ad") shouldBe Duration.ZERO
        }

        test("F-005 상한 없이 아주 긴 대기를 받아도 끝 시각이 넘치지 않는다") {
            val holds = RetryAfterHolds(HoldClock())

            holds.hold("ad", Duration.ofSeconds(Long.MAX_VALUE)) shouldBe Instant.MAX
            shouldThrow<RetryAfterHoldException> { holds.check("ad") }
        }
    })

/** 손으로만 움직이는 시계. [at]으로 epoch 밀리초를 정한다. */
private class HoldClock : Clock() {
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
