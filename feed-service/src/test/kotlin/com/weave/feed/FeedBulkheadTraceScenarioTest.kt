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
import kotlin.time.Duration.Companion.seconds

/**
 * `call-isolation` 델타의 "벌크헤드 스레드에서도 traceId가 이어진다" 중 이어지는 두 시나리오 (F-001, F-004).
 *
 * 하위 서비스 호출은 의존성마다의 벌크헤드 스레드에서 돈다(design 결정 1, 8). 하위 서비스가 받은 `traceparent`는 WireMock의 요청
 * 기록에서, 실패 로그는 [LogCapture]에서 읽어 응답의 `X-Trace-Id`와 견준다. 끊기는 쪽은 [FeedBulkheadTracePropagationOffTest]가 본다.
 * 격리 값은 테스트 기본값(상한 2초, 예산 5초)을 그대로 쓴다. 이 시나리오는 스레드가 바뀌어도 문맥이 넘어가는지만 본다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedBulkheadTraceScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed(): TracedFeedHttp {
            val environment = testContextManager().testContext.applicationContext.environment
            return TracedFeedHttp(environment.getRequiredProperty("local.server.port").toInt())
        }

        beforeEach {
            wireMock.reset()
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

        test("F-001 F-004 다섯 호출의 traceId: post, recommendation, ad, user, like로 간 요청 다섯 개의 traceparent traceId가 모두 X-Trace-Id와 같다") {
            val response = feed().getFeed()

            response.statusCode() shouldBe 200
            val traceId = response.traceId()
            wireMock.paths.forEach { (dependency, path) ->
                withClue("$dependency 요청의 traceparent traceId") {
                    wireMock.requestsTo(path).map { it.traceparentTraceId() } shouldBe listOf(traceId)
                }
            }
        }

        test("F-001 F-004 벌크헤드 스레드의 실패 로그: like가 500을 주면 like의 downstream_call_failed 줄의 traceId가 X-Trace-Id와 같다") {
            wireMock.stubFailure("like", 500)

            LogCapture().use { capture ->
                val response = feed().getFeed()

                response.statusCode() shouldBe 500
                val traceId = response.traceId()
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 like의 downstream_call_failed 줄") {
                        capture
                            .jsonLines()
                            .filter { it["event"] == "downstream_call_failed" && it["dependency"] == "like" && it["traceId"] == traceId }
                            .shouldNotBeEmpty()
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
