package com.weave.ad

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 외부 API의 쿼터를 흉내 내는 요청 한도(design 결정 3). 기본은 꺼져 있고, 메모리에만 있어 다시 띄우면 꺼진다.
 *
 * 고정 창이다. 한도를 켠 시각부터 창 길이마다 새 창이 시작되고, 창 안에서 허용한 요청만 센다(거절한 요청은 세지 않는다).
 * 요청 스레드와 엔드포인트가 함께 바꾸므로 모든 함수를 이 객체로 잠근다.
 */
class AdQuota(
    private val clock: Clock,
) {
    private var quota: Quota? = null

    @Synchronized
    fun enable(
        limit: Int,
        windowSeconds: Int,
    ): QuotaStatus {
        require(limit >= 0) { "limit은 0 이상이어야 한다: $limit" }
        require(windowSeconds >= 1) { "windowSeconds는 1 이상이어야 한다: $windowSeconds" }
        quota = Quota(limit, Duration.ofSeconds(windowSeconds.toLong()), clock.instant())
        return status()
    }

    @Synchronized
    fun disable(): QuotaStatus {
        quota = null
        return status()
    }

    @Synchronized
    fun status(): QuotaStatus =
        quota?.let { QuotaStatus(enabled = true, limit = it.limit, windowSeconds = it.window.seconds) }
            ?: QuotaStatus(enabled = false)

    /** 요청 하나를 한도에 센다. 허용하면 null, 거절하면 `Retry-After`로 줄 초(지금 창이 끝날 때까지 남은 시간을 올림, 1 이상)다. */
    @Synchronized
    fun tryAcquire(): Long? {
        val quota = quota ?: return null
        val now = clock.instant()
        val windowIndex = Math.floorDiv(Duration.between(quota.start, now).toNanos(), quota.window.toNanos())
        if (windowIndex != quota.windowIndex) {
            quota.windowIndex = windowIndex
            quota.used = 0
        }
        if (quota.used < quota.limit) {
            quota.used++
            return null
        }
        val windowEnd = quota.start.plus(quota.window.multipliedBy(windowIndex + 1))
        return maxOf(1, Math.ceilDiv(Duration.between(now, windowEnd).toNanos(), NANOS_PER_SECOND))
    }

    data class QuotaStatus(
        val enabled: Boolean,
        val limit: Int? = null,
        val windowSeconds: Long? = null,
    )

    private class Quota(
        val limit: Int,
        val window: Duration,
        val start: Instant,
    ) {
        var windowIndex = 0L
        var used = 0
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
