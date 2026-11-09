package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import com.weave.feed.DownstreamWireMock.longListParam
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `feed-aggregation` 스펙에서 "종단 간 동작"을 뺀 시나리오 (F-006, F-001, F-002).
 *
 * 하위 서비스 다섯은 WireMock 하나로 흉내 내고([DownstreamWireMock]), 호출 여부와 파라미터는 WireMock의 요청 기록으로 본다.
 * 시간 기준은 스펙의 숫자(1,200ms 미만)를 그대로 쓴다(add-feed-aggregation design 결정 9).
 * "서로 기다리지 않는다"의 GIVEN(의존성마다 호출 시간 상한 1초, 요청 시간 예산 2초)은 이 클래스의 속성으로 준다.
 * 다른 시나리오는 지연 없이 응답하므로 이 값에 영향을 받지 않는다(add-call-isolation design 결정 13).
 * 하위 서비스가 실패할 때의 응답은 고도화 4(add-graceful-degradation)부터 `graceful-degradation` 스펙이 정하고 그쪽 테스트
 * (`FeedDegradationScenarioTest` 등)에 있다. 여기서는 응답 구성의 `degradation`과 `author.unknown`이 정상 응답에서 어떤 값인지만 본다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.timelimiter.instances.post.timeout-duration=1s",
        "resilience4j.timelimiter.instances.user.timeout-duration=1s",
        "resilience4j.timelimiter.instances.like.timeout-duration=1s",
        "resilience4j.timelimiter.instances.recommendation.timeout-duration=1s",
        "resilience4j.timelimiter.instances.ad.timeout-duration=1s",
        "spring.http.serviceclient.post.read-timeout=1s",
        "spring.http.serviceclient.user.read-timeout=1s",
        "spring.http.serviceclient.like.read-timeout=1s",
        "spring.http.serviceclient.recommendation.read-timeout=1s",
        "spring.http.serviceclient.ad.read-timeout=1s",
        "weave.feed.time-budget=2s",
    ],
)
class FeedAggregationScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed(): FeedHttpClient {
            val environment = testContextManager().testContext.applicationContext.environment
            return FeedHttpClient(environment.getRequiredProperty("local.server.port").toInt())
        }

        // post-service가 준 순서(12, 10, 11)가 id 순서와 달라야 "같은 순서"를 확인할 수 있다.
        val posts =
            listOf(
                Post(id = 12, authorId = 4),
                Post(id = 10, authorId = 3),
                Post(id = 11, authorId = 3),
            )

        fun stubAllHealthy(
            delayMs: Int = 0,
            userIds: List<Long> = listOf(4, 3),
        ) {
            wireMock.stubPosts(posts, delayMs)
            wireMock.stubUsers(userIds, delayMs)
            wireMock.stubLikes(
                listOf(
                    LikeState(postId = 11, likeCount = 0, liked = false),
                    LikeState(postId = 12, likeCount = 5, liked = false),
                    LikeState(postId = 10, likeCount = 2, liked = true),
                ),
                delayMs,
            )
            wireMock.stubRecommendations(delayMs)
            wireMock.stubAds(delayMs)
        }

        fun expectedPost(
            post: Post,
            author: Map<String, Any>?,
            count: Int,
            myLike: String,
        ): Map<String, Any?> =
            mapOf(
                "id" to post.id.toInt(),
                "title" to post.title,
                "content" to post.content,
                "createdAt" to post.createdAt,
                "author" to author?.mapValues { (_, value) -> if (value is Long) value.toInt() else value }?.plus("unknown" to false),
                "like" to mapOf("count" to count, "myLike" to myLike),
            )

        beforeEach { wireMock.reset() }

        context("피드 응답 구성") {
            test("F-006 모든 하위 서비스가 정상: 게시글이 post-service 순서대로 작성자·좋아요와 함께 오고, 추천과 광고가 온다") {
                stubAllHealthy()

                val response = feed().getFeed(userId = "1")

                response.status shouldBe 200
                val body = response.json()
                body["posts"] shouldBe
                    listOf(
                        expectedPost(posts[0], wireMock.profile(4), count = 5, myLike = "NOT_LIKED"),
                        expectedPost(posts[1], wireMock.profile(3), count = 2, myLike = "LIKED"),
                        expectedPost(posts[2], wireMock.profile(3), count = 0, myLike = "NOT_LIKED"),
                    )
                body["recommendations"] shouldBe wireMock.recommendations
                body["ads"] shouldBe wireMock.ads
                body["degradation"] shouldBe mapOf("missing" to emptyList<String>(), "stale" to emptyList<Any>())
            }

            test("F-006 작성자 프로필이 없다: 그 작성자의 게시글은 author가 null이고 나머지는 정상으로 오며, user는 빠진 영역이 아니다") {
                stubAllHealthy(userIds = listOf(3))

                val response = feed().getFeed()

                response.status shouldBe 200
                val body = response.json()
                body["posts"] shouldBe
                    listOf(
                        expectedPost(posts[0], author = null, count = 5, myLike = "NOT_LIKED"),
                        expectedPost(posts[1], wireMock.profile(3), count = 2, myLike = "LIKED"),
                        expectedPost(posts[2], wireMock.profile(3), count = 0, myLike = "NOT_LIKED"),
                    )
                @Suppress("UNCHECKED_CAST")
                (body["degradation"] as Map<String, Any?>)["missing"] shouldBe emptyList<String>()
            }

            test("F-006 게시글이 없다: posts가 빈 목록으로 오고 user-service와 like-service는 부르지 않는다") {
                stubAllHealthy()
                wireMock.stubPosts(emptyList())

                val response = feed().getFeed()

                response.status shouldBe 200
                response.json()["posts"] shouldBe emptyList<Any>()
                wireMock.requestsTo("/users").shouldBeEmpty()
                wireMock.requestsTo("/likes").shouldBeEmpty()
            }
        }

        context("피드 요청 검증") {
            test("F-006 사용자 헤더가 없다: 400이 오고 하위 서비스는 하나도 부르지 않는다") {
                stubAllHealthy()

                val response = feed().getFeed(userId = null)

                response.status shouldBe 400
                wireMock.allRequests().shouldBeEmpty()
            }

            test("F-006 사용자 헤더가 양의 정수가 아니다: X-User-Id 0이면 400이 오고 하위 서비스는 하나도 부르지 않는다") {
                stubAllHealthy()

                val response = feed().getFeed(userId = "0")

                response.status shouldBe 400
                wireMock.allRequests().shouldBeEmpty()
            }

            test("F-006 개수를 정한다: size=5면 post-service에 size=5로 게시글을 요청한다") {
                stubAllHealthy()

                feed().getFeed("?size=5")

                val postRequests = wireMock.requestsTo("/posts")
                postRequests shouldHaveSize 1
                postRequests.single().queryParameter("size").values() shouldBe listOf("5")
            }

            listOf("0", "51").forEach { size ->
                test("F-006 범위를 벗어난 개수: size=$size 면 400이 오고 하위 서비스는 하나도 부르지 않는다") {
                    stubAllHealthy()

                    val response = feed().getFeed("?size=$size")

                    response.status shouldBe 400
                    wireMock.allRequests().shouldBeEmpty()
                }
            }
        }

        context("하위 서비스 호출 순서") {
            test("F-006 post 결과로 user와 like를 부른다: 작성자 ID 3, 4로 user를, 게시글 ID 10, 11, 12와 X-User-Id 1로 like를 한 번씩 부른다") {
                stubAllHealthy()

                val response = feed().getFeed(userId = "1")

                response.status shouldBe 200
                val userRequests = wireMock.requestsTo("/users")
                userRequests shouldHaveSize 1
                userRequests.single().longListParam("ids") shouldContainExactlyInAnyOrder listOf(3L, 4L)

                val likeRequests = wireMock.requestsTo("/likes")
                likeRequests shouldHaveSize 1
                likeRequests.single().longListParam("postIds") shouldContainExactlyInAnyOrder listOf(10L, 11L, 12L)
                likeRequests.single().getHeader("X-User-Id") shouldBe "1"
            }

            test("F-006 서로 기다리지 않는다: 호출 시간 상한 1초·예산 2초에서 다섯 의존성이 각각 400ms 늦으면 피드가 1,200ms 안에 200으로 온다") {
                // 첫 요청의 클래스 로딩과 연결 수립이 측정에 섞이지 않게 지연 없이 한 번 먼저 부른다.
                stubAllHealthy()
                feed().getFeed()
                wireMock.reset()
                stubAllHealthy(delayMs = 400)

                val response = feed().getFeed()

                response.status shouldBe 200
                response.elapsedMs shouldBeLessThan 1_200
            }
        }

        context("좋아요 상태 전달") {
            test("F-003 상태를 넘긴다: like-service에 PUT /likes/5와 X-User-Id 1, 같은 liked·version으로 한 번 요청하고 그 결과를 그대로 돌려준다") {
                wireMock.stubLikeState(postId = 5, liked = true, likeCount = 3)

                val response = feed().putLike("5", userId = "1", body = """{"liked":true,"version":7}""")

                val requests = wireMock.requestsTo("/likes/5")
                requests shouldHaveSize 1
                requests.single().method.value() shouldBe "PUT"
                requests.single().getHeader("X-User-Id") shouldBe "1"
                jacksonObjectMapper().readValue<Map<String, Any?>>(requests.single().bodyAsString) shouldBe
                    mapOf("liked" to true, "version" to 7)
                response.status shouldBe 200
                response.json() shouldBe mapOf("postId" to 5, "liked" to true, "likeCount" to 3)
            }

            test("F-003 사용자 헤더가 없다: X-User-Id 없이 PUT /feed/likes/5를 부르면 400이 오고 like-service는 부르지 않는다") {
                wireMock.stubLikeState(postId = 5, liked = true, likeCount = 3)

                val response = feed().putLike("5", userId = null)

                response.status shouldBe 400
                wireMock.allRequests().shouldBeEmpty()
            }

            listOf("0", "abc").forEach { userId ->
                test("F-003 사용자 헤더 값이 잘못됐다: X-User-Id $userId 로 PUT /feed/likes/5를 부르면 400이 오고 like-service는 부르지 않는다") {
                    wireMock.stubLikeState(postId = 5, liked = true, likeCount = 3)

                    val response = feed().putLike("5", userId = userId)

                    response.status shouldBe 400
                    wireMock.allRequests().shouldBeEmpty()
                }
            }

            listOf(
                "본문 없음" to null,
                "liked 없음" to """{"version":1}""",
                "version 없음" to """{"liked":true}""",
                "version 0" to """{"liked":true,"version":0}""",
                "version이 숫자가 아님" to """{"liked":true,"version":"abc"}""",
            ).forEach { (name, body) ->
                test("F-003 본문이 잘못됐다: $name 이면 400이 오고 like-service는 부르지 않는다") {
                    wireMock.stubLikeState(postId = 5, liked = true, likeCount = 3)

                    val response = feed().putLike("5", body = body)

                    response.status shouldBe 400
                    wireMock.allRequests().shouldBeEmpty()
                }
            }

            test("F-003 like-service가 상태 변경에 실패한다: 503이면 500이 오고 like-service에는 한 번만 요청한다") {
                wireMock.stubLikeStateFailure(postId = 5, status = 503)

                val response = feed().putLike("5", userId = "1")

                response.status shouldBe 500
                wireMock.requestsTo("/likes/5") shouldHaveSize 1
            }

            listOf("0", "abc").forEach { postId ->
                test("F-003 게시글 ID가 양의 정수가 아니다: postId $postId 로 부르면 400이 오고 like-service는 부르지 않는다") {
                    val response = feed().putLike(postId)

                    response.status shouldBe 400
                    wireMock.allRequests().shouldBeEmpty()
                }
            }

            test("F-003 토글 API는 없다: POST /feed/likes/5/toggle은 2xx가 오지 않고 like-service는 부르지 않는다") {
                val response = feed().postToggle("5")

                response.status shouldBeGreaterThanOrEqual 300
                wireMock.allRequests().shouldBeEmpty()
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}
