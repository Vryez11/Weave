package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `graceful-degradation` 스펙 "대체 수단의 장애는 피드를 막지 않는다" (F-002, design 결정 4, 5, 15).
 *
 * 캐시 저장소의 상태마다 컨텍스트 설정이 달라 클래스를 셋으로 나눈다.
 *
 * | 클래스 | 캐시 저장소 | 시나리오 |
 * |---|---|---|
 * | [FeedPostCacheOutageScenarioTest] | 죽었다. 아무것도 듣지 않는 포트(`localhost:1`, 테스트 기본값과 같다) | "죽었고 post는 정상", "post와 함께 죽었다" |
 * | [FeedPostCacheReadPauseScenarioTest] | 응답하지 않는다. Testcontainers Redis에 `CLIENT PAUSE 1000 ALL`, 읽기 상한 30ms | "응답하지 않는다" |
 * | [FeedPostCacheWritePauseScenarioTest] | 응답하지 않는다. 같은 방법, 명령 타임아웃 2초 | "캐시 쓰기를 기다리지 않는다" |
 *
 * - **시간을 재는 시나리오:** 첫 요청의 준비 비용(WireMock 첫 응답, Redis 첫 연결, 503 경로의 첫 실행)을 재지 않게 GIVEN을 두기 전에 같은
 *   경로를 한 번 지나게 한다. Redis 컨테이너를 쓰는 클래스는 [PostCacheRedis.warmUp]으로 연결을 맺는다. 멈춘 Redis에서는 첫 연결의
 *   핸드셰이크가 멈춰 읽기 상한과 상관없이 늦어지기 때문이다.
 * - **"캐시 쓰기를 기다리지 않는다"의 타임아웃:** 명령 타임아웃을 2초로 둬야 시나리오가 무엇을 가른다. 30ms로 두면 쓰기를 응답 경로에서 해도
 *   30ms만 늦어 300ms 안에 들어온다. 2초면 응답 경로의 쓰기는 멈춤(1초) 동안 기다려 300ms를 넘긴다.
 * - **멈춤:** `CLIENT PAUSE`는 1초 뒤 스스로 풀린다. 다음 시나리오가 멈춘 Redis를 만나지 않게 시나리오 끝에서 풀릴 때까지 기다린다.
 * - **설정값:** stale 허용·쓰기 간격과 user 로컬 캐시 값은 main 값이 없는 동안(tasks 4.2, 4.3 전) 클래스 속성으로 준다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        MAX_STALENESS, WRITE_INTERVAL, USER_CACHE_TTL, USER_CACHE_SIZE,
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=1",
    ],
)
class FeedPostCacheOutageScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = FeedHttpClient(localPort())

        beforeEach { wireMock.reset() }

        test("F-002 캐시 저장소가 죽었고 post는 정상: 캐시 저장소에 연결할 수 없고 하위 서비스 5개가 정상이면 200이고 missing과 stale이 빈 목록이며 헬스는 UP이다") {
            wireMock.stubAllHealthy()

            val response = feed().getFeed()

            response.status shouldBe 200
            response.json()["degradation"] shouldBe mapOf("missing" to emptyList<String>(), "stale" to emptyList<Any>())
            withClue("GET /actuator/health의 status") { health(localPort()) shouldBe "UP" }
        }

        test("F-002 캐시 저장소와 post가 함께 죽었다: 캐시 저장소에 연결할 수 없고 post-service가 503이면 300ms 안에 503이 온다") {
            wireMock.stubAllHealthy()
            wireMock.stubFailure("post", 503)
            feed().getFeed()

            val response = feed().getFeed()

            response.status shouldBe 503
            withClue("응답까지 걸린 시간(ms)") { response.elapsedMs shouldBeLessThan 300L }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/**
 * "캐시 저장소가 응답하지 않는다" (F-002). 읽기 시간 상한(`spring.data.redis.timeout`)을 스펙대로 30ms로 준다. 연결 타임아웃은 2초로 둬
 * 데우는 동안의 첫 연결이 흔들리지 않게 한다(main 값이 들어와도 덮는다).
 *
 * GIVEN의 `size=20` 캐시는 앱이 정상 요청으로 쓰게 하고, 멈추기 전에 캐시가 없는 다른 크기(`size=7`)로 post가 실패하는 요청을 한 번 보내
 * 캐시 읽기와 503 경로를 데운다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        MAX_STALENESS, WRITE_INTERVAL, USER_CACHE_TTL, USER_CACHE_SIZE,
        "spring.data.redis.timeout=30ms",
        "spring.data.redis.connect-timeout=2s",
    ],
)
class FeedPostCacheReadPauseScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = FeedHttpClient(localPort())

        afterEach { Thread.sleep(PAUSE_WAIT_MS) }

        test(
            "F-002 캐시 저장소가 응답하지 않는다: 읽기 상한 30ms이고 size=20 캐시가 있지만 캐시 저장소가 1초 동안 응답하지 않으며 post-service가 503이면 300ms 안에 503이 온다",
        ) {
            PostCacheRedis.warmUp(feed())
            wireMock.stubAllHealthy()
            withClue("캐시를 남기는 정상 요청") { feed().getFeed("?size=20").status shouldBe 200 }
            withClue("post 캐시 ${PostCacheRedis.key(20)}가 써졌다") { PostCacheRedis.awaitKey(PostCacheRedis.key(20)) shouldBe true }
            wireMock.stubFailure("post", 503)
            feed().getFeed("?size=7")

            PostCacheRedis.pause(Duration.ofSeconds(1))
            val response = feed().getFeed("?size=20")

            response.status shouldBe 503
            withClue("응답까지 걸린 시간(ms)") { response.elapsedMs shouldBeLessThan 300L }
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

/** "캐시 쓰기를 기다리지 않는다" (F-002). 명령 타임아웃이 2초인 이유는 파일 맨 위 KDoc에 있다. */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        MAX_STALENESS, WRITE_INTERVAL, USER_CACHE_TTL, USER_CACHE_SIZE,
        "spring.data.redis.timeout=2s",
        "spring.data.redis.connect-timeout=2s",
    ],
)
class FeedPostCacheWritePauseScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = FeedHttpClient(localPort())

        afterEach { Thread.sleep(PAUSE_WAIT_MS) }

        test("F-002 캐시 쓰기를 기다리지 않는다: 캐시 저장소가 1초 동안 응답하지 않고 하위 서비스 5개가 정상이면 300ms 안에 200이 온다") {
            PostCacheRedis.warmUp(feed())
            wireMock.stubAllHealthy()

            PostCacheRedis.pause(Duration.ofSeconds(1))
            val response = feed().getFeed("?size=20")

            response.status shouldBe 200
            withClue("응답까지 걸린 시간(ms)") { response.elapsedMs shouldBeLessThan 300L }
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

private const val MAX_STALENESS = "weave.post-cache.max-staleness=10m"
private const val WRITE_INTERVAL = "weave.post-cache.write-interval=1s"
private const val USER_CACHE_TTL = "weave.user-cache.expire-after-write=1h"
private const val USER_CACHE_SIZE = "weave.user-cache.maximum-size=100"

/** `CLIENT PAUSE 1000`이 풀릴 때까지 기다리는 시간. */
private const val PAUSE_WAIT_MS = 1_200L

private val POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4))

private val mapper = jacksonObjectMapper()

private val httpClient: HttpClient = HttpClient.newHttpClient()

private fun DownstreamWireMock.stubAllHealthy() {
    stubPosts(POSTS)
    stubUsers(POSTS.map { it.authorId })
    stubLikes(POSTS.map { LikeState(postId = it.id, likeCount = 1, liked = false) })
    stubRecommendations()
    stubAds()
}

/** `GET /actuator/health` 본문의 `status`. */
private fun health(port: Int): Any? {
    val response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    return mapper.readValue<Map<String, Any?>>(response.body())["status"]
}

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()
