package com.weave.faultinjection

import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation

/**
 * `/actuator/faults`(design 결정 2). `GET`은 지금 값, `POST`는 JSON 본문에 준 필드만 바꾸고, `DELETE`는 기본값으로 되돌린다.
 * 셋 다 바뀐 뒤의 설정을 돌려준다. 검증에 걸리면 400이고 설정은 그대로다([FaultConfig]가 만들 때 검증한다).
 */
@Endpoint(id = "faults")
class FaultsEndpoint(
    private val state: FaultState,
) {
    @ReadOperation
    fun faults(): FaultConfig = state.current()

    @WriteOperation
    fun change(
        latencyMedianMs: Double?,
        latencyP99Ms: Double?,
        errorRate: Double?,
    ): FaultConfig =
        try {
            state.update {
                it.copy(
                    latencyMedianMs = latencyMedianMs ?: it.latencyMedianMs,
                    latencyP99Ms = latencyP99Ms ?: it.latencyP99Ms,
                    errorRate = errorRate ?: it.errorRate,
                )
            }
        } catch (e: IllegalArgumentException) {
            throw InvalidEndpointRequestException(e.message ?: "Invalid fault settings", "Invalid fault settings")
        }

    @DeleteOperation
    fun reset(): FaultConfig = state.reset()
}
