package com.weave.feed.gateway

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
            val error = ResourceAccessException("I/O error", SocketTimeoutException("Read timed out"))

            DownstreamFailure.of(error) shouldBe DownstreamFailure("io_failure", SocketTimeoutException::class.java.name)
            DownstreamFailure.of(ResourceAccessException("I/O error")) shouldBe
                DownstreamFailure("io_failure", ResourceAccessException::class.java.name)
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
