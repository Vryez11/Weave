package com.weave.faultinjection

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/**
 * 평시 지연의 로그 정규분포(design 결정 2). 중앙값 m과 p99 q에서 μ = ln m, σ = ln(q/m) / 2.326으로 뽑는다.
 * m = q면 σ = 0이라 늘 m이다. exp(ln m)의 반올림으로 m보다 짧아지지 않게 계산하지 않고 m을 그대로 돌려준다.
 * m = 0이면 로그 정규분포로 나타낼 수 없어 지연을 더하지 않는다.
 * 여러 요청 스레드가 함께 부르므로 상태를 두지 않고, 난수는 부르는 쪽이 넘긴다(필터는 `ThreadLocalRandom`, 테스트는 시드).
 */
class LatencyDistribution(
    private val medianMs: Double,
    p99Ms: Double,
) {
    private val mu = ln(medianMs)
    private val sigma = if (medianMs > 0) ln(p99Ms / medianMs) / Z_99 else 0.0

    fun sampleMs(random: Random): Double = if (sigma == 0.0) medianMs else exp(mu + sigma * random.nextGaussian())

    private companion object {
        /** 표준 정규분포의 99번째 백분위수 */
        const val Z_99 = 2.326
    }
}
