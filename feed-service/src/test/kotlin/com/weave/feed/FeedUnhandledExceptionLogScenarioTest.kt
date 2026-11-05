package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import com.weave.observability.testing.LogCapture
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpServerErrorException
import kotlin.time.Duration.Companion.seconds

/**
 * `trace-logging` "JSON 로그 형식"의 "처리되지 않은 예외로 끝난 요청" 시나리오 (F-004).
 *
 * 실패하는 요청 하나를 부르는 동안 쓰인 줄만 보려고, 앞뒤에 정상 요청을 하나씩 보내 그 줄이 나올 때까지 기다린다.
 * 비동기 appender는 들어온 순서대로 쓰므로, 앞 요청의 줄이 나온 뒤부터 뒤 요청의 줄이 나오기 전까지가 그 요청의 구간이다.
 * 앞 테스트가 남긴 늦은 줄은 앞 요청보다 먼저 쓰이고, Tomcat이 처리되지 않은 예외를 남기는 줄은 응답 전에 쓰여 뒤 요청보다 먼저 온다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedUnhandledExceptionLogScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed(): TracedFeedHttp {
            val environment = testContextManager().testContext.applicationContext.environment
            return TracedFeedHttp(environment.getRequiredProperty("local.server.port").toInt())
        }

        fun stubAllHealthy() {
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4)))
            wireMock.stubUsers(listOf(3, 4))
            wireMock.stubLikes(
                listOf(
                    LikeState(postId = 10, likeCount = 1, liked = false),
                    LikeState(postId = 11, likeCount = 0, liked = false),
                ),
            )
            wireMock.stubRecommendations()
            wireMock.stubAds()
        }

        /** 정상 요청을 하나 보내고 그 요청 완료 줄이 나올 때까지 기다린다. [lines]에서 그 줄의 위치를 돌려준다. */
        suspend fun LogCapture.awaitProbe(): Int {
            val probeTraceId = feed().getFeed().traceId()
            return eventually(5.seconds) {
                val lines = lines()
                val probeIndex = lines.indexOfFirst { it.json?.get("event") == "http_request" && it.json?.get("traceId") == probeTraceId }
                withClue("확인용 요청의 http_request 줄") { (probeIndex >= 0) shouldBe true }
                probeIndex
            }
        }

        beforeEach { wireMock.reset() }

        test("F-004 처리되지 않은 예외로 끝난 요청: GET /feed가 500이면 그 요청의 ERROR 줄은 모두 X-Trace-Id를 갖고, 한 줄에 실패 예외가 있다") {
            stubAllHealthy()

            LogCapture().use { capture ->
                val start = capture.awaitProbe() + 1

                wireMock.stubFailure("recommendation", 503)
                val response = feed().getFeed()
                response.statusCode() shouldBe 500
                val traceId = response.traceId()

                wireMock.reset()
                stubAllHealthy()
                val end = capture.awaitProbe()

                val errorLines =
                    capture
                        .lines()
                        .subList(start, end)
                        .mapNotNull { it.json }
                        .filter { it["level"] == "ERROR" }
                withClue("실패한 요청을 처리하며 쓴 ERROR 줄") { errorLines.shouldNotBeEmpty() }
                errorLines.forEach { line ->
                    withClue("ERROR 줄의 traceId (logger ${line["logger"]})") { line["traceId"] shouldBe traceId }
                }
                withClue("stack_trace에 ${HttpServerErrorException::class.java.name}이 있는 ERROR 줄") {
                    errorLines
                        .filter { (it["stack_trace"] as? String)?.contains(HttpServerErrorException::class.java.name) == true }
                        .shouldNotBeEmpty()
                }
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}
