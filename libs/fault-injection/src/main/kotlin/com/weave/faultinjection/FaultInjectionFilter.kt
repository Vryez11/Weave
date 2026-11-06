package com.weave.faultinjection

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil

/**
 * 업무 요청마다 지연을 뽑아 요청 스레드에서 잠든 뒤, 에러율 확률로 처리하지 않고 500을 돌려준다(design 결정 2).
 * 느린 서비스가 자기 스레드를 붙잡는 모습과 같게 요청 스레드에서 잔다. 에러여도 지연은 먼저 더한다.
 * `/actuator` 경로(헬스 체크, 메트릭, 장애 설정 자체)는 건너뛴다. 장애 중에도 헬스 체크가 살고 장애를 거둘 수 있어야 한다.
 */
class FaultInjectionFilter(
    private val state: FaultState,
) : OncePerRequestFilter(),
    Ordered {
    override fun getOrder(): Int = ORDER

    // `/actuatorx`처럼 글자만 같은 경로는 actuator가 아니다
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == ACTUATOR_PATH || path.startsWith("$ACTUATOR_PATH/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val faults = state.current()
        val random = ThreadLocalRandom.current()
        sleep(faults.latencyDistribution().sampleMs(random))
        if (random.nextDouble() < faults.errorRate) {
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            response.contentType = "application/json"
            response.writer.write(ERROR_BODY)
            return
        }
        filterChain.doFilter(request, response)
    }

    // 밀리초 아래를 버리면 고정 지연(중앙값 = p99)이 설정보다 짧아지므로 나노초로 올려 잔다
    private fun sleep(ms: Double) {
        if (ms > 0) Thread.sleep(Duration.ofNanos(ceil(ms * NANOS_PER_MILLI).toLong()))
    }

    companion object {
        /**
         * `libs/observability`의 `TraceLoggingFilter`(`HIGHEST_PRECEDENCE + 2`) 뒤. 넣은 지연과 에러가 traceId가 붙은
         * 요청 완료 로그와 `http.server.requests`에 들어간다. 사이의 자리는 이 필터보다 먼저 거를 필터(ad 요청 한도)가 쓴다.
         */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 10

        private const val ACTUATOR_PATH = "/actuator"
        private const val ERROR_BODY = """{"error":"injected fault"}"""
        private const val NANOS_PER_MILLI = 1_000_000
    }
}
