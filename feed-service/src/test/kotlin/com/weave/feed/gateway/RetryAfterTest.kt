package com.weave.feed.gateway

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * add-retry-policy design 결정 4: `Retry-After`는 초와 HTTP 날짜를 받고, 없거나 읽지 못하면 기본 대기, 최대 대기보다 길면 최대 대기까지다.
 */
class RetryAfterTest :
    FunSpec({
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val defaultHold = Duration.ofSeconds(1)
        val maxHold = Duration.ofSeconds(60)

        fun parse(
            header: String?,
            defaultHold: Duration? = Duration.ofSeconds(1),
            maxHold: Duration? = Duration.ofSeconds(60),
        ) = RetryAfter.parse(header, now, defaultHold, maxHold)

        fun httpDate(at: Instant) = DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC))

        test("F-005 초로 준다: Retry-After 2면 2초, 0이면 대기하지 않는다, 앞뒤 공백은 무시한다") {
            parse("2") shouldBe Duration.ofSeconds(2)
            parse("0") shouldBe Duration.ZERO
            parse(" 5 ") shouldBe Duration.ofSeconds(5)
        }

        test("F-005 HTTP 날짜로 준다: 3초 뒤의 IMF-fixdate면 3초, 이미 지난 날짜면 대기하지 않는다") {
            parse(httpDate(now.plusSeconds(3))) shouldBe Duration.ofSeconds(3)
            parse("Sun, 08 Nov 2026 12:00:03 GMT") shouldBe Duration.ofSeconds(3)
            parse(httpDate(now.minusSeconds(10))) shouldBe Duration.ZERO
        }

        test("F-005 머리글이 없거나 읽지 못하면 기본 대기다: 없음, 빈 값, 음수, 소수, 다른 형식의 날짜") {
            listOf(null, "", "-5", "1.5", "soon", "2026-11-08T12:00:03Z").forEach { header ->
                parse(header) shouldBe defaultHold
            }
        }

        test("F-005 최대 대기 시간보다 길면 최대 대기까지다: 초, HTTP 날짜, Long을 넘는 초, 최대 대기보다 긴 기본 대기") {
            parse("3600") shouldBe maxHold
            parse(httpDate(now.plusSeconds(3600))) shouldBe maxHold
            parse("99999999999999999999") shouldBe maxHold
            parse(null, defaultHold = Duration.ofSeconds(90)) shouldBe maxHold
        }

        test("F-005 최대 대기가 0이면 대기하지 않는다(설정으로 끈다)") {
            parse("2", maxHold = Duration.ZERO) shouldBe Duration.ZERO
            parse(null, maxHold = Duration.ZERO) shouldBe Duration.ZERO
        }

        test("F-005 속성이 없으면 읽지 못한 머리글에 대기하지 않고 상한도 두지 않는다") {
            parse(null, defaultHold = null, maxHold = null) shouldBe Duration.ZERO
            parse("3600", defaultHold = null, maxHold = null) shouldBe Duration.ofSeconds(3600)
        }
    })
