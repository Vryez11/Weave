package com.weave.feed.gateway

import org.apache.hc.core5.http.ConnectionRequestTimeoutException
import org.springframework.web.client.RestClientResponseException

/**
 * 멱등한 조회에서 다시 부를 실패 (add-retry-policy design 결정 2). [DownstreamFailure]와 같은 분류를 쓴다.
 *
 * 다시 부르는 것은 5xx, `connect_failure`, `io_failure`(커넥션 풀 대기 초과는 뺀다)뿐이다. 시간 상한·응답 타임아웃은 느린 의존성에 부하만
 * 더하고, 4xx(429 포함)는 요청 자체가 거절됐고, 서킷 열림·벌크헤드 거절·요청 시간 예산 초과·요청 수 상한·Retry-After 대기는 우리 쪽이
 * 막은 것이라 다시 부르지 않는다. 남은 시간 예산과 재시도 예산은 재시도 루프가 따로 본다.
 */
object RetryPolicy {
    private val RETRYABLE_CAUSES = setOf("connect_failure", "io_failure")

    fun retryable(error: Throwable): Boolean {
        val unwrapped = DownstreamFailure.unwrap(error)
        if (unwrapped is RestClientResponseException) return unwrapped.statusCode.is5xxServerError
        if (DownstreamFailure.of(unwrapped).cause !in RETRYABLE_CAUSES) return false
        // 커넥션 풀에서 연결을 얻지 못한 것은 feed 쪽 자원이 모자란 것이라 다시 부르면 더 모자란다
        return DownstreamFailure.causeChain(unwrapped).none { it is ConnectionRequestTimeoutException }
    }
}
