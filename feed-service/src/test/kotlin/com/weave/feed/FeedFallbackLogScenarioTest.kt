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
 * `graceful-degradation` 스펙 "대체 이벤트 로그" (F-002, F-004).
 *
 * 로그는 [LogCapture]로 잡고, 대체 로그 줄은 `event` `fallback_applied`, `dependency`, 그리고 응답의 `X-Trace-Id`와 같은 `traceId`로 찾는다.
 * 필드는 design 결정 9다: `event`, `dependency`, `fallback`, post `unavailable`이면 `reason`, user면 `cacheHits`·`cacheMisses`. 요청 스레드에서
 * 남기므로 MDC의 `traceId`가 그대로 붙는다. 로그 appender가 비동기라 줄은 `eventually`로 기다린다.
 *
 * - **post 캐시:** "게시글을 대체할 수 없다"의 `cache_miss`는 캐시 저장소가 살아 있고 비어 있어야 나온다(죽은 저장소는 `cache_error`). 그래서
 *   이 클래스는 [PostCacheRedis]의 주소를 준다. 다른 시나리오의 정상 요청이 남긴 비동기 쓰기와 겹치지 않게, 이 시나리오는 다른 시나리오가 쓰지
 *   않는 크기(`size=7`)로 부르고 그 키가 없는 것을 GIVEN에서 확인한다.
 * - **user 로컬 캐시:** 캐시는 컨텍스트에 남는다. "작성자 일부를 채우지 못한다"의 작성자 4가 캐시에 없도록, 이 클래스의 다른 시나리오는 작성자 3의
 *   게시글만 쓰고 user-service가 작성자 4를 돌려주는 일이 없다.
 * - **"정상이면 남기지 않는다":** 없음을 보려면 그 요청의 로그가 다 나왔다는 표지가 필요하다. 요청을 마칠 때 남는 `http_request` 줄
 *   (observability `TraceLoggingFilter`)이 보인 뒤 `fallback_applied` 줄이 없는지 본다. 대체 로그는 응답 전에 요청 스레드에서 남는다.
 * - **설정값:** stale 허용·쓰기 간격과 user 로컬 캐시 값은 main 값이 없는 동안(tasks 4.2, 4.3 전) 클래스 속성으로 준다. Redis 명령·연결
 *   타임아웃은 첫 연결에 흔들리지 않게 2초다([FeedPostStaleCacheScenarioTest]와 같다).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.post-cache.max-staleness=10m",
        "weave.post-cache.write-interval=1s",
        "spring.data.redis.timeout=2s",
        "spring.data.redis.connect-timeout=2s",
        "weave.user-cache.expire-after-write=1h",
        "weave.user-cache.maximum-size=100",
    ],
)
class FeedFallbackLogScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = TracedFeedHttp(localPort())

        beforeEach {
            wireMock.reset()
            PostCacheRedis.flush()
        }

        context("대체 이벤트 로그") {
            test(
                "F-002 F-004 선택 영역을 뺀다: recommendation-service가 503이면 recommendation omitted인 fallback_applied 줄이 하나 있고 그 traceId가 X-Trace-Id와 같으며 같은 호출의 downstream_call_failed 줄도 있다",
            ) {
                wireMock.stubAllHealthy()
                wireMock.stubFailure("recommendation", 503)

                LogCapture().use { capture ->
                    val response = feed().getFeed()

                    response.statusCode() shouldBe 200
                    val traceId = response.traceId()
                    eventually(5.seconds) {
                        val lines = capture.fallbackLines(traceId, "recommendation")
                        withClue("traceId가 X-Trace-Id와 같은 recommendation의 fallback_applied 줄") { lines shouldHaveSize 1 }
                        lines.single()["fallback"] shouldBe "omitted"
                        withClue("traceId가 X-Trace-Id와 같은 recommendation의 downstream_call_failed 줄") {
                            capture.eventLines("downstream_call_failed", traceId, "recommendation").shouldNotBeEmpty()
                        }
                    }
                }
            }

            test(
                "F-002 F-004 작성자 일부를 채우지 못한다: 캐시에 작성자 3만 있고 user-service가 503이면 작성자 3, 4의 피드에 user partial_cache인 fallback_applied 줄이 하나 있다",
            ) {
                wireMock.stubAllHealthy()
                withClue("작성자 3의 프로필을 캐시에 남기는 정상 요청") { feed().getFeed().statusCode() shouldBe 200 }
                wireMock.reset()
                wireMock.stubAllHealthy(posts = AUTHOR_3_AND_4_POSTS)
                wireMock.stubFailure("user", 503)

                LogCapture().use { capture ->
                    val response = feed().getFeed()

                    response.statusCode() shouldBe 200
                    val traceId = response.traceId()
                    eventually(5.seconds) {
                        val lines = capture.fallbackLines(traceId, "user")
                        withClue("traceId가 X-Trace-Id와 같은 user의 fallback_applied 줄") { lines shouldHaveSize 1 }
                        lines.single()["fallback"] shouldBe "partial_cache"
                    }
                }
            }

            test(
                "F-002 F-004 게시글을 대체할 수 없다: post 캐시가 비어 있고 post-service가 503이면 post unavailable, reason cache_miss인 fallback_applied 줄이 하나 있다",
            ) {
                wireMock.stubAllHealthy()
                wireMock.stubFailure("post", 503)
                withClue("post 캐시 ${PostCacheRedis.key(UNCACHED_SIZE)}가 없다") {
                    PostCacheRedis.exists(PostCacheRedis.key(UNCACHED_SIZE)) shouldBe false
                }

                LogCapture().use { capture ->
                    val response = feed().getFeed("?size=$UNCACHED_SIZE")

                    response.statusCode() shouldBe 503
                    val traceId = response.traceId()
                    eventually(5.seconds) {
                        val lines = capture.fallbackLines(traceId, "post")
                        withClue("traceId가 X-Trace-Id와 같은 post의 fallback_applied 줄") { lines shouldHaveSize 1 }
                        lines.single()["fallback"] shouldBe "unavailable"
                        lines.single()["reason"] shouldBe "cache_miss"
                    }
                }
            }

            test("F-002 F-004 정상이면 남기지 않는다: 하위 서비스 5개가 모두 정상이면 그 요청의 fallback_applied 줄이 없다") {
                wireMock.stubAllHealthy()

                LogCapture().use { capture ->
                    val response = feed().getFeed()

                    response.statusCode() shouldBe 200
                    val traceId = response.traceId()
                    eventually(5.seconds) {
                        withClue("요청을 마친 표지인 http_request 줄") { capture.eventLines("http_request", traceId).shouldNotBeEmpty() }
                    }
                    withClue("traceId가 X-Trace-Id와 같은 fallback_applied 줄") { capture.fallbackLines(traceId).shouldBeEmpty() }
                }
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun addresses(registry: DynamicPropertyRegistry) {
            DownstreamWireMock.registerBaseUrls(registry)
            PostCacheRedis.registerAddress(registry)
        }
    }
}

