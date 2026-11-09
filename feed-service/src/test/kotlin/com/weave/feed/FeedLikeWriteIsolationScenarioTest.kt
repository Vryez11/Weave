package com.weave.feed

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import com.weave.observability.testing.LogCapture
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
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * `call-isolation` 델타의 "좋아요 상태 변경의 격리" 시나리오 (F-001, F-003).
 *
 * like 서킷은 최근 호출 10개 중 실패 50% 이상이면 열리고(창이 차기 전에는 열지 않는다), 열림 유지 시간은 10초다. 상태 변경은 조회와 같은
 * `like` 서킷을 거치므로(design 결정 6), 서킷은 like 조회가 503인 `GET /feed` 10번으로 연다. 느린 호출 판정(main 값은 상한의 80%)은
 * 다루지 않으므로 그 경계를 테스트 기본 상한(2초)으로 둬 빠지게 한다.
 * like 그룹의 응답 타임아웃 100ms는 "like-service가 응답하지 않는다"의 GIVEN이다. 상태 변경은 TimeLimiter를 쓰지 않아 이 값이 호출을 끊는다.
 * 첫 요청의 클래스 로딩과 연결 수립이 측정에 섞이지 않게 시나리오마다 피드와 상태 변경을 지연 없이 한 번씩 먼저 부르고 요청 기록을 비운다.
 *
 * 서킷은 컨텍스트의 [CircuitBreakerRegistry]에 남으므로 `beforeEach`에서 모두 `reset()`한다([FeedCircuitBreakerScenarioTest]와 같다).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.circuitbreaker.instances.like.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.like.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.like.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.like.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.like.slow-call-duration-threshold=2s",
        "resilience4j.circuitbreaker.instances.like.wait-duration-in-open-state=10s",
        "spring.http.serviceclient.like.read-timeout=100ms",
    ],
)
class FeedLikeWriteIsolationScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            wireMock.stubLikeState(postId = 5, liked = true, likeCount = 3)
            resetCircuits()
            val client = TracedFeedHttp(localPort())
            client.getFeed()
            client.putLike(5)
            resetCircuits()
            wireMock.server.resetRequests()
        }

        test(
            "F-001 F-003 like의 서킷이 열려 있다: like 서킷이 열린 뒤 PUT /feed/likes/5를 부르면 500이 오고 like-service는 상태 변경 요청을 받지 않으며 like circuit_open 실패 로그가 있다",
        ) {
            wireMock.stubFailure("like", 503)
            val client = TracedFeedHttp(localPort())
            repeat(WINDOW) { client.getFeed() }
            wireMock.requestsTo("/likes") shouldHaveSize WINDOW

            LogCapture().use { capture ->
                val response = client.putLike(5)

                response.statusCode() shouldBe 500
                withClue("서킷이 열린 뒤 like-service가 받은 상태 변경 요청") { wireMock.requestsTo("/likes/5").shouldBeEmpty() }
                val traceId = response.traceId()
                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 like의 circuit_open downstream_call_failed 줄") {
                        capture.failureLines("like", "circuit_open", traceId).shouldNotBeEmpty()
                    }
                }
            }
        }

        test("F-001 F-003 like-service가 응답하지 않는다: like 응답 타임아웃이 100ms이고 상태 변경에 2초 늦으면 400ms 안에 500이 오고 like-service에는 한 번만 요청한다") {
            wireMock.server.stubFor(
                put(urlPathEqualTo("/likes/5")).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)),
            )

            val (response, elapsed) = measureTimedValue { TracedFeedHttp(localPort()).putLike(5) }

            response.statusCode() shouldBe 500
            elapsed shouldBeLessThan 400.milliseconds
            wireMock.requestsTo("/likes/5") shouldHaveSize 1
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

private fun DownstreamWireMock.stubAllHealthy() {
    stubPosts(listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4)))
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

/** 앞 시나리오가 남긴 서킷 상태와 기록을 지운다. 레지스트리의 서킷을 모두 닫힘으로 되돌린다. */
private suspend fun resetCircuits() =
    testContextManager()
        .testContext.applicationContext
        .getBean(CircuitBreakerRegistry::class.java)
        .allCircuitBreakers
        .forEach { it.reset() }

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
