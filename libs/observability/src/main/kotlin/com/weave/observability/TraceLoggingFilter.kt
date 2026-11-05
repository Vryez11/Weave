package com.weave.observability

import io.micrometer.tracing.Tracer
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 응답마다 `X-Trace-Id` 헤더를 넣고, 요청 하나를 마칠 때 `http_request` 로그를 한 줄 남긴다(design 결정 5).
 *
 * Boot의 `ServerHttpObservationFilter`(서버 스팬을 연다) 바로 뒤에 두어 현재 스팬의 traceId를 읽는다.
 * 처리되지 않은 예외나 `sendError`는 Tomcat이 `/error`로 다시 디스패치하며 응답을 초기화할 수 있어,
 * 오류 디스패치에서도 헤더를 다시 넣는다. 요청 완료 로그는 첫 디스패치에서만 남긴다.
 * 처리되지 않은 예외로 끝나면 요청 완료 로그를 ERROR로 올려 예외를 담는다([TomcatServletExceptionLogFilter]).
 */
class TraceLoggingFilter(
    private val tracer: ObjectProvider<Tracer>,
) : OncePerRequestFilter(),
    Ordered {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun getOrder(): Int = ORDER

    override fun shouldNotFilterErrorDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        putTraceIdHeader(request, response)
        if (request.dispatcherType == DispatcherType.ERROR || request.requestURI.startsWith(ACTUATOR_PATH)) {
            filterChain.doFilter(request, response)
            return
        }

        val startedAt = System.nanoTime()
        var failure: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // 체인에서 예외가 올라온 시점의 응답 상태는 오류 처리 전이라 200이다
            val status = if (failure != null) HttpServletResponse.SC_INTERNAL_SERVER_ERROR else response.status
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            // 처리되지 않은 예외는 여기서 남긴다. Tomcat이 남기는 줄은 서버 스팬의 scope가 닫힌 뒤라 traceId가 없어 logback에서 막는다
            val event = if (failure != null) log.atError().setCause(failure) else log.atInfo()
            event
                .addKeyValue("event", "http_request")
                .addKeyValue("method", request.method)
                .addKeyValue("path", request.requestURI)
                .addKeyValue("status", status)
                .addKeyValue("durationMs", durationMs)
                .log("{} {} {} ({}ms)", request.method, request.requestURI, status, durationMs)
        }
    }

    override fun doFilterNestedErrorDispatch(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        putTraceIdHeader(request, response)
        filterChain.doFilter(request, response)
    }

    // 오류 디스패치에서는 첫 디스패치에서 읽은 값을 그대로 쓴다. 그때의 현재 스팬에 기대지 않는다
    private fun putTraceIdHeader(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val traceId =
            request.getAttribute(TRACE_ID_ATTRIBUTE) as? String
                ?: tracer
                    .getIfAvailable()
                    ?.currentSpan()
                    ?.context()
                    ?.traceId()
                    ?.takeIf { it.isNotEmpty() }
                    ?.also { request.setAttribute(TRACE_ID_ATTRIBUTE, it) }
                ?: return
        response.setHeader(TRACE_ID_HEADER, traceId)
    }

    companion object {
        const val TRACE_ID_HEADER = "X-Trace-Id"

        // Boot가 ServerHttpObservationFilter를 HIGHEST_PRECEDENCE + 1로 등록한다. 그 바로 뒤
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 2

        // Compose 헬스 체크와 Prometheus 수집이 몇 초마다 와서 로그를 덮으므로 로그만 건너뛴다(헤더는 넣는다)
        private const val ACTUATOR_PATH = "/actuator"
        private val TRACE_ID_ATTRIBUTE = "${TraceLoggingFilter::class.java.name}.traceId"
    }
}
