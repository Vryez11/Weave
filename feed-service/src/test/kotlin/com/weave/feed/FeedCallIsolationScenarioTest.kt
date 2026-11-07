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
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * `call-isolation` 델타의 "의존성별 호출 시간 상한" 시나리오 (F-001).
 *
 * GIVEN의 상한(recommendation 200ms, user·post 100ms)은 클래스 속성으로 함께 준다. 시나리오마다 상한을 준 의존성 하나만
 * 늦게 응답하고 나머지는 지연 없이 응답하므로 서로 영향을 주지 않는다. 응답 타임아웃(`read-timeout`)은 테스트 기본값(2초)으로 둬,
 * 응답을 끊는 것이 HttpClient가 아니라 호출 시간 상한(TimeLimiter)인지 본다.
 * 첫 요청의 클래스 로딩과 연결 수립이 100ms 상한에 걸리지 않게, 시나리오마다 지연 없이 한 번 먼저 부른다.
 * 무응답은 WireMock 지연을 상한과 응답 타임아웃보다 길게(3초) 줘 흉내 낸다.
 *
 * 같은 파일의 [FeedCallIsolationBudgetScenarioTest]가 요청 시간 예산을, [FeedCallIsolationBulkheadScenarioTest]가 벌크헤드를 본다.
 * 값이 달라 Spring 컨텍스트를 나눈다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.timelimiter.instances.recommendation.timeout-duration=200ms",
        "resilience4j.timelimiter.instances.user.timeout-duration=100ms",
        "resilience4j.timelimiter.instances.post.timeout-duration=100ms",
    ],
)
class FeedCallIsolationScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed(): TracedFeedHttp = TracedFeedHttp(localPort())

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            feed().getFeed()
        }

        test("F-001 느린 의존성: recommendation 상한이 200ms이고 2초 늦으면 400ms 안에 응답하고 recommendation timeout 실패 로그가 남는다") {
            wireMock.stubRecommendations(delayMs = 2_000)

            LogCapture().use { capture ->
                val (response, elapsed) = measureTimedValue { feed().getFeed() }

                elapsed shouldBeLessThan 400.milliseconds
                val traceId = response.traceId()
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 recommendation의 timeout downstream_call_failed 줄") {
                        capture.failureLines("recommendation", "timeout", traceId).shouldNotBeEmpty()
                    }
                }
            }
        }

        test("F-001 응답하지 않는 의존성: user 상한이 100ms이고 user가 응답을 보내지 않으면 400ms 안에 응답하고 user timeout 실패 로그가 있다") {
            wireMock.stubUsers(listOf(3, 4), delayMs = UNRESPONSIVE_MS)

            LogCapture().use { capture ->
                val (response, elapsed) = measureTimedValue { feed().getFeed() }

                elapsed shouldBeLessThan 400.milliseconds
                val traceId = response.traceId()
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 user의 timeout downstream_call_failed 줄") {
                        capture.failureLines("user", "timeout", traceId).shouldNotBeEmpty()
                    }
                }
            }
        }

        test("F-001 상한 안의 지연: post 상한이 100ms이고 post가 60ms 늦으면 200과 함께 게시글이 채워진 피드가 온다") {
            wireMock.stubPosts(HEALTHY_POSTS, delayMs = 60)

            val response = FeedHttpClient(localPort()).getFeed()

            response.status shouldBe 200
            val posts = response.json()["posts"].shouldNotBeNull() as List<*>
            posts.map { (it as Map<*, *>)["id"] } shouldBe HEALTHY_POSTS.map { it.id.toInt() }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/**
 * `call-isolation` 델타의 "요청 시간 예산" 시나리오 (F-001).
 *
 * 두 시나리오의 GIVEN을 함께 준다: 예산 250ms, 의존성 다섯의 호출 시간 상한 200ms. "예산 안에 끝난다"에서 상한을 모두 200ms로
 * 두면 상한의 합(post 200ms + user·like 200ms)이 예산보다 커서, 피드를 끝내는 것이 예산인지 상한인지 가를 수 있다.
 * 첫 요청이 측정에 섞이지 않게 시나리오마다 지연 없이 한 번 먼저 부른다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.feed.time-budget=250ms",
        "resilience4j.timelimiter.instances.post.timeout-duration=200ms",
        "resilience4j.timelimiter.instances.user.timeout-duration=200ms",
        "resilience4j.timelimiter.instances.like.timeout-duration=200ms",
        "resilience4j.timelimiter.instances.recommendation.timeout-duration=200ms",
        "resilience4j.timelimiter.instances.ad.timeout-duration=200ms",
    ],
)
class FeedCallIsolationBudgetScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            FeedHttpClient(localPort()).getFeed()
        }

        test("F-001 F-002 의존성마다는 상한 안이지만 합치면 예산을 넘는다: post와 user가 150ms씩 늦으면 350ms 안에 200이 오고 user가 빠지며 user budget_exhausted 실패 로그가 있다") {
            wireMock.stubPosts(HEALTHY_POSTS, delayMs = 150)
            wireMock.stubUsers(listOf(3, 4), delayMs = 150)

            LogCapture().use { capture ->
                val (response, elapsed) = measureTimedValue { TracedFeedHttp(localPort()).getFeed() }

                response.statusCode() shouldBe 200
                elapsed shouldBeLessThan 350.milliseconds
                @Suppress("UNCHECKED_CAST")
                withClue("degradation.missing") {
                    ((response.json()["degradation"] as Map<String, Any?>)["missing"] as List<*>).contains("user") shouldBe
                        true
                }
                val traceId = response.traceId()
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 user의 budget_exhausted downstream_call_failed 줄") {
                        capture.failureLines("user", "budget_exhausted", traceId).shouldNotBeEmpty()
                    }
                }
            }
        }

        test("F-001 예산 안에 끝난다: 다섯 의존성이 상한보다 빠른 75ms씩 늦어 post와 user·like의 지연 합이 150ms면 200과 함께 모든 영역이 채워진다") {
            wireMock.stubAllHealthy(delayMs = 75)

            val response = FeedHttpClient(localPort()).getFeed()

            response.status shouldBe 200
            val body = response.json()
            val posts = body["posts"].shouldNotBeNull() as List<*>
            posts shouldHaveSize HEALTHY_POSTS.size
            posts.forEach { post ->
                withClue("게시글 $post 의 작성자") { (post as Map<*, *>)["author"].shouldNotBeNull() }
            }
            posts.map { ((it as Map<*, *>)["like"] as Map<*, *>)["count"] } shouldBe listOf(1, 0)
            body["recommendations"] shouldBe wireMock.recommendations
            body["ads"] shouldBe wireMock.ads
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/**
 * `call-isolation` 델타의 "의존성별 벌크헤드" 시나리오 (F-001).
 *
 * recommendation 벌크헤드는 동시 2(대기열 0), post·user·like는 동시 10(대기열 0)이다. recommendation 상한 2초, 예산 3초는
 * "한 의존성이 상한을 다 쓴다"의 GIVEN이고, "다른 의존성의 몫은 그대로다"도 같은 값에서 돈다.
 * 동시 요청은 요청마다 스레드 하나로 보낸다. recommendation 지연(1초, 무응답 3초)이 요청을 모두 보내는 시간보다 훨씬 길어
 * 요청들이 벌크헤드에서 겹친다. 상한이 2초라 첫 호출의 지연을 걱정할 필요가 없어 미리 부르지 않는다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.thread-pool-bulkhead.instances.recommendation.core-thread-pool-size=2",
        "resilience4j.thread-pool-bulkhead.instances.recommendation.max-thread-pool-size=2",
        "resilience4j.thread-pool-bulkhead.instances.recommendation.queue-capacity=0",
        "resilience4j.thread-pool-bulkhead.instances.post.core-thread-pool-size=10",
        "resilience4j.thread-pool-bulkhead.instances.post.max-thread-pool-size=10",
        "resilience4j.thread-pool-bulkhead.instances.post.queue-capacity=0",
        "resilience4j.thread-pool-bulkhead.instances.user.core-thread-pool-size=10",
        "resilience4j.thread-pool-bulkhead.instances.user.max-thread-pool-size=10",
        "resilience4j.thread-pool-bulkhead.instances.user.queue-capacity=0",
        "resilience4j.thread-pool-bulkhead.instances.like.core-thread-pool-size=10",
        "resilience4j.thread-pool-bulkhead.instances.like.max-thread-pool-size=10",
        "resilience4j.thread-pool-bulkhead.instances.like.queue-capacity=0",
        "resilience4j.timelimiter.instances.recommendation.timeout-duration=2s",
        "weave.feed.time-budget=3s",
    ],
)
class FeedCallIsolationBulkheadScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
        }

        test("F-001 한 의존성이 상한을 다 쓴다: recommendation 동시 상한이 2이고 1초 늦을 때 피드 5개를 동시에 보내면 recommendation은 2개만 받고 bulkhead_full 실패 로그가 3개다") {
            wireMock.stubRecommendations(delayMs = 1_000)
            val client = FeedHttpClient(localPort())

            LogCapture().use { capture ->
                concurrently(5) { client.getFeed() }

                wireMock.requestsTo("/recommendations") shouldHaveSize 2
                eventually(5.seconds) {
                    withClue("recommendation의 bulkhead_full downstream_call_failed 줄") {
                        capture.failureLines("recommendation", "bulkhead_full") shouldHaveSize 3
                    }
                }
            }
        }

        test("F-001 다른 의존성의 몫은 그대로다: recommendation 호출 2개가 응답 없이 묶인 동안 피드 5개를 동시에 보내면 post·user·like 호출은 모두 하위 서비스까지 가고 실패 로그가 없다") {
            wireMock.stubRecommendations(delayMs = UNRESPONSIVE_MS)
            val client = FeedHttpClient(localPort())

            LogCapture().use { capture ->
                Executors.newFixedThreadPool(2).use { pool ->
                    val stuck = (1..2).map { pool.submit(Callable { client.getFeed() }) }
                    eventually(2.seconds) {
                        withClue("묶인 recommendation 호출과 그 피드의 post·user·like 호출") {
                            wireMock.requestsTo("/recommendations") shouldHaveSize 2
                            listOf("/posts", "/users", "/likes").forEach { wireMock.requestsTo(it) shouldHaveSize 2 }
                        }
                    }

                    concurrently(5) { client.getFeed() }

                    listOf("/posts", "/users", "/likes").forEach { path ->
                        withClue("$path 가 받은 요청 (묶인 피드 2개 + 동시에 보낸 피드 5개)") { wireMock.requestsTo(path) shouldHaveSize 7 }
                    }
                    // 묶인 피드가 끝날 때까지 기다린다. 그사이 동시에 보낸 피드의 실패 로그도 모두 찍힌다.
                    stuck.forEach { it.get() }
                }

                listOf("post", "user", "like").forEach { dependency ->
                    withClue("$dependency 의 downstream_call_failed 줄") { capture.failureLines(dependency).shouldBeEmpty() }
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

/** 응답하지 않는 하위 서비스의 WireMock 지연. 테스트 기본값의 상한·응답 타임아웃(2초)보다 길다. */
private const val UNRESPONSIVE_MS = 3_000

private val HEALTHY_POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4))

