package com.weave.feed.gateway

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 429 응답의 `Retry-After` 머리글로 그 의존성을 부르지 않을 시간을 정한다 (add-retry-policy design 결정 4).
 *
 * 초(0 이상의 정수)와 HTTP 날짜(RFC 1123, IMF-fixdate)를 읽는다. 머리글이 없거나 읽지 못하면(음수 포함) [defaultHold]이고,
 * [maxHold]보다 길면 [maxHold]까지만이다. 기본 대기도 최대 대기를 넘지 않는다(`max-hold: 0s`면 대기를 끈다). 이미 지난 날짜는 0이다.
 */
object RetryAfter {
    const val HEADER = "Retry-After"

    private val SECONDS = Regex("\\d+")

    /**
     * [header]를 [now] 기준의 대기 시간으로 바꾼다. [defaultHold]가 null이면 읽지 못한 머리글에 대기하지 않고, [maxHold]가 null이면 상한을 두지 않는다
     * (값은 tasks 4.4에서 넣는다).
     */
    fun parse(
        header: String?,
        now: Instant,
        defaultHold: Duration?,
        maxHold: Duration?,
    ): Duration {
        val hold = header?.trim()?.let { readSeconds(it) ?: readDate(it, now) } ?: defaultHold ?: Duration.ZERO
        return if (maxHold != null && hold > maxHold) maxHold else hold
    }

    /** 숫자만 있으면 초. Long을 넘는 값은 아주 긴 대기로 본다(상한이 자른다). */
    private fun readSeconds(value: String): Duration? {
        if (!SECONDS.matches(value)) return null
        return Duration.ofSeconds(value.toLongOrNull() ?: Long.MAX_VALUE)
    }

    private fun readDate(
        value: String,
        now: Instant,
    ): Duration? {
        val at =
            try {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            } catch (_: DateTimeParseException) {
                return null
            }
        return Duration.between(now, at).coerceAtLeast(Duration.ZERO)
    }
}
