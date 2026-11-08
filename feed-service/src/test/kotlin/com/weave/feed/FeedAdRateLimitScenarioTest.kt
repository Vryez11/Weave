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
import io.kotest.matchers.shouldBe
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds

/**
 * `retry-policy` 스펙 "ad 계약 쿼터를 넘기 전에 멈춘다"의 "상한을 넘는다" (F-005).
 *
 * ad의 요청 수 상한은 10초에 3개이고 기다리지 않는다(`resilience4j.ratelimiter.instances.ad.*`, add-retry-policy design 결정 6).
 * 상한으로 막은 호출이 서킷에 실패로 세이면 열리도록 ad 서킷을 최근 호출 2개 중 실패 50% 이상이면 열리게 둔다.
 *
 * RateLimiter의 주기와 남은 허용 수는 컨텍스트에 남으므로 이 클래스는 시나리오 하나만 두고 정상 피드로 미리 데우지 않는다(데우는 피드도
 * 허용 하나를 쓴다). Resilience4j RateLimiter의 주기는 인스턴스를 만든 시각부터 세므로, 컨텍스트가 뜬 직후 0.5초 남짓 걸리는 피드 다섯은
 * 첫 주기(10초) 안에 든다. 주기가 다른 "다음 주기"는 [FeedAdRateLimitNextPeriodScenarioTest]다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.ratelimiter.instances.ad.limit-for-period=3",
        "resilience4j.ratelimiter.instances.ad.limit-refresh-period=10s",
        "resilience4j.ratelimiter.instances.ad.timeout-duration=0s",
        "resilience4j.circuitbreaker.instances.ad.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.ad.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.ad.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.ad.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.ad.slow-call-duration-threshold=2s",
        "resilience4j.circuitbreaker.instances.ad.wait-duration-in-open-state=10s",
    ],
)
class FeedAdRateLimitScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test(
            "F-005 상한을 넘는다: ad 상한이 10초에 3개이고 ad-service가 정상일 때 피드를 다섯 번 부르면 ad는 세 개를 받고 네·다섯 번째는 missing이 ad 하나, rate_limited 실패 로그가 두 줄, ad 서킷은 닫혀 있다",
        ) {
            val client = TracedFeedHttp(localPort())

            LogCapture().use { capture ->
                val responses = (1..5).map { client.getFeed() }

                withClue("ad-service가 받은 요청") { wireMock.requestsTo("/ads") shouldHaveSize 3 }
                responses.take(3).forEachIndexed { index, response ->
                    withClue("${index + 1}번째 피드의 missing") { response.json().missing().shouldBeEmpty() }
                }
                responses.drop(3).forEachIndexed { index, response ->
                    withClue("${index + 4}번째 피드") {
                        response.statusCode() shouldBe 200
                        response.json().missing() shouldBe listOf("ad")
                    }
                }
                capture.awaitRequestLine(responses.last().traceId())
                withClue("ad의 rate_limited downstream_call_failed 줄") { capture.failureLines("ad", "rate_limited") shouldHaveSize 2 }
                withClue("ad 서킷 상태") { circuitBreakerRegistry().circuitBreaker("ad").state shouldBe CircuitBreaker.State.CLOSED }
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
 * `retry-policy` 스펙 "ad 계약 쿼터를 넘기 전에 멈춘다"의 "다음 주기" (F-005).
 *
 * ad의 요청 수 상한은 1초에 1개다. 주기 경계가 첫 피드와 둘째 피드 사이에 걸리면 둘 다 허용되므로, 시나리오 전에 주기 하나를 맞춘다:
 * 피드를 한 번 불러 허용을 쓰고, Resilience4j가 내보내는 남은 허용 수(`resilience4j_ratelimiter_available_permissions{name="ad"}`,
 * `/actuator/prometheus`를 긁을 때 지금 시각으로 계산한다)가 다시 1이 될 때까지 기다린다. 그 순간이 새 주기의 시작이라, 곧바로 부르는
 * 두 피드는 같은 주기에 든다. 맞추는 피드의 요청은 기록에서 지운다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.ratelimiter.instances.ad.limit-for-period=1",
        "resilience4j.ratelimiter.instances.ad.limit-refresh-period=1s",
        "resilience4j.ratelimiter.instances.ad.timeout-duration=0s",
    ],
)
class FeedAdRateLimitNextPeriodScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-005 다음 주기: ad 상한이 1초에 1개일 때 피드를 두 번 부르고 1.5초 뒤 한 번 더 부르면 ad는 두 개를 받고 세 번째 피드에는 광고가 채워져 있다") {
            val port = localPort()
            val client = FeedHttpClient(port)
            client.getFeed()
            eventually(3.seconds) {
                withClue("ad의 남은 허용 수") { availablePermissions(port, "ad") shouldBe 1.0 }
            }
            wireMock.server.resetRequests()

            client.getFeed()
            client.getFeed()
            Thread.sleep(NEXT_PERIOD_MS)
            val third = client.getFeed()

            withClue("ad-service가 받은 요청") { wireMock.requestsTo("/ads") shouldHaveSize 2 }
            third.status shouldBe 200
            withClue("세 번째 피드의 ads") { third.json()["ads"] shouldBe wireMock.ads }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/** 스펙의 "1.5초 뒤"다. */
private const val NEXT_PERIOD_MS = 1_500L

private val HEALTHY_POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4))

private val HEALTHY_LIKE_STATES =
    listOf(
        LikeState(postId = 10, likeCount = 1, liked = false),
        LikeState(postId = 11, likeCount = 0, liked = false),
    )

private fun DownstreamWireMock.stubAllHealthy() {
    stubPosts(HEALTHY_POSTS)
    stubUsers(listOf(3, 4))
    stubLikes(HEALTHY_LIKE_STATES)
    stubRecommendations()
    stubAds()
}

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

/** traceId가 [traceId]인 `http_request` 줄(요청을 마친 표지)이 잡힐 때까지 기다린다. 그 요청의 실패 로그는 이보다 먼저 남는다. */
private suspend fun LogCapture.awaitRequestLine(traceId: String) =
    eventually(5.seconds) {
        withClue("traceId가 $traceId 인 http_request 줄") {
            jsonLines().filter { it["event"] == "http_request" && it["traceId"] == traceId }.shouldNotBeEmpty()
        }
    }

/** [dependency]의 `cause`가 [cause]인 `downstream_call_failed` 줄. */
private fun LogCapture.failureLines(
    dependency: String,
    cause: String,
): List<Map<String, Any?>> =
    jsonLines().filter { it["event"] == "downstream_call_failed" && it["dependency"] == dependency && it["cause"] == cause }

private val prometheusClient: HttpClient = HttpClient.newHttpClient()

/** `/actuator/prometheus`의 `resilience4j_ratelimiter_available_permissions{name}` 값. 표본이 없으면 null이다. */
private fun availablePermissions(
    port: Int,
    name: String,
): Double? {
    val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build()
    val response = prometheusClient.send(request, HttpResponse.BodyHandlers.ofString())
    response.statusCode() shouldBe 200
    return response
        .body()
        .lineSequence()
        .filter { it.startsWith("resilience4j_ratelimiter_available_permissions{") }
        .firstOrNull { """name="$name"""" in it }
        ?.substringAfterLast('}')
        ?.trim()
        ?.split(' ')
        ?.first()
        ?.toDouble()
}
