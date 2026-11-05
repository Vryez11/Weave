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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.time.Duration.Companion.seconds

/**
 * `feed-aggregation` 델타의 "하위 서비스 호출 실패 로그" 시나리오 중 하위 서비스가 오류 상태를 주는 셋 (F-004).
 *
 * 로그는 [LogCapture]로 잡고, 실패 로그 줄은 `event`, `dependency`, 그리고 응답의 `X-Trace-Id`와 같은 `traceId`로 찾는다.
 * "연결할 수 없다"는 주소가 달라 Spring 컨텍스트를 따로 띄워야 해서 [FeedCallFailureLogUnreachableScenarioTest]에 둔다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedCallFailureLogScenarioTest :
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

        beforeEach { wireMock.reset() }

        test("F-004 하위 서비스가 5xx: recommendation이 503이면 피드는 500이고, X-Trace-Id와 같은 traceId로 recommendation http_503 실패 로그가 한 줄 남는다") {
            stubAllHealthy()
            wireMock.stubFailure("recommendation", 503)

            LogCapture().use { capture ->
                val response = feed().getFeed()

                response.statusCode() shouldBe 500
                val traceId = response.traceId()
                eventually(5.seconds) {
                    val lines = capture.failureLines("recommendation", traceId)
                    withClue("traceId가 X-Trace-Id와 같은 recommendation의 downstream_call_failed 줄") {
                        lines shouldHaveSize 1
                    }
                    lines.single()["cause"] shouldBe "http_503"
                }
            }
        }

        test("F-004 post 뒤에 부른 호출이 실패한다: like가 500이면 like http_500 실패 로그의 traceId가 X-Trace-Id와 같다") {
            stubAllHealthy()
            wireMock.stubFailure("like", 500)

            LogCapture().use { capture ->
                val response = feed().getFeed()

                val traceId = response.traceId()
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 like의 http_500 downstream_call_failed 줄") {
                        capture.failureLines("like", traceId).filter { it["cause"] == "http_500" }.shouldNotBeEmpty()
                    }
                }
            }
        }

        test("F-004 토글 전달이 실패한다: like-service가 토글에 503을 주면 like http_503 실패 로그가 남는다") {
            wireMock.stubToggleFailure(postId = 5, status = 503)

            LogCapture().use { capture ->
                val traceId = feed().toggleLike(5).traceId()

                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 like의 http_503 downstream_call_failed 줄") {
                        capture.failureLines("like", traceId).filter { it["cause"] == "http_503" }.shouldNotBeEmpty()
                    }
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

/**
 * `feed-aggregation` 델타의 "하위 서비스에 연결할 수 없다" 실패 로그 시나리오 (F-004).
 *
 * [FeedUnreachableScenarioTest]처럼 recommendation 주소만 아무것도 떠 있지 않은 포트(1번)로 준다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedCallFailureLogUnreachableScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach { wireMock.reset() }

        test("F-004 하위 서비스에 연결할 수 없다: recommendation 주소에 아무것도 없으면 recommendation connect_failure 실패 로그가 남는다") {
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3)))
            wireMock.stubUsers(listOf(3))
            wireMock.stubLikes(listOf(LikeState(postId = 10, likeCount = 0, liked = false)))
            wireMock.stubAds()

            LogCapture().use { capture ->
                val environment = testContextManager().testContext.applicationContext.environment
                val traceId = TracedFeedHttp(environment.getRequiredProperty("local.server.port").toInt()).getFeed().traceId()

                // 실패 원인이 WireMock의 응답이 아니라 연결 거부임을 확인한다
                wireMock.requestsTo("/recommendations").shouldBeEmpty()
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 recommendation의 connect_failure downstream_call_failed 줄") {
                        capture.failureLines("recommendation", traceId).filter { it["cause"] == "connect_failure" }.shouldNotBeEmpty()
                    }
                }
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) =
            DownstreamWireMock.registerBaseUrls(registry, closed = setOf("recommendation"))
    }
}

/** [dependency]의 `downstream_call_failed` 줄. [traceId]를 주면 그 trace의 줄만 남긴다. */
private fun LogCapture.failureLines(
    dependency: String,
    traceId: String? = null,
): List<Map<String, Any?>> =
    jsonLines().filter {
        it["event"] == "downstream_call_failed" &&
            it["dependency"] == dependency &&
            (traceId == null || it["traceId"] == traceId)
    }
