package com.weave.feed.gateway

import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.hc.client5.http.HttpHostConnectException
import org.apache.hc.core5.http.ConnectionRequestTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

/** add-retry-policy design 결정 2의 표: 다시 부르는 실패와 다시 부르지 않는 실패. */
class RetryPolicyTest :
    FunSpec({
        val retryable =
            mapOf(
                "500" to HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR),
                "503" to HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE),
                "CompletionException에 싸인 503" to CompletionException(HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)),
                "연결 실패(connect_failure)" to ResourceAccessException("I/O error", ConnectException("refused")),
                "HttpClient 5 연결 거절" to
                    CompletionException(
                        ResourceAccessException(
                            "I/O error",
                            IOException("wrapped", HttpHostConnectException("Connect to http://localhost:1 failed: Connection refused")),
                        ),
                    ),
                "응답 전 연결 끊김(io_failure)" to ResourceAccessException("I/O error", IOException("Connection reset")),
            )
        val notRetryable =
            mapOf(
                "커넥션 풀 대기 초과" to
                    ResourceAccessException("I/O error", ConnectionRequestTimeoutException("Timeout deadline: 100 MILLISECONDS")),
                "CompletionException에 싸인 커넥션 풀 대기 초과" to
                    CompletionException(
                        ResourceAccessException("I/O error", IOException("wrapped", ConnectionRequestTimeoutException("deadline"))),
                    ),
                "호출 시간 상한(TimeoutException)" to CompletionException(TimeoutException("TimeLimiter 'user' recorded a timeout exception.")),
                "응답 타임아웃" to ResourceAccessException("I/O error", SocketTimeoutException("Read timed out")),
                "404" to HttpClientErrorException(HttpStatus.NOT_FOUND),
                "429" to HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS),
                "서킷 열림" to
                    CallNotPermittedException.createCallNotPermittedException(
                        CircuitBreaker.ofDefaults("user").apply { transitionToOpenState() },
                    ),
                "벌크헤드 거절" to BulkheadFullException.createBulkheadFullException(ThreadPoolBulkhead.ofDefaults("user")),
                "요청 시간 예산 초과" to TimeBudgetExhaustedException("user"),
                "요청 수 상한" to RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("ad")),
                "Retry-After 대기" to RetryAfterHoldException("ad", Instant.parse("2026-11-08T00:00:02Z")),
                "분류되지 않은 예외" to RestClientException("no converter"),
            )

        retryable.forEach { (name, error) ->
            test("F-005 $name 은 다시 부른다") { RetryPolicy.retryable(error) shouldBe true }
        }
        notRetryable.forEach { (name, error) ->
            test("F-005 $name 은 다시 부르지 않는다") { RetryPolicy.retryable(error) shouldBe false }
        }
    })
