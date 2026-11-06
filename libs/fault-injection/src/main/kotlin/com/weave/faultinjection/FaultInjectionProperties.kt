package com.weave.faultinjection

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 장애 주입 설정(design 결정 2). 서비스는 `application-experiment.yml`에서 [enabled]만 켠다.
 * 나머지는 `DELETE /actuator/faults`가 되돌아가는 기본값이다. 중앙값 20ms·p99 50ms는 PRD F-007의 "p99 약 50ms"에
 * 중앙값을 p99의 40%로 둔 값이고(실험 조건이라 측정 대신 이 계산이 근거다), 기본 에러율은 0이다.
 */
@ConfigurationProperties("weave.fault-injection")
data class FaultInjectionProperties(
    val enabled: Boolean = false,
    val latencyMedianMs: Double = 20.0,
    val latencyP99Ms: Double = 50.0,
    val errorRate: Double = 0.0,
) {
    fun defaults(): FaultConfig = FaultConfig(latencyMedianMs, latencyP99Ms, errorRate)
}
