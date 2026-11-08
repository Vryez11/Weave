package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import com.weave.observability.testing.LogCapture
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * `call-isolation` 델타의 "서킷 브레이커의 실패 분류", "열린 서킷은 부르지 않는다", "서킷 상태 전이 로그" 시나리오 (F-001, F-004).
 *
 * recommendation과 ad 서킷은 최근 호출 10개 중 실패 50% 이상이면 열리고(창이 차기 전에는 열지 않는다), 열림 유지 시간은 10초다.
 * 느린 호출 판정(main 값은 상한의 80%)은 이 시나리오들이 다루지 않으므로, 그 경계를 테스트 기본 상한(2초)으로 둬 빠지게 한다.
 *
 * 서킷이 열렸는지는 스펙의 THEN대로 하위 서비스가 받은 요청 수와 `circuit_open` 실패 로그로 본다. 레지스트리의 상태는 읽지 않는다.
 *
 * **서킷 상태를 되돌리는 방법:** 서킷은 Spring 컨텍스트의 [CircuitBreakerRegistry]에 남아 다음 시나리오로 이어진다. 시나리오마다
 * 컨텍스트를 새로 띄우는 대신 `beforeEach`에서 레지스트리의 서킷을 모두 [CircuitBreaker.reset]한다(닫힘, 기록 비움).
 * 값이 다른 시나리오는 같은 파일의 다른 클래스로 컨텍스트를 나눈다: [FeedCircuitBreakerRecoveryScenarioTest](열림 유지 1초),
 * [FeedCircuitBreakerBulkheadScenarioTest](벌크헤드 1).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.circuitbreaker.instances.recommendation.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.recommendation.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.recommendation.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.recommendation.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.recommendation.slow-call-duration-threshold=2s",
        "resilience4j.circuitbreaker.instances.recommendation.wait-duration-in-open-state=10s",
        "resilience4j.circuitbreaker.instances.ad.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.ad.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.ad.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.ad.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.ad.slow-call-duration-threshold=2s",
        "resilience4j.circuitbreaker.instances.ad.wait-duration-in-open-state=10s",
    ],
)
class FeedCircuitBreakerScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-001 5xx가 이어진다: recommendation이 503을 줄 때 피드를 10번 부르면 recommendation 서킷이 열려 다음 피드는 recommendation을 부르지 않는다") {
            wireMock.stubFailure("recommendation", 503)
            val client = TracedFeedHttp(localPort())

            repeat(WINDOW) { client.getFeed() }
            wireMock.requestsTo("/recommendations") shouldHaveSize WINDOW

            LogCapture().use { capture ->
                val traceId = client.getFeed().traceId()

                withClue("서킷이 열린 뒤의 피드가 recommendation에 보낸 요청") { wireMock.requestsTo("/recommendations") shouldHaveSize WINDOW }
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 recommendation의 circuit_open downstream_call_failed 줄") {
                        capture.failureLines("recommendation", "circuit_open", traceId).shouldNotBeEmpty()
                    }
                }
            }
        }

        // ad의 Retry-After 대기(60초)는 컨텍스트에 남는다. 이 클래스의 뒤 시나리오는 ad를 보지 않는다(add-retry-policy design 결정 12)
        test(
            "F-001 F-005 4xx는 세지 않는다: ad가 429와 Retry-After 60, recommendation이 404를 줄 때 피드를 20번 부르면 두 서킷은 닫혀 있고 recommendation은 20개, ad는 1개를 받는다",
        ) {
            wireMock.stubFailure("ad", 429, retryAfterSeconds = 60)
            wireMock.stubFailure("recommendation", 404)
            val client = FeedHttpClient(localPort())

            LogCapture().use { capture ->
                repeat(20) { client.getFeed() }

                withClue("Retry-After 동안 부르지 않는 ad가 받은 요청") { wireMock.requestsTo("/ads") shouldHaveSize 1 }
                wireMock.requestsTo("/recommendations") shouldHaveSize 20
                listOf("ad", "recommendation").forEach { dependency ->
                    withClue("$dependency 의 circuit_open downstream_call_failed 줄") {
                        capture.failureLines(dependency, "circuit_open").shouldBeEmpty()
                    }
                }
            }
        }

        test("F-001 열린 뒤에는 부르지 않는다: recommendation 서킷이 열린 뒤 10초 안에 피드를 20번 부르면 recommendation은 요청을 받지 않고 모두 50ms 안에 circuit_open으로 끝난다") {
            wireMock.stubFailure("recommendation", 503)
            val client = FeedHttpClient(localPort())
            repeat(WINDOW) { client.getFeed() }
            wireMock.server.resetRequests()

            LogCapture().use { capture ->
                val responses = (1..20).map { client.getFeed() }

                withClue("서킷이 열린 동안 recommendation이 받은 요청") { wireMock.requestsTo("/recommendations").shouldBeEmpty() }
                responses.forEachIndexed { index, response ->
                    withClue("${index + 1}번째 피드의 응답 시간(ms)") { response.elapsedMs shouldBeLessThan 50 }
                }
                eventually(5.seconds) {
                    withClue("recommendation의 circuit_open downstream_call_failed 줄") {
                        capture.failureLines("recommendation", "circuit_open") shouldHaveSize 20
                    }
                }
            }
        }

        test(
            "F-001 F-004 서킷이 열린다: recommendation이 503을 줄 때 서킷이 열릴 때까지 피드를 부르면 CLOSED에서 OPEN으로의 circuit_state_changed 줄이 하나 있고 traceId는 서킷을 연 요청의 것이다",
        ) {
            wireMock.stubFailure("recommendation", 503)
            val client = TracedFeedHttp(localPort())

            LogCapture().use { capture ->
                // 최근 호출 10개가 모두 실패하는 열 번째 피드가 서킷을 연다.
                val traceIds = (1..WINDOW).map { client.getFeed().traceId() }

                eventually(5.seconds) {
                    val transitions =
                        capture.jsonLines().filter {
                            it["event"] == "circuit_state_changed" &&
                                it["dependency"] == "recommendation" &&
                                it["from"] == "CLOSED" &&
                                it["to"] == "OPEN"
                        }
                    withClue("recommendation의 CLOSED → OPEN circuit_state_changed 줄") { transitions shouldHaveSize 1 }
                    withClue("서킷을 연 피드 요청의 X-Trace-Id") { transitions.single()["traceId"] shouldBe traceIds.last() }
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
 * `call-isolation` 델타의 "복구하면 서킷이 닫힌다" 시나리오 (F-001).
 *
 * recommendation 서킷은 최근 호출 10개 중 실패 50% 이상이면 열리고, 열림 유지 1초 뒤 요청 없이 반열림이 되며, 반열림에서 시험 호출
 * 3개로 판단한다. 서킷을 여는 것은 503 피드 10번이다.
 *
 * 열림 유지 시간이 지나 저절로 반열림이 되는 것은 레지스트리의 상태를 `eventually`로 기다린다. 피드를 불러 확인하면 그 호출이
 * 시험 호출을 써 버려서다. 그 뒤의 THEN은 다른 클래스처럼 요청 수와 로그로 본다. 서킷은 `beforeEach`에서 되돌린다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.circuitbreaker.instances.recommendation.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.recommendation.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.recommendation.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.recommendation.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.recommendation.slow-call-duration-threshold=2s",
        "resilience4j.circuitbreaker.instances.recommendation.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.recommendation.permitted-number-of-calls-in-half-open-state=3",
        "resilience4j.circuitbreaker.instances.recommendation.automatic-transition-from-open-to-half-open-enabled=true",
    ],
)
class FeedCircuitBreakerRecoveryScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        /** 503 피드 10번으로 recommendation 서킷을 열고, 열림 유지 시간이 지나 반열림이 될 때까지 기다린다. */
        suspend fun openAndWaitForHalfOpen(client: FeedHttpClient) {
            wireMock.stubFailure("recommendation", 503)
            repeat(WINDOW) { client.getFeed() }
            eventually(5.seconds) {
                withClue("열림 유지 1초 뒤 recommendation 서킷의 상태") {
                    circuitBreakerRegistry().circuitBreaker("recommendation").state shouldBe CircuitBreaker.State.HALF_OPEN
                }
            }
        }

        test("F-001 복구된 의존성: recommendation 서킷이 열린 뒤 recommendation이 정상으로 돌아오면 1초 뒤부터 부른 피드로 서킷이 닫히고 이후 피드는 200과 함께 추천이 채워진다") {
            val client = FeedHttpClient(localPort())
            openAndWaitForHalfOpen(client)
            wireMock.stubRecommendations()

            eventually(5.seconds) {
                val response = client.getFeed()
                response.status shouldBe 200
                response.json()["recommendations"] shouldBe wireMock.recommendations
            }

            // 반열림이면 시험 호출 3개 뒤의 호출은 막힌다. 다섯 번 모두 recommendation까지 가면 서킷이 닫힌 것이다.
            wireMock.server.resetRequests()
            repeat(5) { attempt ->
                val response = client.getFeed()
                withClue("서킷이 닫힌 뒤 ${attempt + 1}번째 피드") {
                    response.status shouldBe 200
                    response.json()["recommendations"] shouldBe wireMock.recommendations
                }
            }
            wireMock.requestsTo("/recommendations") shouldHaveSize 5
        }

        test("F-001 아직 아프다: recommendation이 계속 503을 주면 1초 뒤부터 부른 피드 3번이 시험 호출 3개로 가고 서킷은 다시 열린다") {
            val client = TracedFeedHttp(localPort())
            openAndWaitForHalfOpen(FeedHttpClient(localPort()))
            wireMock.server.resetRequests()

            repeat(3) { client.getFeed() }
            withClue("반열림에서 recommendation이 받은 시험 호출") { wireMock.requestsTo("/recommendations") shouldHaveSize 3 }

            LogCapture().use { capture ->
                val traceId = client.getFeed().traceId()

                withClue("서킷이 다시 열린 뒤의 피드가 recommendation에 보낸 요청") {
                    wireMock.requestsTo("/recommendations") shouldHaveSize 3
                }
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 recommendation의 circuit_open downstream_call_failed 줄") {
                        capture.failureLines("recommendation", "circuit_open", traceId).shouldNotBeEmpty()
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
 * `call-isolation` 델타의 "벌크헤드 거절은 세지 않는다" 시나리오 (F-001).
 *
 * recommendation 서킷은 최근 호출 10개 중 실패 50% 이상이면 열리고, 벌크헤드는 동시 1(대기열 0), 호출 시간 상한 1초, 시간 예산 2초다.
 * 느린 호출 경계는 상한(1초)으로 둬 300ms 응답이 느린 호출로 세지 않게 한다. post·user·like·ad 벌크헤드는 동시 10이라 피드 10개에서도
 * recommendation 말고는 거절되지 않는다.
 *
 * 피드 10개를 동시에 보내면 recommendation 벌크헤드에 하나만 들어가고 나머지는 `bulkhead_full`로 끝난다. 거절을 실패로 셌다면
 * 기록된 호출이 10개를 채워 서킷이 열린다. 닫혀 있는지는 그 뒤 피드 하나가 recommendation까지 가는지와 `circuit_open` 로그로 본다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.circuitbreaker.instances.recommendation.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.recommendation.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.recommendation.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.recommendation.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.recommendation.slow-call-duration-threshold=1s",
        "resilience4j.thread-pool-bulkhead.instances.recommendation.core-thread-pool-size=1",
        "resilience4j.thread-pool-bulkhead.instances.recommendation.max-thread-pool-size=1",
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
        "resilience4j.thread-pool-bulkhead.instances.ad.core-thread-pool-size=10",
        "resilience4j.thread-pool-bulkhead.instances.ad.max-thread-pool-size=10",
        "resilience4j.thread-pool-bulkhead.instances.ad.queue-capacity=0",
        "resilience4j.timelimiter.instances.recommendation.timeout-duration=1s",
        "weave.feed.time-budget=2s",
    ],
)
class FeedCircuitBreakerBulkheadScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-001 벌크헤드 거절은 세지 않는다: recommendation 동시 상한이 1이고 300ms 걸릴 때 피드 10개를 동시에 보내면 bulkhead_full 실패가 있지만 recommendation 서킷은 닫혀 있다") {
            wireMock.stubRecommendations(delayMs = 300)
            val client = FeedHttpClient(localPort())

            LogCapture().use { capture ->
                concurrently(WINDOW) { client.getFeed() }

                eventually(5.seconds) {
                    withClue("recommendation의 bulkhead_full downstream_call_failed 줄") {
                        capture.failureLines("recommendation", "bulkhead_full").shouldNotBeEmpty()
                    }
                }

                wireMock.server.resetRequests()
                client.getFeed()
                withClue("동시 피드 뒤의 피드가 recommendation에 보낸 요청") { wireMock.requestsTo("/recommendations") shouldHaveSize 1 }
                withClue("recommendation의 circuit_open downstream_call_failed 줄") {
                    capture.failureLines("recommendation", "circuit_open").shouldBeEmpty()
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

/** 시나리오 서킷의 최근 호출 수(창 크기)이자 서킷을 여는 데 드는 503 피드 수. */
private const val WINDOW = 10

private val HEALTHY_POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4))

private fun DownstreamWireMock.stubAllHealthy() {
    stubPosts(HEALTHY_POSTS)
    stubUsers(listOf(3, 4))
    stubLikes(
        listOf(
            LikeState(postId = 10, likeCount = 1, liked = false),
            LikeState(postId = 11, likeCount = 0, liked = false),
        ),
    )
    stubRecommendations()
    stubAds()
}

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()

private suspend fun circuitBreakerRegistry(): CircuitBreakerRegistry =
    testContextManager().testContext.applicationContext.getBean(CircuitBreakerRegistry::class.java)

/** 앞 시나리오가 남긴 서킷 상태와 기록을 지운다. 레지스트리의 서킷을 모두 닫힘으로 되돌린다. */
private suspend fun resetCircuits() = circuitBreakerRegistry().allCircuitBreakers.forEach { it.reset() }

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
