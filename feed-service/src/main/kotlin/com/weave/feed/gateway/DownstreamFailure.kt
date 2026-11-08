package com.weave.feed.gateway

import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.apache.hc.client5.http.ConnectTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

/**
 * 하위 서비스 호출 실패의 분류 (add-trace-logging design 결정 6, add-call-isolation design 결정 7).
 *
 * 실패 로그에는 예외 객체와 메시지 대신 이 두 값만 남긴다. `RestClientResponseException`의 메시지에 하위 서비스의 응답 본문이 들어가기 때문이다.
 * 서킷 브레이커가 실패로 세는지도 같은 분류로 정한다([countsAsCircuitFailure]). 로그의 `cause`와 서킷이 센 것이 어긋나지 않게 한다.
 *
 * @property cause `http_<상태 코드>`, `connect_failure`, `timeout`, `circuit_open`, `bulkhead_full`, `budget_exhausted`, `rate_limited`,
 *   `retry_after`, `io_failure`, `other` 중 하나
 * @property exception 원인 사슬에서 가장 안쪽 예외의 클래스 이름. `other`일 때 원인을 찾을 단서다
 */
data class DownstreamFailure(
    val cause: String,
    val exception: String,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DownstreamFailure::class.java)

        /** [dependency] 호출 실패를 `downstream_call_failed` WARN 한 줄로 남긴다. 예외 객체는 로거에 넘기지 않는다. */
        fun log(
            dependency: String,
            error: Throwable,
        ) {
            val failure = of(error)
            logger
                .atWarn()
                .addKeyValue("event", "downstream_call_failed")
                .addKeyValue("dependency", dependency)
                .addKeyValue("cause", failure.cause)
                .addKeyValue("exception", failure.exception)
                .log("{} 호출 실패: {}", dependency, failure.cause)
        }

        fun of(error: Throwable): DownstreamFailure {
            val unwrapped = unwrap(error)
            val chain = causeChain(unwrapped)
            val cause =
                when {
                    unwrapped is RestClientResponseException -> "http_${unwrapped.statusCode.value()}"
                    unwrapped is CallNotPermittedException -> "circuit_open"
                    unwrapped is BulkheadFullException -> "bulkhead_full"
                    unwrapped is TimeBudgetExhaustedException -> "budget_exhausted"
                    unwrapped is RequestNotPermitted -> "rate_limited"
                    unwrapped is RetryAfterHoldException -> "retry_after"
                    unwrapped is TimeoutException -> "timeout"
                    unwrapped is ResourceAccessException && chain.any(::isConnectFailure) -> "connect_failure"
                    // 연결 시간 초과(ConnectTimeoutException)도 SocketTimeoutException이라 위의 connect_failure가 먼저 잡는다.
                    // 커넥션 풀 대기 시간 초과(ConnectionRequestTimeoutException)는 SocketTimeoutException이 아니라 io_failure로 남는다
                    unwrapped is ResourceAccessException && chain.any { it is SocketTimeoutException } -> "timeout"
                    unwrapped is ResourceAccessException -> "io_failure"
                    else -> "other"
                }
            return DownstreamFailure(cause, chain.last().javaClass.name)
        }

        /**
         * 서킷 브레이커가 [error]를 실패로 세는지 (design 결정 7). 하위 서비스 문제(시간 상한·응답 타임아웃, 연결 실패, 입출력 실패, 5xx)만 센다.
         * 4xx(429 포함), 벌크헤드 거절, 열린 서킷이 막은 호출, 요청 시간 예산 초과, 요청 수 상한·Retry-After 대기로 막은 호출,
         * 분류되지 않은 예외는 세지 않는다.
         */
        fun countsAsCircuitFailure(error: Throwable): Boolean {
            val unwrapped = unwrap(error)
            if (unwrapped is RestClientResponseException) return unwrapped.statusCode.is5xxServerError
            return of(unwrapped).cause in CIRCUIT_FAILURE_CAUSES
        }

        private val CIRCUIT_FAILURE_CAUSES = setOf("timeout", "connect_failure", "io_failure")

        internal fun unwrap(error: Throwable): Throwable = if (error is CompletionException && error.cause != null) error.cause!! else error

        /**
         * 하위 서비스와 연결을 맺지 못했다. 커넥션 풀에서 연결을 얻지 못한 `ConnectionRequestTimeoutException`은 feed 쪽 자원 문제라 넣지 않는다.
         */
        private fun isConnectFailure(error: Throwable): Boolean =
            error is ConnectException ||
                error is NoRouteToHostException ||
                error is UnknownHostException ||
                error is ConnectTimeoutException

        /** 자기 자신이나 앞의 예외를 다시 가리키는 원인에서 멈춘다. */
        internal fun causeChain(error: Throwable): List<Throwable> {
            val chain = mutableListOf(error)
            var next = error.cause
            while (next != null && chain.none { it === next }) {
                chain += next
                next = next.cause
            }
            return chain
        }
    }
}

/**
 * 요청 시간 예산이 다 돼 그 의존성을 더 기다리지 않았다 (add-call-isolation design 결정 4). `cause`는 `budget_exhausted`이고 서킷에 세지 않는다.
 * 하위 서비스 실패가 아니라 피드 쪽 사정이라서다.
 */
class TimeBudgetExhaustedException(
    dependency: String,
) : RuntimeException("요청 시간 예산이 다 돼 $dependency 응답을 기다리지 않았다")

/**
 * [dependency]가 429와 함께 준 Retry-After 대기가 [holdUntil]까지라 부르지 않았다 (add-retry-policy design 결정 4, 7).
 * `cause`는 `retry_after`다. 대기는 서킷 바깥에서 막으므로 서킷에 닿지 않고, 재시도하지 않는다.
 */
class RetryAfterHoldException(
    dependency: String,
    val holdUntil: Instant,
) : RuntimeException("Retry-After 대기가 $holdUntil 까지라 $dependency 를 부르지 않았다")