/** 다른 시나리오가 부르지 않아 post 캐시가 생기지 않는 크기. */
private const val UNCACHED_SIZE = 7

/** 작성자 3의 게시글. "작성자 일부를 채우지 못한다"를 뺀 시나리오는 이것만 쓴다. */
private val AUTHOR_3_POSTS = listOf(Post(id = 10, authorId = 3))

/** 작성자가 3, 4인 게시글. */
private val AUTHOR_3_AND_4_POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4))

/** 하위 서비스 다섯이 [posts]로 정상 응답한다. user-service는 [posts]의 작성자 프로필을 돌려준다. */
private fun DownstreamWireMock.stubAllHealthy(posts: List<Post> = AUTHOR_3_POSTS) {
    stubPosts(posts)
    stubUsers(posts.map { it.authorId }.distinct())
    stubLikes(posts.map { LikeState(postId = it.id, likeCount = 1, liked = false) })
    stubRecommendations()
    stubAds()
}

/** [traceId]의 `fallback_applied` 줄. [dependency]를 주면 그 의존성의 줄만 남긴다. */
private fun LogCapture.fallbackLines(
    traceId: String,
    dependency: String? = null,
): List<Map<String, Any?>> = eventLines("fallback_applied", traceId, dependency)

/** [traceId]이고 `event`가 [event]인 줄. [dependency]를 주면 그 의존성의 줄만 남긴다. */
private fun LogCapture.eventLines(
    event: String,
    traceId: String,
    dependency: String? = null,
): List<Map<String, Any?>> =
    jsonLines().filter {
        it["event"] == event && it["traceId"] == traceId && (dependency == null || it["dependency"] == dependency)
    }

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()
