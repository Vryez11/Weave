package com.weave.feed.client

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.weave.feed.DownstreamWireMock
import com.weave.feed.client.recommendation.RecommendationClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.util.UUID

/**
 * design 결정 6 끝: `HttpClientConfig.bodylessErrorMessages`가 만든 오류 예외도 기본 처리기처럼 메시지 변환기로 본문을 읽는다 (F-004).
 *
 * 예외 메시지에는 본문이 없어야 하고(`trace-logging` "로그에 남기지 않는 것"), 본문은 `getResponseBodyAs(...)`로 읽을 수 있어야 한다(F-005가 쓴다).
 */
@Tags("Integration")
@SpringBootTest
class HttpClientErrorBodyTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach { wireMock.reset() }

        suspend fun recommendationClient(): RecommendationClient =
            testContextManager().testContext.applicationContext.getBean(RecommendationClient::class.java)

        fun stubRecommendationError(
            status: Int,
            contentType: String,
            body: String,
        ) {
            wireMock.server.stubFor(
                get(urlPathEqualTo("/recommendations")).willReturn(
                    aResponse().withStatus(status).withHeader("Content-Type", contentType).withBody(body),
                ),
            )
        }

        test("F-004 하위 서비스 503 응답에서 만든 예외가 getResponseBodyAs(String)로 본문을 돌려주고 메시지에는 본문이 없다") {
            val marker = "weave-error-body-${UUID.randomUUID()}"
            stubRecommendationError(503, "text/plain;charset=UTF-8", marker)
            val client = recommendationClient()

            val error = shouldThrow<HttpServerErrorException> { client.getRecommendations() }

            error.getResponseBodyAs(String::class.java) shouldBe marker
            error.message shouldBe "503 Service Unavailable"
            error.message.orEmpty() shouldNotContain marker
        }

        test("F-004 하위 서비스 503 JSON 본문을 getResponseBodyAs로 객체로 읽는다") {
            val marker = "weave-error-body-${UUID.randomUUID()}"
            stubRecommendationError(503, "application/json", """{"error":"$marker"}""")
            val client = recommendationClient()

            val error = shouldThrow<HttpServerErrorException> { client.getRecommendations() }

            error.getResponseBodyAs(ErrorBody::class.java) shouldBe ErrorBody(marker)
            error.message.orEmpty() shouldNotContain marker
        }

        test("F-004 하위 서비스 404 응답에서 만든 예외도 getResponseBodyAs(String)로 본문을 돌려준다") {
            val marker = "weave-error-body-${UUID.randomUUID()}"
            stubRecommendationError(404, "text/plain;charset=UTF-8", marker)
            val client = recommendationClient()

            val error = shouldThrow<HttpClientErrorException> { client.getRecommendations() }

            error.getResponseBodyAs(String::class.java) shouldBe marker
            error.message shouldBe "404 Not Found"
        }
    }) {
    data class ErrorBody(
        val error: String,
    )

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}
