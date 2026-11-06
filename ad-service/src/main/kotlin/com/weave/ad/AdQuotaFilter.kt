package com.weave.ad

import com.weave.faultinjection.FaultInjectionFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 요청 한도를 넘은 업무 요청에 처리하지 않고 429와 `Retry-After`를 돌려준다(design 결정 3).
 * `/actuator` 경로(헬스 체크, 메트릭, 한도 설정 자체)는 세지도 막지도 않는다.
 */
class AdQuotaFilter(
    private val quota: AdQuota,
) : OncePerRequestFilter(),
    Ordered {
    override fun getOrder(): Int = ORDER

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == ACTUATOR_PATH || path.startsWith("$ACTUATOR_PATH/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val retryAfterSeconds = quota.tryAcquire()
        if (retryAfterSeconds == null) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = SC_TOO_MANY_REQUESTS
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        response.contentType = "application/json"
        response.writer.write(ERROR_BODY)
    }

    companion object {
        /**
         * 장애 주입 필터보다 먼저 건다. 쿼터를 넘은 외부 API는 지연 없이 바로 429를 준다.
         * `libs/observability`의 추적·요청 로그 필터 뒤라 429도 traceId가 붙은 요청 완료 로그에 남는다.
         */
        const val ORDER = FaultInjectionFilter.ORDER - 1

        private const val SC_TOO_MANY_REQUESTS = 429
        private const val ACTUATOR_PATH = "/actuator"
        private const val ERROR_BODY = """{"error":"quota exceeded"}"""
    }
}
