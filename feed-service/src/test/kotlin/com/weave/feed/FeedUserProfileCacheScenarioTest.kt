package com.weave.feed

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * `graceful-degradation` 스펙 "작성자 프로필을 로컬 캐시로 채운다"의 시나리오 (F-002).
 *
 * user 로컬 캐시의 유효 기간(`weave.user-cache.expire-after-write`)은 이 클래스의 속성으로 1초를, 최대 크기
 * (`weave.user-cache.maximum-size`)는 작성자 수보다 넉넉한 100을 준다(main 값은 tasks 4.2에서 넣는다. 속성 이름은 design 결정 14).
 * 유효 기간은 실제 시간으로 지나게 한다. 캐시의 `Ticker`는 `Clock` Bean(`systemUTC`)에서 오므로 실제 시간과 같이 간다(design 결정 11).
 *
 * - **시나리오 사이의 캐시:** 캐시는 컨텍스트에 남는다. 앞 시나리오가 넣은 프로필이 뒤 시나리오의 GIVEN("작성자 3만 받았다")을 깨지 않게,
 *   시나리오마다 유효 기간보다 길게([CACHE_CLEAR_WAIT_MS]) 기다려 앞의 프로필이 모두 지난 상태에서 시작한다.
 * - **한 시나리오 안의 시간:** 프로필을 받은 요청과 user가 실패하는 요청은 바로 이어 부르므로 그사이는 유효 기간(1초)보다 훨씬 짧다.
 * - user-service가 주는 프로필은 [stubProfiles]로 닉네임을 정해 둔다. "정상일 때는 캐시를 쓰지 않는다"가 old·new를 가른다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.user-cache.expire-after-write=1s",
        "weave.user-cache.maximum-size=100",
    ],
)
class FeedUserProfileCacheScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = FeedHttpClient(localPort())

        beforeEach {
            Thread.sleep(CACHE_CLEAR_WAIT_MS)
            wireMock.reset()
        }

        context("작성자 프로필을 로컬 캐시로 채운다") {
            test("F-002 캐시에 모든 작성자가 있다: 앞서 작성자 3, 4를 받았고 지금 user-service가 503이면 200이고 둘 다 앞서 받은 프로필이며 missing에 user가 없다") {
                wireMock.stubOthersHealthy()
                wireMock.stubProfiles(profile(3, "사용자3"), profile(4, "사용자4"))
                feed().getFeed().status shouldBe 200
                wireMock.stubFailure("user", 503)

                val response = feed().getFeed()

                response.status shouldBe 200
                val body = response.json()
                body.authorsById() shouldBe
                    mapOf(
                        3 to profile(3, "사용자3").plus("unknown" to false),
                        4 to profile(4, "사용자4").plus("unknown" to false),
                    )
                body.missing() shouldNotContain "user"
            }

            test(
                "F-002 캐시에 없는 작성자가 있다: 앞서 작성자 3만 받았고 지금 user-service가 503이면 작성자 3은 캐시의 프로필, 4는 unknown true이고 missing에 user가 있다",
            ) {
                wireMock.stubOthersHealthy()
                wireMock.stubProfiles(profile(3, "사용자3"))
                feed().getFeed().status shouldBe 200
                wireMock.stubFailure("user", 503)

                val response = feed().getFeed()

                response.status shouldBe 200
                val body = response.json()
                body.authorsById() shouldBe
                    mapOf(
                        3 to profile(3, "사용자3").plus("unknown" to false),
                        4 to mapOf("id" to 4, "nickname" to null, "profileImageUrl" to null, "unknown" to true),
                    )
                body.missing() shouldContain "user"
            }

            test("F-002 정상일 때는 캐시를 쓰지 않는다: 앞서 작성자 3이 old였고 지금 user-service가 new를 주면 new이고, 그 뒤 user-service가 503이면 new다") {
                wireMock.stubOthersHealthy()
                wireMock.stubProfiles(profile(3, "old"), profile(4, "사용자4"))
                feed().getFeed().status shouldBe 200
                wireMock.stubProfiles(profile(3, "new"), profile(4, "사용자4"))

                val healthy = feed().getFeed()

                healthy.status shouldBe 200
                withClue("user-service가 정상일 때 작성자 3의 nickname") { healthy.json().authorsById().getValue(3)["nickname"] shouldBe "new" }

                wireMock.stubFailure("user", 503)
                val failed = feed().getFeed()

                failed.status shouldBe 200
                withClue("user-service가 503일 때 작성자 3의 nickname") { failed.json().authorsById().getValue(3)["nickname"] shouldBe "new" }
            }

            test("F-002 캐시 유효 기간이 지났다: 유효 기간 1초에서 작성자 3의 프로필을 받은 지 1초가 넘었고 user-service가 503이면 작성자 3은 unknown true이고 missing에 user가 있다") {
                wireMock.stubOthersHealthy(posts = listOf(Post(id = 10, authorId = 3)))
                wireMock.stubProfiles(profile(3, "사용자3"))
                feed().getFeed().status shouldBe 200
                Thread.sleep(CACHE_CLEAR_WAIT_MS)
                wireMock.stubFailure("user", 503)

                val response = feed().getFeed()

                response.status shouldBe 200
                val body = response.json()
                body.authorsById().getValue(3)["unknown"] shouldBe true
                body.missing() shouldContain "user"
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/** 유효 기간(1초)이 확실히 지나도록 기다리는 시간. */
private const val CACHE_CLEAR_WAIT_MS = 1_500L

/** 작성자가 3, 4인 게시글. */
private val POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4))

private val mapper = jacksonObjectMapper()

private fun profile(
    id: Int,
    nickname: String,
): Map<String, Any?> = mapOf("id" to id, "nickname" to nickname, "profileImageUrl" to "https://cdn.example.com/users/$id.png")

/** user를 뺀 하위 서비스가 [posts]로 정상 응답한다. */
private fun DownstreamWireMock.stubOthersHealthy(posts: List<Post> = POSTS) {
    stubPosts(posts)
    stubLikes(posts.map { LikeState(postId = it.id, likeCount = 0, liked = false) })
    stubRecommendations()
    stubAds()
}

/** user-service가 [profiles]를 돌려준다. 앞서 둔 user 스텁(실패 포함)보다 나중에 둔 스텁이 이긴다. */
private fun DownstreamWireMock.stubProfiles(vararg profiles: Map<String, Any?>) {
    server.stubFor(
        get(urlPathEqualTo("/users")).willReturn(
            aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(mapper.writeValueAsString(mapOf("users" to profiles.toList()))),
        ),
    )
}

/** 게시글의 작성자를 작성자 ID로 모은다. */
@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.authorsById(): Map<Int, Map<String, Any?>> =
    (this["posts"] as List<Map<String, Any?>>)
        .map { it["author"] as Map<String, Any?> }
        .associateBy { it["id"] as Int }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.missing(): List<String> = (this["degradation"] as Map<String, Any?>)["missing"] as List<String>

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()
