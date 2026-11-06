package com.weave.ad

import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation

/**
 * `/actuator/quota`(design 결정 3). `GET`은 지금 한도, `POST`는 JSON 본문의 `limit`(0 이상)과 `windowSeconds`(1 이상)로
 * 한도를 켜고(창은 이때부터 다시 센다), `DELETE`는 끈다. 셋 다 바뀐 뒤의 상태를 돌려준다. 검증에 걸리면 400이고 한도는 그대로다.
 */
@Endpoint(id = "quota")
class AdQuotaEndpoint(
    private val quota: AdQuota,
) {
    @ReadOperation
    fun quota(): AdQuota.QuotaStatus = quota.status()

    @WriteOperation
    fun enable(
        limit: Int,
        windowSeconds: Int,
    ): AdQuota.QuotaStatus =
        try {
            quota.enable(limit, windowSeconds)
        } catch (e: IllegalArgumentException) {
            throw InvalidEndpointRequestException(e.message ?: "Invalid quota", "Invalid quota")
        }

    @DeleteOperation
    fun disable(): AdQuota.QuotaStatus = quota.disable()
}
