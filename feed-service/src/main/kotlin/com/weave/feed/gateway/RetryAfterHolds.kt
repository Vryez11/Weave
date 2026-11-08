package com.weave.feed.gateway

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 의존성마다의 Retry-After 대기 끝 시각 (add-retry-policy design 결정 4).
 *
 * 재시도 루프 바로 안, 서킷 브레이커 바깥에서 [check]로 막는다. 대기 끝 시각은 더 늦은 쪽으로만 늘어나고, 그 시각이 지나면 저절로 풀린다.
 * 시각은 [clock]으로 읽는다.
 */
class RetryAfterHolds(
    private val clock: Clock,
) {
    private val holdUntil = ConcurrentHashMap<String, Instant>()

    /** [dependency]가 대기 중이면 [RetryAfterHoldException]을 던진다. */
    fun check(dependency: String) {
        val until = holdUntil[dependency] ?: return
        if (clock.instant() < until) throw RetryAfterHoldException(dependency, until)
    }

    /**
     * [dependency]를 지금부터 [hold] 동안 부르지 않는다. 이미 더 늦게까지 대기 중이면 그대로 둔다.
     *
     * 대기 중이 아니던 의존성이 대기를 시작했으면 끝 시각을, 대기 중이었거나 [hold]가 0 이하라 시작하지 않았으면 null을 돌려준다
     * (대기 시작 로그는 처음 한 번만 남긴다, design 결정 8).
     */
    fun hold(
        dependency: String,
        hold: Duration,
    ): Instant? {
        if (!hold.isPositive) return null
        val now = clock.instant()
        // 상한 없는 아주 긴 Retry-After(속성이 없을 때)는 Instant 범위를 넘으므로 끝 시각을 Instant.MAX로 둔다
        val until = if (hold < Duration.between(now, Instant.MAX)) now + hold else Instant.MAX
        var started: Instant? = null
        holdUntil.compute(dependency) { _, previous ->
            when {
                previous == null || previous <= now -> until.also { started = it }
                previous < until -> until
                else -> previous
            }
        }
        return started
    }

    /** 남은 대기 시간. 대기 중이 아니면 0이다. */
    fun remaining(dependency: String): Duration {
        val until = holdUntil[dependency] ?: return Duration.ZERO
        return Duration.between(clock.instant(), until).coerceAtLeast(Duration.ZERO)
    }
}
