package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `graceful-degradation` 스펙의 중요·선택 등급 시나리오 (F-002).
 *
 * "부분 응답 표시", "중요·선택 등급의 실패는 피드를 실패시키지 않는다", "선택 영역 빼기", "좋아요 상태를 모름으로 내린다"를 본다.
 * 하위 서비스 다섯은 WireMock 하나로 흉내 내고([DownstreamWireMock]), 시간 상한·예산은 테스트 기본값(2초, 5초)이다.
 * 같은 요구사항의 "느린 선택 의존성"은 상한이 달라 [FeedSlowDependencyScenarioTest]에, 연결 실패는 [FeedUnreachableScenarioTest]에 있다.
 * 작성자 프로필 로컬 캐시의 시나리오는 [FeedUserProfileCacheScenarioTest]다.
 *
 * - **서킷:** "서킷이 열린 선택 의존성"의 GIVEN은 레지스트리의 서킷을 직접 연다(`transitionToOpenState`). THEN은 하위 서비스가 받은 요청
 *   수로 본다. 서킷 상태는 컨텍스트에 남으므로 시나리오마다 모든 서킷을 [io.github.resilience4j.circuitbreaker.CircuitBreaker.reset]한다.
 * - **user 로컬 캐시:** 캐시는 컨텍스트에 남아 앞 시나리오에서 받은 작성자 프로필이 뒤 시나리오의 user 실패를 채운다. 유효 기간과 최대 크기는
 *   main 값이 없는 동안(tasks 4.2 전) 이 클래스의 속성으로 준다. `degradation.missing`에 `user`가 있다고 단언하는 시나리오는 이 클래스에서
 *   user-service가 한 번도 돌려준 적 없는 작성자([UNSEEN_AUTHOR_POSTS])의 게시글을 쓴다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.user-cache.expire-after-write=1h",
        "weave.user-cache.maximum-size=100",
    ],
)
class FeedDegradationScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = FeedHttpClient(localPort())

        beforeEach {
            wireMock.reset()
            resetCircuits()
        }

        context("부분 응답 표시") {
            test("F-002 모든 하위 서비스가 정상: 200이고 degradation.missing과 degradation.stale이 빈 목록이다") {
                wireMock.stubAllHealthy()

                val response = feed().getFeed()

                response.status shouldBe 200
                response.json()["degradation"] shouldBe mapOf("missing" to emptyList<String>(), "stale" to emptyList<Any>())
            }

            test("F-002 여러 영역이 빠진다: like-service와 ad-service가 503이면 200이고 degradation.missing은 like, ad 두 개다") {
                wireMock.stubAllHealthy()
                wireMock.stubFailure("like", 503)
                wireMock.stubFailure("ad", 503)

                val response = feed().getFeed()

                response.status shouldBe 200
                response.json().missing() shouldContainExactlyInAnyOrder listOf("like", "ad")
            }
        }

        context("중요·선택 등급의 실패는 피드를 실패시키지 않는다") {
            listOf("user", "like", "recommendation", "ad").forEach { dependency ->
                test("F-002 F-005 중요·선택 등급 하나가 5xx: $dependency 가 503이면 200이 오고 $dependency 에는 최대 시도 횟수(테스트 기본 설정에서 1)보다 많이 요청하지 않는다") {
                    wireMock.stubAllHealthy()
                    wireMock.stubFailure(dependency, 503)

                    val response = feed().getFeed()

                    response.status shouldBe 200
                    // 테스트 기본 설정(config/application.yml)은 재시도를 꺼 최대 시도 횟수가 1이다. ad는 재시도 인스턴스가 없다(add-retry-policy design 결정 12)
                    withClue("$dependency 가 받은 요청") { wireMock.requestsTo(wireMock.paths.getValue(dependency)) shouldHaveSize 1 }
                }
            }

            test(
                "F-002 중요·선택 등급이 모두 실패한다: post만 정상이고 나머지가 503이면 200과 함께 게시글이 같은 순서로 오고 degradation.missing은 user, like, recommendation, ad다",
            ) {
                wireMock.stubPosts(UNSEEN_AUTHOR_POSTS)
                listOf("user", "like", "recommendation", "ad").forEach { wireMock.stubFailure(it, 503) }

                val response = feed().getFeed()

                response.status shouldBe 200
                val body = response.json()
                body.posts().map { it["id"] } shouldBe UNSEEN_AUTHOR_POSTS.map { it.id.toInt() }
                body.missing() shouldContainExactlyInAnyOrder listOf("user", "like", "recommendation", "ad")
            }

            test(
                "F-002 서킷이 열린 선택 의존성: recommendation 서킷이 열려 있으면 recommendation-service는 요청을 받지 않고 200이 오며 degradation.missing에 recommendation이 있다",
            ) {
                wireMock.stubAllHealthy()
                circuitBreakerRegistry().circuitBreaker("recommendation").transitionToOpenState()

                val response = feed().getFeed()

                withClue("서킷이 열린 동안 recommendation이 받은 요청") { wireMock.requestsTo("/recommendations").shouldBeEmpty() }
                response.status shouldBe 200
                response.json().missing() shouldContain "recommendation"
            }
        }

        context("선택 영역 빼기") {
            test("F-002 추천이 실패한다: recommendation-service가 503이면 recommendations는 빈 목록이고 missing은 recommendation 하나이며 나머지는 정상이다") {
                wireMock.stubAllHealthy()
                wireMock.stubFailure("recommendation", 503)

                val response = feed().getFeed()

                response.status shouldBe 200
                val body = response.json()
                body["recommendations"] shouldBe emptyList<Any>()
                body.missing() shouldBe listOf("recommendation")
                body.posts() shouldBe HEALTHY_FEED_POSTS
                body["ads"] shouldBe wireMock.ads
            }

            test("F-002 광고가 속도 조절 응답을 준다: ad-service가 429와 Retry-After 1을 주면 200이고 ads는 빈 목록, missing은 ad 하나이며 ad에는 한 번만 요청한다") {
                wireMock.stubAllHealthy()
                wireMock.stubFailure("ad", 429, retryAfterSeconds = 1)

                val response = feed().getFeed()

                response.status shouldBe 200
                val body = response.json()
                body["ads"] shouldBe emptyList<Any>()
                body.missing() shouldBe listOf("ad")
                withClue("ad가 받은 요청") { wireMock.requestsTo("/ads") shouldHaveSize 1 }
                // F-005부터 feed-service는 Retry-After 동안 ad를 부르지 않는다. 대기는 컨텍스트에 남으므로, 뒤 시나리오가 광고를
                // 기다리지 않게 대기(1초)가 끝날 때까지 기다린다(add-retry-policy design 결정 4, 12)
                Thread.sleep(RETRY_AFTER_WAIT_MS)
            }
        }

        context("좋아요 상태를 모름으로 내린다") {
            test("F-002 좋아요 조회가 실패한다: like-service가 500이면 200이고 모든 게시글의 like.count가 null, like.myLike가 UNKNOWN이며 missing은 like 하나다") {
                wireMock.stubAllHealthy()
                wireMock.stubFailure("like", 500)

                val response = feed().getFeed(userId = "1")

                response.status shouldBe 200
                val body = response.json()
                body.posts().map { it["id"] } shouldBe HEALTHY_POSTS.map { it.id.toInt() }
                body.posts().forEach { post ->
                    withClue("게시글 ${post["id"]}의 like") { post["like"] shouldBe mapOf("count" to null, "myLike" to "UNKNOWN") }
                }
                body.missing() shouldBe listOf("like")
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

// post-service가 준 순서(12, 10)가 id 순서와 달라야 "같은 순서"를 확인할 수 있다.
private val HEALTHY_POSTS = listOf(Post(id = 12, authorId = 4), Post(id = 10, authorId = 3))

/** 이 클래스에서 user-service가 프로필을 돌려준 적 없는 작성자(7, 8)의 게시글. user 로컬 캐시가 채우지 못한다. */
private val UNSEEN_AUTHOR_POSTS = listOf(Post(id = 22, authorId = 8), Post(id = 21, authorId = 7))

/** [HEALTHY_POSTS]를 모든 하위 서비스가 정상일 때 피드가 보여 주는 모양. */
private val HEALTHY_FEED_POSTS: List<Map<String, Any?>> =
    listOf(
        feedPost(HEALTHY_POSTS[0], count = 5, myLike = "NOT_LIKED"),
        feedPost(HEALTHY_POSTS[1], count = 2, myLike = "LIKED"),
    )

private fun feedPost(
    post: Post,
    count: Int,
    myLike: String,
): Map<String, Any?> =
    mapOf(
        "id" to post.id.toInt(),
        "title" to post.title,
        "content" to post.content,
        "createdAt" to post.createdAt,
        "author" to
            mapOf(
                "id" to post.authorId.toInt(),
                "nickname" to "사용자${post.authorId}",
                "profileImageUrl" to "https://cdn.example.com/users/${post.authorId}.png",
                "unknown" to false,
            ),
        "like" to mapOf("count" to count, "myLike" to myLike),
    )

private fun DownstreamWireMock.stubAllHealthy() {
    stubPosts(HEALTHY_POSTS)
    stubUsers(listOf(4, 3))
    stubLikes(
        listOf(
            LikeState(postId = 12, likeCount = 5, liked = false),
            LikeState(postId = 10, likeCount = 2, liked = true),
        ),
    )
    stubRecommendations()
    stubAds()
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.posts(): List<Map<String, Any?>> = this["posts"] as List<Map<String, Any?>>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.missing(): List<String> = (this["degradation"] as Map<String, Any?>)["missing"] as List<String>

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()

private suspend fun circuitBreakerRegistry(): CircuitBreakerRegistry =
    testContextManager().testContext.applicationContext.getBean(CircuitBreakerRegistry::class.java)

/** 앞 시나리오가 남긴 서킷 상태와 기록을 지운다. 레지스트리의 서킷을 모두 닫힘으로 되돌린다. */
private suspend fun resetCircuits() = circuitBreakerRegistry().allCircuitBreakers.forEach { it.reset() }

/** "광고가 속도 조절 응답을 준다"의 `Retry-After: 1`이 끝날 때까지 기다리는 시간. 1초에 초 단위 경계 여유를 더했다. */
private const val RETRY_AFTER_WAIT_MS = 1_200L
