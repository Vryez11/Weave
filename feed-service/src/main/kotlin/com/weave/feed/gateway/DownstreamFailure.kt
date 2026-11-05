package com.weave.feed.gateway

import org.slf4j.LoggerFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
import java.util.concurrent.CompletionException

/**
 * 하위 서비스 호출 실패의 분류 (design 결정 6).
 *
 * 실패 로그에는 예외 객체와 메시지 대신 이 두 값만 남긴다. `RestClientResponseException`의 메시지에 하위 서비스의 응답 본문이 들어가기 때문이다.
 * F-001에서 타임아웃, 서킷 열림, 벌크헤드 거절이 `cause`의 새 값으로 더해진다.
 *
 * @property cause `http_<상태 코드>`, `connect_failure`, `io_failure`, `other` 중 하나
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
            val unwrapped = if (error is CompletionException && error.cause != null) error.cause!! else error
            val chain = causeChain(unwrapped)
            val cause =
                when {
                    unwrapped is RestClientResponseException -> "http_${unwrapped.statusCode.value()}"
                    unwrapped is ResourceAccessException && chain.any { it is ConnectException } -> "connect_failure"
                    unwrapped is ResourceAccessException -> "io_failure"
                    else -> "other"
                }
            return DownstreamFailure(cause, chain.last().javaClass.name)
        }

        /** 자기 자신이나 앞의 예외를 다시 가리키는 원인에서 멈춘다. */
        private fun causeChain(error: Throwable): List<Throwable> {
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
