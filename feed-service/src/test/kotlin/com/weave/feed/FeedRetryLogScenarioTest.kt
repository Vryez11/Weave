package com.weave.feed

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.time.Duration.Companion.seconds

/**
 * `retry-policy` 스펙 "재시도와 대기 이벤트 로그"의 "다시 부른다", "Retry-After 대기를 시작한다" (F-005, F-004).
 *
 * like 최대 시도 횟수 3, 간격 10ms다. 재시도 예산은 컨텍스트에 남으므로 이 클래스의 시나리오가 닿지 않게 넉넉히 준다. Retry-After는 기본 대기
 * 1초, 최대 대기 60초다(`weave.retry-after.*`, add-retry-policy design 결정 4).
 *
 * 로그 줄은 응답의 `X-Trace-Id`와 같은 `traceId`로 찾고, 하위 서비스가 받은 `traceparent`는 WireMock 요청 기록에서 읽는다. 로그는 그 피드의
 * `http_request` 줄(요청을 마친 표지, 재시도·대기 로그보다 늦게 남는다)을 기다린 뒤 본다. 숫자 필드(`attempt`, `waitMs`, `holdMs`)는 JSON 숫자로
 * 읽든 문자열로 읽든 같게 비교한다([longField]).
 *
 * **Retry-After 대기는 컨텍스트에 남는다**(design 결정 12). 시나리오 앞뒤에 ad를 정상 스텁으로 되돌리고 피드에 광고가 채워질 때까지(대기가 풀릴
 * 때까지) 기다린다([awaitAdsRestored]). 앞의 기다림은 첫 피드의 클래스 로딩과 연결 수립도 치운다. 그 뒤 서킷과 요청 기록을 비운다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.max-attempts=3",
        "resilience4j.retry.instances.like.wait-duration=10ms",
        "weave.retry.budget.ratio=1",
        "weave.retry.budget.min-retries=1000",
        "weave.retry.budget.window=10s",
        "weave.retry-after.default-hold=1s",
        "weave.retry-after.max-hold=60s",
    ],
)
class FeedRetryLogScenarioTest :
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
            "F-005 F-004 다시 부른다: like 최대 시도 횟수 3이고 like-service가 첫 좋아요 조회에 503을 주면 like, attempt 2, cause http_503인 downstream_call_retried 줄이 하나이고 그 줄과 like-service가 받은 두 요청의 traceId가 X-Trace-Id와 같다",
        ) {
            wireMock.stubLikesFailingOnce()
            val client = TracedFeedHttp(port)

            LogCapture().use { capture ->
                val response = client.getFeed()

                response.statusCode() shouldBe 200
                val traceId = response.traceId()
                capture.awaitRequestLine(traceId)
                val line =
                    withClue("like의 downstream_call_retried 줄") {
                        capture.eventLines("downstream_call_retried", "like").shouldHaveSize(1).single()
                    }
                withClue("downstream_call_retried 줄의 attempt") { longField(line, "attempt") shouldBe 2L }
                withClue("downstream_call_retried 줄의 cause") { line["cause"] shouldBe "http_503" }
                withClue("downstream_call_retried 줄의 waitMs") { longField(line, "waitMs") shouldBeGreaterThanOrEqual 0L }
                withClue("downstream_call_retried 줄의 traceId") { line["traceId"] shouldBe traceId }

                val requests = withClue("like-service가 받은 좋아요 조회") { wireMock.requestsTo("/likes").shouldHaveSize(2) }
                requests.forEachIndexed { index, request ->
                    withClue("like-service가 받은 ${index + 1}번째 요청의 traceparent traceId") { request.traceparentTraceId() shouldBe traceId }
                }
            }
        }

        test(
            "F-005 F-004 Retry-After 대기를 시작한다: ad-service가 429와 Retry-After 2를 주고 피드를 두 번 부르면 ad, holdMs 2000인 retry_after_hold_started 줄이 하나이고 traceId는 첫 피드의 X-Trace-Id다",
        ) {
            wireMock.stubFailure("ad", 429, retryAfterSeconds = 2)
            val client = TracedFeedHttp(port)

            LogCapture().use { capture ->
                val first = client.getFeed()
                val second = client.getFeed()

                withClue("첫 피드") { first.statusCode() shouldBe 200 }
                withClue("둘째 피드") { second.statusCode() shouldBe 200 }
                capture.awaitRequestLine(second.traceId())
                val line =
                    withClue("ad의 retry_after_hold_started 줄") {
                        capture.eventLines("retry_after_hold_started", "ad").shouldHaveSize(1).single()
                    }
                withClue("retry_after_hold_started 줄의 holdMs") { longField(line, "holdMs") shouldBe 2_000L }
                withClue("retry_after_hold_started 줄의 holdUntil") { line["holdUntil"].shouldNotBeNull() }
                withClue("retry_after_hold_started 줄의 traceId") { line["traceId"] shouldBe first.traceId() }
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

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
 * 이 클래스의 대기는 길어야 2초다.
 */
private suspend fun DownstreamWireMock.awaitAdsRestored(port: Int) {
    reset()
    stubAllHealthy()
    val client = FeedHttpClient(port)
    eventually(10.seconds) {
        withClue("Retry-After 대기가 풀린 뒤 피드의 ads") { client.getFeed().json()["ads"] shouldBe ads }
    }
}

/** 첫 좋아요 조회에만 503을 주고 그 뒤에는 [HEALTHY_LIKE_STATES]로 응답한다(WireMock 시나리오 상태). 앞서 둔 정상 스텁보다 이긴다. */
private fun DownstreamWireMock.stubLikesFailingOnce() {
    val body =
        HEALTHY_LIKE_STATES.joinToString(",", prefix = """{"likes":[""", postfix = "]}") {
            """{"postId":${it.postId},"likeCount":${it.likeCount},"liked":${it.liked}}"""
        }
    server.stubFor(
        get(urlPathEqualTo("/likes"))
            .inScenario(LIKE_FAILS_ONCE)
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo(LIKE_RECOVERED),
    )
    server.stubFor(
        get(urlPathEqualTo("/likes"))
            .inScenario(LIKE_FAILS_ONCE)
            .whenScenarioStateIs(LIKE_RECOVERED)
            .willReturn(okJson(body)),
    )
}

private const val LIKE_FAILS_ONCE = "like-fails-once"
private const val LIKE_RECOVERED = "recovered"

/** 로그 줄의 숫자 필드. JSON 숫자든 숫자 문자열이든 정수로 읽는다. 없거나 숫자가 아니면 테스트를 실패시킨다. */
private fun longField(
    line: Map<String, Any?>,
    name: String,
): Long =
    withClue("$name 필드: ${line[name]}") {
        when (val value = line[name]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }.shouldNotBeNull()
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

/** traceId가 [traceId]인 `http_request` 줄(요청을 마친 표지)이 잡힐 때까지 기다린다. 그 요청의 재시도·대기 로그는 이보다 먼저 남는다. */
private suspend fun LogCapture.awaitRequestLine(traceId: String) =
    eventually(5.seconds) {
        withClue("traceId가 $traceId 인 http_request 줄") {
            jsonLines().filter { it["event"] == "http_request" && it["traceId"] == traceId }.shouldNotBeEmpty()
        }
    }

/** `event`가 [event]이고 `dependency`가 [dependency]인 줄. */
private fun LogCapture.eventLines(
    event: String,
    dependency: String,
): List<Map<String, Any?>> = jsonLines().filter { it["event"] == event && it["dependency"] == dependency }
