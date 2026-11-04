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
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `feed-aggregation` 스펙에서 "종단 간 동작"을 뺀 시나리오 (F-006).
 *
 * 하위 서비스 다섯은 WireMock 하나로 흉내 내고([DownstreamWireMock]), 호출 여부와 파라미터는 WireMock의 요청 기록으로 본다.
 * 시간 기준은 스펙의 숫자(1,200ms 미만, 2초 이상)를 그대로 쓴다(design 결정 9).
 * "연결할 수 없다" 시나리오는 주소 설정이 달라 [FeedUnreachableScenarioTest]에 있다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
                "author" to author?.mapValues { (_, value) -> if (value is Long) value.toInt() else value },
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
            }

            test("F-006 작성자 프로필이 없다: 그 작성자의 게시글은 author가 null이고 나머지는 정상으로 온다") {
                stubAllHealthy(userIds = listOf(3))

                val response = feed().getFeed()

                response.status shouldBe 200
                response.json()["posts"] shouldBe
                    listOf(
                        expectedPost(posts[0], author = null, count = 5, myLike = "NOT_LIKED"),
                        expectedPost(posts[1], wireMock.profile(3), count = 2, myLike = "LIKED"),
                        expectedPost(posts[2], wireMock.profile(3), count = 0, myLike = "NOT_LIKED"),
                    )
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

            test("F-006 서로 기다리지 않는다: 다섯 의존성이 각각 400ms 늦으면 피드가 1,200ms 안에 온다") {
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

        context("보호 장치 없는 실패 전파") {
            DownstreamWireMock.dependencies.forEach { dependency ->
                test("F-006 하위 서비스 하나가 5xx: $dependency 가 503을 주면 피드는 500이고 $dependency 에는 한 번만 요청한다") {
                    stubAllHealthy()
                    wireMock.stubFailure(dependency, 503)

                    val response = feed().getFeed()

                    response.status shouldBe 500
                    wireMock.requestsTo(wireMock.paths.getValue(dependency)) shouldHaveSize 1
                }
            }

            test("F-006 하위 서비스가 속도 조절 응답을 준다: ad가 429와 Retry-After 1을 주면 피드는 500이고 ad에는 한 번만 요청한다") {
                stubAllHealthy()
                wireMock.stubFailure("ad", 429, retryAfterSeconds = 1)

                val response = feed().getFeed()

                response.status shouldBe 500
                wireMock.requestsTo("/ads") shouldHaveSize 1
            }

            test("F-006 하위 서비스 하나가 느리다: recommendation이 2초 늦으면 2초 넘게 기다린 뒤 추천까지 채워진 200이 온다") {
                stubAllHealthy()
                wireMock.stubRecommendations(delayMs = 2_000)

                val response = feed().getFeed()

                response.status shouldBe 200
                response.elapsedMs shouldBeGreaterThanOrEqual 2_000
                response.json()["recommendations"] shouldBe wireMock.recommendations
            }
        }

        context("좋아요 토글 전달") {
            test("F-006 토글을 넘긴다: like-service에 POST /likes/5/toggle과 X-User-Id 1로 한 번 요청하고 그 결과를 그대로 돌려준다") {
                wireMock.stubToggle(postId = 5, liked = true, likeCount = 3)

                val response = feed().toggleLike("5", userId = "1")

                val toggleRequests = wireMock.requestsTo("/likes/5/toggle")
                toggleRequests shouldHaveSize 1
                toggleRequests.single().method.value() shouldBe "POST"
                toggleRequests.single().getHeader("X-User-Id") shouldBe "1"
                response.status shouldBe 200
                response.json() shouldBe mapOf("postId" to 5, "liked" to true, "likeCount" to 3)
            }

            test("F-006 사용자 헤더가 없다: X-User-Id 없이 토글하면 400이 오고 like-service는 부르지 않는다") {
                wireMock.stubToggle(postId = 5, liked = true, likeCount = 3)

                val response = feed().toggleLike("5", userId = null)

                response.status shouldBe 400
                wireMock.allRequests().shouldBeEmpty()
            }

            listOf("0", "abc").forEach { userId ->
                test("F-006 사용자 헤더 값이 잘못됐다: X-User-Id $userId 로 토글하면 400이 오고 like-service는 부르지 않는다") {
                    wireMock.stubToggle(postId = 5, liked = true, likeCount = 3)

                    val response = feed().toggleLike("5", userId = userId)

                    response.status shouldBe 400
                    wireMock.allRequests().shouldBeEmpty()
                }
            }

            test("F-006 like-service가 토글에 실패한다: 503이면 500이 오고 like-service에는 토글을 한 번만 요청한다") {
                wireMock.stubToggleFailure(postId = 5, status = 503)

                val response = feed().toggleLike("5", userId = "1")

                response.status shouldBe 500
                wireMock.requestsTo("/likes/5/toggle") shouldHaveSize 1
            }

            listOf("0", "abc").forEach { postId ->
                test("F-006 게시글 ID가 양의 정수가 아니다: postId $postId 로 토글하면 400이 오고 like-service는 부르지 않는다") {
                    val response = feed().toggleLike(postId)

                    response.status shouldBe 400
                    wireMock.allRequests().shouldBeEmpty()
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
