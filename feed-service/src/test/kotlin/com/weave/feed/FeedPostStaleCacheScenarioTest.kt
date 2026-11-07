package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import com.weave.feed.DownstreamWireMock.longListParam
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.time.Instant

/**
 * `graceful-degradation` 스펙 "게시글을 stale 캐시로 대체한다", "게시글을 대체할 수 없으면 503"과 "대체 설정값은 설정으로 바꾼다"의
 * "환경 변수로 stale 허용 시간을 바꾼다" (F-002).
 *
 * post 캐시 저장소는 Testcontainers Redis([PostCacheRedis])다. 클래스 사이에 컨테이너 하나를 같이 쓰고 시나리오마다 비운다(`FLUSHDB`).
 * 상한·예산이 다른 "503도 시간 안에 나간다"는 [FeedPostUnavailableDeadlineScenarioTest], 환경 변수 시나리오는
 * [FeedPostStaleCacheOverrideScenarioTest]에 따로 둔다(컨텍스트 설정이 다르다).
 *
 * - **"N분 전의 캐시":** 캐시는 앱이 직접 쓰게 한다. 하위 서비스를 모두 정상으로 두고 피드를 한 번 부른 뒤(캐시 쓰기는 비동기라 키가 생길 때까지
 *   기다린다), 시계를 N분 옮긴다. 값 형식(design 결정 4)은 테스트가 만들지 않는다.
 * - **시계를 바꾸는 방법:** [MovableClockConfiguration]을 `@Import`해 컨텍스트의 `Clock`을 [MovableClock]으로 바꾼다. 실제 시간과 함께
 *   흐르고, 테스트가 요청과 요청 사이에 [MovableClock.advance]로 옮긴다. 그래서 2장의 구현은 아래를 모두 `Clock` Bean으로 잰다:
 *   캐시 값의 `cachedAt`(post 응답을 받은 시각), stale 판단(`now - cachedAt`), 쓰기 간격(`weave.post-cache.write-interval`).
 *   Redis TTL은 Redis의 실제 시간이라 시계를 옮겨도 값이 남는다. 그래서 "10분을 넘었다"는 값의 `cachedAt`으로 가른다(design 결정 4).
 * - **쓰기 간격:** 시나리오마다 같은 키(`size=20`)를 다시 쓴다. 앞 시나리오의 쓰기 간격에 걸리지 않게 시나리오마다 시계를 1시간 옮긴다.
 * - **`cachedAt`:** 캐시를 남긴 요청의 앞뒤 시각 사이여야 한다. 표기(ISO-8601 UTC)가 초 아래를 버려도 되게 앞쪽에 1초를 둔다.
 * - **설정값:** stale 허용 10분(`weave.post-cache.max-staleness`), 쓰기 간격 1초(`weave.post-cache.write-interval`)는 main 값이 없는 동안
 *   (tasks 4.3 전) 클래스 속성으로 준다. Redis 명령·연결 타임아웃은 테스트 기본값과 같은 생각으로 2초다(main 값이 들어와도 첫 연결에 흔들리지
 *   않게). user 로컬 캐시 값도 [FeedDegradationScenarioTest]처럼 준다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [MAX_STALENESS, WRITE_INTERVAL, REDIS_TIMEOUT, REDIS_CONNECT_TIMEOUT, USER_CACHE_TTL, USER_CACHE_SIZE],
)
@Import(MovableClockConfiguration::class)
class FeedPostStaleCacheScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = FeedHttpClient(localPort())

        beforeEach {
            wireMock.reset()
            PostCacheRedis.flush()
            resetCircuits()
            clock().advance(Duration.ofHours(1))
        }

        context("게시글을 stale 캐시로 대체한다") {
            test(
                "F-002 10분 안의 캐시가 있다: 5분 전 size=20 응답이 게시글 10, 11, 12였고 지금 post-service가 503이면 200과 함께 같은 순서로 오고 stale은 post 하나, cachedAt은 그때이며 user·like는 그 게시글로 요청을 받는다",
            ) {
                val cachedWindow = seedPostCache(feed(), clock())
                clock().advance(Duration.ofMinutes(5))
                wireMock.stubFailure("post", 503)
                wireMock.server.resetRequests()

                val response = feed().getFeed("?size=20")

                response.status shouldBe 200
                val body = response.json()
                body.postIds() shouldBe listOf(10, 11, 12)
                val stale = body.stale()
                stale shouldHaveSize 1
                stale.single()["section"] shouldBe "post"
                val cachedAt = Instant.parse(stale.single()["cachedAt"] as String)
                withClue("cachedAt $cachedAt 이 캐시를 남긴 요청의 시각 $cachedWindow 안이다") { (cachedAt in cachedWindow) shouldBe true }
                withClue("user-service가 받은 작성자 ID") {
                    wireMock.requestsTo("/users").single().longListParam("ids") shouldContainExactlyInAnyOrder listOf(3L, 4L, 5L)
                }
                withClue("like-service가 받은 게시글 ID") {
                    wireMock.requestsTo("/likes").single().longListParam("postIds") shouldContainExactlyInAnyOrder listOf(10L, 11L, 12L)
                }
            }

            test("F-002 post 서킷이 열려 있다: 1분 전의 size=20 캐시가 있고 post 서킷이 열려 있으면 post-service는 요청을 받지 않고 200과 함께 캐시의 게시글이 stale로 온다") {
                seedPostCache(feed(), clock())
                clock().advance(Duration.ofMinutes(1))
                circuitBreakerRegistry().circuitBreaker("post").transitionToOpenState()
                wireMock.server.resetRequests()

                val response = feed().getFeed("?size=20")

                withClue("서킷이 열린 동안 post가 받은 요청") { wireMock.requestsTo("/posts").shouldBeEmpty() }
                response.status shouldBe 200
                val body = response.json()
                body.postIds() shouldBe listOf(10, 11, 12)
                body.stale().map { it["section"] } shouldBe listOf("post")
            }

            test("F-002 다른 개수의 캐시는 쓰지 않는다: 1분 전의 size=20 캐시만 있고 post-service가 503이면 size=5 요청은 503이다") {
                seedPostCache(feed(), clock())
                clock().advance(Duration.ofMinutes(1))
                wireMock.stubFailure("post", 503)

                val response = feed().getFeed("?size=5")

                response.status shouldBe 503
            }
        }

        context("게시글을 대체할 수 없으면 503") {
            test("F-002 캐시가 없다: post 캐시가 비어 있고 post-service가 503이면 503이 오고 X-Trace-Id 헤더가 있다") {
                wireMock.stubAllHealthy()
                wireMock.stubFailure("post", 503)

                val response = TracedFeedHttp(localPort()).getFeed()

                response.statusCode() shouldBe 503
                response.traceId().shouldNotBeBlank()
            }

            test("F-002 캐시가 10분을 넘었다: 11분 전의 size=20 캐시만 있고 post-service가 503이면 503이다") {
                seedPostCache(feed(), clock())
                clock().advance(Duration.ofMinutes(11))
                wireMock.stubFailure("post", 503)

                val response = feed().getFeed("?size=20")

                response.status shouldBe 503
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

/**
 * "503도 시간 안에 나간다" (F-002). post 상한 100ms, 시간 예산 250ms를 이 클래스의 속성으로 준다.
 *
 * 첫 요청의 준비 비용(Redis 첫 연결, WireMock 첫 응답, 503 경로의 첫 실행)을 재지 않도록 시나리오의 GIVEN을 두기 전에 데운다:
 * [PostCacheRedis.warmUp]으로 연결을 맺고, 캐시가 없는 다른 크기(`size=7`)로 post가 바로 실패하는 요청을 한 번 보낸다. 그 뒤 캐시를 비워
 * GIVEN("post 캐시가 비어 있다")에서 시작한다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        MAX_STALENESS, WRITE_INTERVAL, REDIS_TIMEOUT, REDIS_CONNECT_TIMEOUT, USER_CACHE_TTL, USER_CACHE_SIZE,
        "resilience4j.timelimiter.instances.post.timeout-duration=100ms",
        "weave.feed.time-budget=250ms",
    ],
)
class FeedPostUnavailableDeadlineScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = FeedHttpClient(localPort())

        test("F-002 503도 시간 안에 나간다: 캐시가 비어 있고 post 상한 100ms, 예산 250ms에서 post-service와 recommendation-service가 2초 늦으면 300ms 안에 503이 온다") {
            PostCacheRedis.warmUp(feed())
            wireMock.stubAllHealthy()
            wireMock.stubFailure("post", 503)
            feed().getFeed("?size=7")
            wireMock.reset()
            PostCacheRedis.flush()

            wireMock.stubAllHealthy(postDelayMs = 2_000)
            wireMock.stubRecommendations(delayMs = 2_000)

            val response = feed().getFeed()

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

/**
 * "대체 설정값은 설정으로 바꾼다"의 "환경 변수로 stale 허용 시간을 바꾼다" (F-002, design 결정 14).
 *
 * **환경 변수를 주는 방법:** [FeedIsolationOverrideScenarioTest]와 같다. [EnvironmentOverride]가 이름이 `...-systemEnvironment`로 끝나는
 * [SystemEnvironmentPropertySource]를 환경 맨 앞에 붙여, `WEAVE_POSTCACHE_MAXSTALENESS=1m`이 실제 시스템 환경처럼
 * `weave.post-cache.max-staleness`로 읽힌다. 설정 파일 쪽 값은 클래스 속성의 10분이다. 환경 변수가 바인딩되지 않으면 2분 전 캐시가
 * 10분 안이라 200(stale)이 온다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [MAX_STALENESS, WRITE_INTERVAL, REDIS_TIMEOUT, REDIS_CONNECT_TIMEOUT, USER_CACHE_TTL, USER_CACHE_SIZE],
)
@Import(MovableClockConfiguration::class)
@ContextConfiguration(initializers = [FeedPostStaleCacheOverrideScenarioTest.EnvironmentOverride::class])
class FeedPostStaleCacheOverrideScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed() = FeedHttpClient(localPort())

        beforeEach {
            wireMock.reset()
            PostCacheRedis.flush()
        }

        test("F-002 환경 변수로 stale 허용 시간을 바꾼다: 환경 변수로 1분으로 바꿔 띄웠고 2분 전의 size=20 캐시만 있으며 post-service가 503이면 503이다") {
            seedPostCache(feed(), clock())
            clock().advance(Duration.ofMinutes(2))
            wireMock.stubFailure("post", 503)

            val response = feed().getFeed("?size=20")

            response.status shouldBe 503
        }
    }) {
    /** 환경 변수 형식의 이름을 실제 시스템 환경처럼 읽히는 속성 소스로 맨 앞에 붙인다. */
    class EnvironmentOverride : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            context.environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    "test-systemEnvironment",
                    mapOf<String, Any>("WEAVE_POSTCACHE_MAXSTALENESS" to "1m"),
                ),
            )
        }
    }

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
private const val REDIS_TIMEOUT = "spring.data.redis.timeout=2s"
private const val REDIS_CONNECT_TIMEOUT = "spring.data.redis.connect-timeout=2s"
private const val USER_CACHE_TTL = "weave.user-cache.expire-after-write=1h"
private const val USER_CACHE_SIZE = "weave.user-cache.maximum-size=100"

