package com.weave.feed.gateway

import java.time.Clock
import java.time.Duration

/**
 * 의존성 하나의 재시도 예산 (add-retry-policy design 결정 5).
 *
 * 최근 [window]를 [SLOT] 칸으로 나눠 칸마다 원래 호출 수와 보낸 재시도 수를 센다. 다시 부를 수 있는 것은
 * `창 안의 재시도 수 < max(minRetries, floor(ratio × 창 안의 원래 호출 수))`일 때이고, [tryAcquireRetry]가 확인과 차감을 한 번에 한다.
 * 창은 지금 칸과 그 앞 `window ÷ 칸` 칸이라, 칸 경계 때문에 실제 창보다 최대 한 칸 길게 센다(짧게 세지는 않는다).
 *
 * 칸은 [clock]의 시각으로 정한다. 확인과 기록은 잠금 하나로 묶는다(400 RPS × 의존성 4개에서 다툼은 무시할 만하다).
 */
class RetryBudget(
    private val ratio: Double,
    private val minRetries: Int,
    window: Duration,
    private val clock: Clock,
) {
    init {
        require(ratio >= 0) { "재시도 예산 비율은 0 이상이어야 한다: $ratio" }
        require(minRetries >= 0) { "재시도 예산 최솟값은 0 이상이어야 한다: $minRetries" }
        require(window.isPositive) { "재시도 예산 창은 0보다 길어야 한다: $window" }
    }

    /** 창이 지금 칸 앞으로 걸치는 칸 수. 창이 칸으로 나누어떨어지지 않으면 올린다. */
    private val slotsBehind = Math.ceilDiv(window.toMillis(), SLOT.toMillis()).coerceAtLeast(1)

    private val slots = Array(slotsBehind.toInt() + 1) { Slot() }

    /** 원래 호출 하나를 센다. 서킷이 막은 호출도 원래 호출이다. */
    fun recordCall() {
        synchronized(this) { currentSlot().calls++ }
    }

    /** 다시 부를 수 있으면 재시도 하나를 차감하고 true, 예산이 다 됐으면 false. */
    fun tryAcquireRetry(): Boolean =
        synchronized(this) {
            val current = currentSlot()
            val inWindow = slots.filter { it.index >= current.index - slotsBehind }
            val calls = inWindow.sumOf { it.calls }
            val retries = inWindow.sumOf { it.retries }
            val allowed = maxOf(minRetries.toLong(), Math.floor(ratio * calls).toLong())
            if (retries >= allowed) return false
            current.retries++
            true
        }

    /** 지금 시각의 칸. 그 자리에 창 밖의 예전 칸이 남아 있으면 비운다. 잠금 안에서만 부른다. */
    private fun currentSlot(): Slot {
        val index = clock.millis() / SLOT.toMillis()
        val slot = slots[Math.floorMod(index, slots.size)]
        if (slot.index != index) {
            slot.index = index
            slot.calls = 0
            slot.retries = 0
        }
        return slot
    }

    private class Slot {
        var index = Long.MIN_VALUE
        var calls = 0L
        var retries = 0L
    }

    companion object {
        /** 칸 하나의 길이. 창 10초면 칸 100개이고, 칸 경계 오차는 창의 1%다. */
        val SLOT: Duration = Duration.ofMillis(100)
    }
}
