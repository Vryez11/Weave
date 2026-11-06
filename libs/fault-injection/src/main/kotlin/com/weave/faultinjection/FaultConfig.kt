package com.weave.faultinjection

/**
 * 하위 서비스가 업무 요청마다 더하는 장애(design 결정 2). 필드 이름은 `/actuator/faults`의 JSON 필드 이름과 같다.
 * 만들 때 검증해 틀린 값이면 [IllegalArgumentException]을 던진다. `POST`는 [copy]로 준 필드만 바꾸므로 같은 검증을 거친다.
 */
data class FaultConfig(
    val latencyMedianMs: Double,
    val latencyP99Ms: Double,
    val errorRate: Double,
) {
    init {
        require(latencyMedianMs.isFinite() && latencyMedianMs >= 0) { "latencyMedianMs는 0 이상이어야 한다: $latencyMedianMs" }
        require(latencyP99Ms.isFinite() && latencyP99Ms >= 0) { "latencyP99Ms는 0 이상이어야 한다: $latencyP99Ms" }
        require(latencyP99Ms >= latencyMedianMs) {
            "latencyP99Ms는 latencyMedianMs 이상이어야 한다: 중앙값 $latencyMedianMs, p99 $latencyP99Ms"
        }
        require(errorRate in 0.0..1.0) { "errorRate는 0~1이어야 한다: $errorRate" }
    }

    fun latencyDistribution(): LatencyDistribution = LatencyDistribution(latencyMedianMs, latencyP99Ms)
}
