package com.weave.feed.gateway

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.hc.client5.http.HttpHostConnectException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

class DownstreamFailureTest :
    FunSpec({
        test("F-004 HTTP 오류 응답은 http_<상태 코드>이고 exception은 그 예외 클래스다") {
            val body = "하위 서비스 본문".toByteArray()
            val error = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders.EMPTY, body, null)

            DownstreamFailure.of(error) shouldBe DownstreamFailure("http_503", error.javaClass.name)
            DownstreamFailure.of(HttpClientErrorException(HttpStatus.NOT_FOUND)).cause shouldBe "http_404"
        }

        test("F-004 CompletionException은 벗겨서 안의 예외로 분류한다") {
            val error = HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)

            DownstreamFailure.of(CompletionException(error)) shouldBe DownstreamFailure("http_500", error.javaClass.name)
        }

        test("F-004 원인에 ConnectException이 있는 ResourceAccessException은 connect_failure이고 exception은 가장 안쪽 예외다") {
            val refused = HttpHostConnectException("Connect to http://localhost:1 failed: Connection refused")
            val error = ResourceAccessException("I/O error", IOException("wrapped", refused))

            DownstreamFailure.of(CompletionException(error)) shouldBe
                DownstreamFailure("connect_failure", HttpHostConnectException::class.java.name)
            DownstreamFailure.of(ResourceAccessException("I/O error", ConnectException("refused"))).cause shouldBe "connect_failure"
        }

        test("F-004 그 밖의 ResourceAccessException은 io_failure다") {
            // 응답 타임아웃(SocketTimeoutException)은 F-001부터 timeout이다. 아래 "F-001 응답 타임아웃" 테스트가 본다
            val error = ResourceAccessException("I/O error", IOException("Connection reset"))

            DownstreamFailure.of(error) shouldBe DownstreamFailure("io_failure", IOException::class.java.name)
            DownstreamFailure.of(ResourceAccessException("I/O error")) shouldBe
                DownstreamFailure("io_failure", ResourceAccessException::class.java.name)
        }

        test("F-001 응답 타임아웃: 원인이 SocketTimeoutException인 ResourceAccessException은 timeout이다") {
            val error = ResourceAccessException("I/O error", SocketTimeoutException("Read timed out"))

            DownstreamFailure.of(CompletionException(error)) shouldBe
                DownstreamFailure("timeout", SocketTimeoutException::class.java.name)
        }

        test("F-001 호출 시간 상한: TimeLimiter의 TimeoutException은 timeout이다") {
            val error = TimeoutException("TimeLimiter 'recommendation' recorded a timeout exception.")

            DownstreamFailure.of(CompletionException(error)) shouldBe DownstreamFailure("timeout", TimeoutException::class.java.name)
        }

        test("F-001 서킷이 막은 호출: CallNotPermittedException은 circuit_open이다") {
            val circuitBreaker = CircuitBreaker.ofDefaults("recommendation").apply { transitionToOpenState() }
            val error = CallNotPermittedException.createCallNotPermittedException(circuitBreaker)

            DownstreamFailure.of(CompletionException(error)) shouldBe
                DownstreamFailure("circuit_open", CallNotPermittedException::class.java.name)
        }

        test("F-001 벌크헤드가 거절한 호출: BulkheadFullException은 ThreadPool·Semaphore 벌크헤드 모두 bulkhead_full이다") {
            val threadPool = BulkheadFullException.createBulkheadFullException(ThreadPoolBulkhead.ofDefaults("recommendation"))
            val semaphore = BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("like-write"))

            DownstreamFailure.of(CompletionException(threadPool)) shouldBe
                DownstreamFailure("bulkhead_full", BulkheadFullException::class.java.name)
            DownstreamFailure.of(semaphore).cause shouldBe "bulkhead_full"
        }

        test("F-001 요청 시간 예산 초과: TimeBudgetExhaustedException은 budget_exhausted다") {
            DownstreamFailure.of(TimeBudgetExhaustedException("user")) shouldBe
                DownstreamFailure("budget_exhausted", TimeBudgetExhaustedException::class.java.name)
        }

        context("F-001 서킷이 실패로 세는 것 (design 결정 7)") {
            val counted =
                mapOf(
                    "시간 상한(TimeoutException)" to CompletionException(TimeoutException("timeout")),
                    "응답 타임아웃" to ResourceAccessException("I/O error", SocketTimeoutException("Read timed out")),
                    "연결 실패" to ResourceAccessException("I/O error", ConnectException("refused")),
                    "그 밖의 입출력 실패" to ResourceAccessException("I/O error", IOException("Connection reset")),
                    "5xx" to HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE),
                )
            val notCounted =
                mapOf(
                    "4xx" to HttpClientErrorException(HttpStatus.NOT_FOUND),
                    "429" to HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS),
                    "벌크헤드 거절" to BulkheadFullException.createBulkheadFullException(ThreadPoolBulkhead.ofDefaults("recommendation")),
                    "열린 서킷이 막은 호출" to
                        CallNotPermittedException.createCallNotPermittedException(
                            CircuitBreaker.ofDefaults("recommendation").apply { transitionToOpenState() },
                        ),
                    "요청 시간 예산 초과" to TimeBudgetExhaustedException("user"),
                    "분류되지 않은 예외" to RestClientException("no converter"),
                )
            counted.forEach { (name, error) ->
                test("F-001 $name 은 센다") { DownstreamFailure.countsAsCircuitFailure(error) shouldBe true }
            }
            notCounted.forEach { (name, error) ->
                test("F-001 $name 은 세지 않는다") { DownstreamFailure.countsAsCircuitFailure(error) shouldBe false }
            }
        }

        test("F-004 그 밖의 예외는 other다") {
            DownstreamFailure.of(RestClientException("no converter")) shouldBe
                DownstreamFailure("other", RestClientException::class.java.name)
            DownstreamFailure.of(CompletionException(IllegalStateException("boom"))) shouldBe
                DownstreamFailure("other", IllegalStateException::class.java.name)
        }

        test("F-004 원인이 자기 자신을 가리켜도 멈춘다") {
            val error = SelfCausedException()

            DownstreamFailure.of(error) shouldBe DownstreamFailure("other", SelfCausedException::class.java.name)
        }
    })

private class SelfCausedException : RuntimeException("loop") {
    override val cause: Throwable get() = this
}