private fun DownstreamWireMock.stubAllHealthy(delayMs: Int = 0) {
    stubPosts(HEALTHY_POSTS, delayMs)
    stubUsers(listOf(3, 4), delayMs)
    stubLikes(
        listOf(
            LikeState(postId = 10, likeCount = 1, liked = false),
            LikeState(postId = 11, likeCount = 0, liked = false),
        ),
        delayMs,
    )
    stubRecommendations(delayMs)
    stubAds(delayMs)
}

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()

/** [block]을 스레드 [count]개에서 동시에 돌리고 모두 끝날 때까지 기다린다. */
private fun <T> concurrently(
    count: Int,
    block: () -> T,
): List<T> =
    Executors.newFixedThreadPool(count).use { pool ->
        (1..count).map { pool.submit(Callable(block)) }.map { it.get() }
    }

/** [dependency]의 `downstream_call_failed` 줄. [cause], [traceId]를 주면 그 값인 줄만 남긴다. */
private fun LogCapture.failureLines(
    dependency: String,
    cause: String? = null,
    traceId: String? = null,
): List<Map<String, Any?>> =
    jsonLines().filter {
        it["event"] == "downstream_call_failed" &&
            it["dependency"] == dependency &&
            (cause == null || it["cause"] == cause) &&
            (traceId == null || it["traceId"] == traceId)
    }