/** post-service가 주는 게시글. 작성자는 3, 4, 5다. */
private val POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4), Post(id = 12, authorId = 5))

private fun DownstreamWireMock.stubAllHealthy(postDelayMs: Int = 0) {
    stubPosts(POSTS, delayMs = postDelayMs)
    stubUsers(POSTS.map { it.authorId })
    stubLikes(POSTS.map { LikeState(postId = it.id, likeCount = 1, liked = false) })
    stubRecommendations()
    stubAds()
}

/**
 * 하위 서비스를 모두 정상으로 두고 `size=20` 피드를 한 번 불러 post 캐시를 남긴다. 캐시 쓰기는 비동기라 키가 생길 때까지 기다린다.
 * 그 요청을 보내기 전과 받은 뒤의 시각(앞쪽은 1초 여유)을 돌려준다. `cachedAt`은 이 안에 있어야 한다.
 */
private fun seedPostCache(
    feed: FeedHttpClient,
    clock: MovableClock,
): ClosedRange<Instant> {
    DownstreamWireMock.stubAllHealthy()
    val before = clock.instant()
    withClue("캐시를 남기는 정상 요청") { feed.getFeed("?size=20").status shouldBe 200 }
    val after = clock.instant()
    withClue("post 캐시 ${PostCacheRedis.key(20)}가 써졌다") { PostCacheRedis.awaitKey(PostCacheRedis.key(20)) shouldBe true }
    return before.minusSeconds(1)..after
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.postIds(): List<Any?> = (this["posts"] as List<Map<String, Any?>>).map { it["id"] }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.stale(): List<Map<String, Any?>> =
    (this["degradation"] as Map<String, Any?>)["stale"] as List<Map<String, Any?>>

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()

private suspend fun clock(): MovableClock = testContextManager().testContext.applicationContext.getBean(MovableClock::class.java)

private suspend fun circuitBreakerRegistry(): CircuitBreakerRegistry =
    testContextManager().testContext.applicationContext.getBean(CircuitBreakerRegistry::class.java)

/** 앞 시나리오가 남긴 서킷 상태와 기록을 지운다. */
private suspend fun resetCircuits() = circuitBreakerRegistry().allCircuitBreakers.forEach { it.reset() }
