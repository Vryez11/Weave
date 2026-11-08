package com.weave.feed

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * `retry-policy` 스펙 "Retry-After 동안 부르지 않는다"의 "초로 준다", "대기가 끝난다", "HTTP 날짜로 준다", "머리글이 없다" (F-005).
 *
 * 기본 대기 1초, 최대 대기 60초다(`weave.retry-after.default-hold`, `max-hold`, add-retry-policy design 결정 4). 대기 중인 ad 호출은
 * 서킷에 닿지 않아야 하므로, ad 서킷을 최근 호출 2개 중 실패 50% 이상이면 열리게 둬서 막은 호출이 실패로 세이면 서킷이 열리게 한다.
 * 429는 4xx라 서킷에 세지 않는다(`call-isolation`). 느린 호출 경계는 테스트 기본 상한(2초)으로 둔다.
 *
 * **대기는 컨텍스트에 남는다**(design 결정 12). 대기를 지우는 테스트 전용 API는 없으므로, 시나리오가 끝나면 ad를 정상 스텁으로 되돌리고
 * 피드에 광고가 채워질 때까지(대기가 풀릴 때까지) 기다린다([awaitAdsRestored]). 시나리오 앞에서도 같은 방법으로 정상 피드를 한 번 받아
 * 첫 호출의 클래스 로딩과 연결 수립을 치운 뒤 요청 기록을 비운다. 서킷은 `beforeEach`에서 모두 되돌린다.
 *
 * 요청 수는 WireMock이 받은 요청으로 본다. "N초 안에"는 그 시간 안에 부른 것을 확인한 뒤 단언한다(대기가 끝난 뒤의 호출은 ad에 간다).
 * 로그는 마지막 피드의 `http_request` 줄(요청을 마친 표지)을 기다린 뒤 본다. 최대 대기 시간이 다른 "최대 대기 시간보다 길다"는
 * [FeedRetryAfterMaxHoldScenarioTest]다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.retry-after.default-hold=1s",
        "weave.retry-after.max-hold=60s",
        "resilience4j.circuitbreaker.instances.ad.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.ad.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.ad.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.ad.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.ad.slow-call-duration-threshold=2s",
        "resilience4j.circuitbreaker.instances.ad.wait-duration-in-open-state=10s",
    ],
)
class FeedRetryAfterScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock
        var port = 0

        beforeEach {
            port = localPort()
            resetCircuits()
            wireMock.awaitAdsRestored(port)
            resetCircuits()
            wireMock.server.resetRequests()
        }

        afterEach { wireMock.awaitAdsRestored(port) }

        test(
            "F-005 초로 준다: ad-service가 429와 Retry-After 2를 주면 피드를 한 번, 2초 안에 다섯 번 더 불러도 모두 200이고 missing은 ad 하나, ad는 한 번만 받고 retry_after 실패 로그가 다섯 줄, ad 서킷은 닫혀 있다",
        ) {
            wireMock.stubFailure("ad", 429, retryAfterSeconds = 2)
            val client = TracedFeedHttp(port)

            LogCapture().use { capture ->
                val started = System.nanoTime()
                val responses = (1..6).map { client.getFeed() }
                withClue("여섯 번을 부른 시간(ms), Retry-After 2초 안이어야 한다") { elapsedMs(started) shouldBeLessThan 2_000 }

                responses.forEachIndexed { index, response ->
                    withClue("${index + 1}번째 피드") {
                        response.statusCode() shouldBe 200
                        response.json().missing() shouldBe listOf("ad")
                    }
                }
                withClue("ad-service가 받은 요청") { wireMock.requestsTo("/ads") shouldHaveSize 1 }
                capture.awaitRequestLine(responses.last().traceId())
                withClue("ad의 retry_after downstream_call_failed 줄") { capture.failureLines("ad", "retry_after") shouldHaveSize 5 }
                withClue("ad 서킷 상태") { circuitBreakerRegistry().circuitBreaker("ad").state shouldBe CircuitBreaker.State.CLOSED }
            }
        }

        test("F-005 대기가 끝난다: ad-service가 첫 요청에 429와 Retry-After 1을 주고 그 뒤 정상이면 1.5초 뒤의 피드에는 광고가 채워지고 ad는 두 번 받는다") {
            wireMock.stubAdsThrottledOnce(retryAfter = "1")
            val client = FeedHttpClient(port)

            withClue("첫 피드의 missing") { client.getFeed().json().missing() shouldBe listOf("ad") }
            Thread.sleep(AFTER_HOLD_MS)
            val second = client.getFeed()

            second.status shouldBe 200
            withClue("두 번째 피드의 ads") { second.json()["ads"] shouldBe wireMock.ads }
            withClue("ad-service가 받은 요청") { wireMock.requestsTo("/ads") shouldHaveSize 2 }
        }

        test("F-005 HTTP 날짜로 준다: ad-service가 429와 3초 뒤의 HTTP 날짜를 Retry-After로 주면 피드를 한 번, 1초 안에 다섯 번 더 불러도 ad는 한 번만 받는다") {
            val retryAfter = HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(3))
            wireMock.server.stubFor(
                get(urlPathEqualTo("/ads")).willReturn(aResponse().withStatus(429).withHeader("Retry-After", retryAfter)),
            )
            val client = FeedHttpClient(port)

            val started = System.nanoTime()
            repeat(6) { index -> withClue("${index + 1}번째 피드") { client.getFeed().status shouldBe 200 } }
            withClue("여섯 번을 부른 시간(ms)") { elapsedMs(started) shouldBeLessThan 1_000 }

            withClue("ad-service가 받은 요청 (Retry-After: $retryAfter)") { wireMock.requestsTo("/ads") shouldHaveSize 1 }
        }

        test("F-005 머리글이 없다: 기본 대기 1초이고 ad-service가 Retry-After 없이 429를 주면 한 번, 0.5초 안에 세 번 더, 1.5초 뒤 한 번 부를 때 ad는 처음과 마지막 두 번 받는다") {
            wireMock.stubFailure("ad", 429)
            val client = FeedHttpClient(port)

            val started = System.nanoTime()
            repeat(4) { index -> withClue("${index + 1}번째 피드") { client.getFeed().status shouldBe 200 } }
            withClue("네 번을 부른 시간(ms)") { elapsedMs(started) shouldBeLessThan 500 }
            Thread.sleep(AFTER_HOLD_MS)
            withClue("1.5초 뒤의 피드") { client.getFeed().status shouldBe 200 }

            withClue("ad-service가 받은 요청") { wireMock.requestsTo("/ads") shouldHaveSize 2 }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/**
 * `retry-policy` 스펙 "Retry-After 동안 부르지 않는다"의 "최대 대기 시간보다 길다" (F-005).
 *
 * 최대 대기 1초다. `Retry-After: 3600`을 받아도 1초까지만 부르지 않으므로 1.5초 뒤의 피드는 ad를 다시 부른다. 기다리기 전과 끝난 뒤의
 * 정리는 [FeedRetryAfterScenarioTest]와 같다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.retry-after.default-hold=1s",
        "weave.retry-after.max-hold=1s",
    ],
)
class FeedRetryAfterMaxHoldScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock
        var port = 0

        beforeEach {
            port = localPort()
            resetCircuits()
            wireMock.awaitAdsRestored(port)
            resetCircuits()
            wireMock.server.resetRequests()
        }

        afterEach { wireMock.awaitAdsRestored(port) }

        test("F-005 최대 대기 시간보다 길다: 최대 대기 1초이고 ad-service가 첫 요청에 429와 Retry-After 3600을 주고 그 뒤 정상이면 1.5초 뒤의 피드에는 광고가 채워져 있다") {
            wireMock.stubAdsThrottledOnce(retryAfter = "3600")
            val client = FeedHttpClient(port)

            withClue("첫 피드의 missing") { client.getFeed().json().missing() shouldBe listOf("ad") }
            Thread.sleep(AFTER_HOLD_MS)
            val second = client.getFeed()

            second.status shouldBe 200
            withClue("두 번째 피드의 ads") { second.json()["ads"] shouldBe wireMock.ads }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/** 대기 1초가 끝난 뒤 다시 부르기까지 기다리는 시간. 스펙의 "1.5초 뒤"다. */
private const val AFTER_HOLD_MS = 1_500L

/** `Retry-After`의 HTTP 날짜(IMF-fixdate, RFC 9110 5.6.7). 예) `Sun, 08 Nov 2026 12:00:03 GMT` */
private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

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

/**
 * 스텁을 모두 정상으로 되돌리고, 피드에 광고가 채워질 때까지 기다린다. 앞 시나리오가 남긴 Retry-After 대기가 풀렸다는 뜻이다.
 * 이 클래스들의 대기는 길어야 3초(HTTP 날짜)다.
 */
private suspend fun DownstreamWireMock.awaitAdsRestored(port: Int) {
    reset()
    stubAllHealthy()
    val client = FeedHttpClient(port)
    eventually(10.seconds) {
        withClue("Retry-After 대기가 풀린 뒤 피드의 ads") { client.getFeed().json()["ads"] shouldBe ads }
    }
}

/** 첫 광고 조회에만 429와 `Retry-After: [retryAfter]`를 주고 그 뒤에는 정상 광고로 응답한다(WireMock 시나리오 상태). */
private fun DownstreamWireMock.stubAdsThrottledOnce(retryAfter: String) {
    server.stubFor(
        get(urlPathEqualTo("/ads"))
            .inScenario(AD_THROTTLED_ONCE)
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", retryAfter))
            .willSetStateTo(AD_RECOVERED),
    )
    server.stubFor(
        get(urlPathEqualTo("/ads"))
            .inScenario(AD_THROTTLED_ONCE)
            .whenScenarioStateIs(AD_RECOVERED)
            .willReturn(okJson(bodyMapper.writeValueAsString(mapOf("ads" to ads)))),
    )
}

private const val AD_THROTTLED_ONCE = "ad-throttled-once"
private const val AD_RECOVERED = "recovered"

private val bodyMapper = jacksonObjectMapper()

private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

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
