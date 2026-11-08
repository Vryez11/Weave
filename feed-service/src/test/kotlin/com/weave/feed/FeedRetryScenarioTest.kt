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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * `retry-policy` 스펙의 "멱등한 조회만 재시도한다", "재시도하는 실패" 시나리오 (F-005).
 *
 * 테스트 기본 설정(`config/application.yml`)은 재시도를 끈다(add-retry-policy design 결정 12). 이 클래스는 like와 recommendation의
 * 최대 시도 횟수를 3, 간격을 10ms로 켠다. recommendation의 호출 시간 상한 200ms는 "시간 상한 초과는 다시 부르지 않는다"의 GIVEN이다.
 * 재시도 예산(결정 5)은 컨텍스트에 남으므로 이 클래스의 시나리오가 닿지 않게 넉넉히 준다. 예산은 [FeedRetryBudgetScenarioTest]가 본다.
 *
 * 요청 수는 WireMock이 받은 요청으로 본다. 피드 응답은 모든 시도가 끝난 뒤에 오므로(시간 예산 5초 안) 응답 뒤에 세면 된다.
 * 로그가 "없다"는 단언은 그 피드의 `http_request` 줄(요청을 마친 표지, 실패·재시도 로그보다 늦게 남는다)을 기다린 뒤 본다.
 * 첫 요청의 클래스 로딩과 연결 수립이 상한 200ms에 걸리지 않게 시나리오마다 정상 피드를 한 번 먼저 부르고 요청 기록을 비운다.
 * 서킷은 `beforeEach`에서 모두 되돌린다([FeedCircuitBreakerScenarioTest]와 같다).
 *
 * GIVEN이 다른 시나리오는 같은 파일의 다른 클래스로 컨텍스트를 나눈다: [FeedRetryConnectFailureScenarioTest](recommendation 주소가 닫힌
 * 포트), [FeedRetryCircuitOpensScenarioTest](like 서킷 창 2), [FeedRetryIntervalScenarioTest](간격 100ms, 배수, jitter),
 * [FeedRetryDeadlineScenarioTest](요청 시간 예산 250ms).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.max-attempts=3",
        "resilience4j.retry.instances.like.wait-duration=10ms",
        "resilience4j.retry.instances.recommendation.max-attempts=3",
        "resilience4j.retry.instances.recommendation.wait-duration=10ms",
        // ad는 재시도 인스턴스가 있어도 다시 부르지 않는다(IsolatedCalls.NEVER_RETRIED). 설정만으로 막지 않는 것을 "광고는 다시 부르지 않는다"가 본다
        "resilience4j.retry.instances.ad.max-attempts=3",
        "resilience4j.retry.instances.ad.wait-duration=10ms",
        "resilience4j.timelimiter.instances.recommendation.timeout-duration=200ms",
        "weave.retry.budget.ratio=1",
        "weave.retry.budget.min-retries=1000",
        "weave.retry.budget.window=10s",
    ],
)
class FeedRetryScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
            FeedHttpClient(localPort()).getFeed()
            resetCircuits()
            wireMock.server.resetRequests()
        }

        context("멱등한 조회만 재시도한다") {
            test("F-005 한 번 실패한 뒤 성공한다: like-service가 첫 좋아요 조회에 503을 주고 그 뒤 정상이면 200, 좋아요가 채워지고 like-service는 두 번 받으며 like 실패 로그가 없다") {
                wireMock.stubLikesFailingOnce()
                val client = TracedFeedHttp(localPort())

                LogCapture().use { capture ->
                    val response = client.getFeed()

                    response.statusCode() shouldBe 200
                    val body = response.json()
                    withClue("degradation.missing") { body.missing().shouldBeEmpty() }
                    withClue("게시글의 like") { body.posts().map { it["like"] } shouldBe HEALTHY_LIKES }
                    withClue("like-service가 받은 좋아요 조회") { wireMock.requestsTo("/likes") shouldHaveSize 2 }
                    capture.awaitRequestLine(response.traceId())
                    withClue("like의 downstream_call_failed 줄") { capture.failureLines("like").shouldBeEmpty() }
                }
            }

            test("F-005 계속 실패한다: like-service가 늘 503을 주면 200이고 missing은 like 하나, like-service는 세 번 받고 like 실패 로그는 한 줄이다") {
                wireMock.stubFailure("like", 503)
                val client = TracedFeedHttp(localPort())

                LogCapture().use { capture ->
                    val response = client.getFeed()

                    response.statusCode() shouldBe 200
                    withClue("degradation.missing") { response.json().missing() shouldBe listOf("like") }
                    withClue("like-service가 받은 좋아요 조회") { wireMock.requestsTo("/likes") shouldHaveSize 3 }
                    capture.awaitRequestLine(response.traceId())
                    withClue("like의 downstream_call_failed 줄") { capture.failureLines("like") shouldHaveSize 1 }
                }
            }

            test("F-005 광고는 다시 부르지 않는다: ad 재시도 인스턴스를 최대 3번으로 둬도 ad-service가 503을 주면 200이고 missing은 ad 하나, ad-service는 요청을 한 번만 받는다") {
                wireMock.stubFailure("ad", 503)

                val response = FeedHttpClient(localPort()).getFeed()

                response.status shouldBe 200
                withClue("degradation.missing") { response.json().missing() shouldBe listOf("ad") }
                withClue("ad-service가 받은 요청") { wireMock.requestsTo("/ads") shouldHaveSize 1 }
            }

            test("F-005 좋아요 토글은 다시 부르지 않는다: like 최대 시도 횟수가 3이고 like-service가 토글에 503을 주면 500이 오고 토글은 한 번만 받는다") {
                wireMock.stubToggleFailure(postId = 5, status = 503)

                val response = FeedHttpClient(localPort()).toggleLike("5")

                response.status shouldBe 500
                withClue("like-service가 받은 토글 요청") { wireMock.requestsTo("/likes/5/toggle") shouldHaveSize 1 }
            }
        }

        context("재시도하는 실패") {
            test("F-005 4xx는 다시 부르지 않는다: recommendation 최대 시도 횟수가 3이고 recommendation-service가 404를 주면 요청을 한 번만 받는다") {
                wireMock.stubFailure("recommendation", 404)

                FeedHttpClient(localPort()).getFeed().status shouldBe 200

                withClue("recommendation-service가 받은 요청") { wireMock.requestsTo("/recommendations") shouldHaveSize 1 }
            }

            test("F-005 시간 상한 초과는 다시 부르지 않는다: recommendation 상한이 200ms이고 1초 늦게 응답하면 recommendation-service는 요청을 한 번만 받는다") {
                wireMock.stubRecommendations(delayMs = 1_000)

                val response = FeedHttpClient(localPort()).getFeed()

                response.status shouldBe 200
                withClue("degradation.missing") { response.json().missing() shouldBe listOf("recommendation") }
                withClue("recommendation-service가 받은 요청") { wireMock.requestsTo("/recommendations") shouldHaveSize 1 }
            }

            test("F-005 열린 서킷은 다시 부르지 않는다: like 서킷이 열려 있으면 like-service는 요청을 받지 않고 downstream_call_retried 줄도 없다") {
                circuitBreakerRegistry().circuitBreaker("like").transitionToOpenState()
                val client = TracedFeedHttp(localPort())

                LogCapture().use { capture ->
                    val response = client.getFeed()

                    response.statusCode() shouldBe 200
                    withClue("서킷이 열린 동안 like-service가 받은 요청") { wireMock.requestsTo("/likes").shouldBeEmpty() }
                    capture.awaitRequestLine(response.traceId())
                    withClue("downstream_call_retried 줄") { capture.retriedLines().shouldBeEmpty() }
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
 * `retry-policy` 스펙 "재시도하는 실패"의 "연결 실패는 다시 부른다" (F-005).
 *
 * recommendation 주소는 듣는 프로세스가 없는 포트([DownstreamWireMock.registerBaseUrls]의 `closed`)라 연결이 바로 거부된다
 * (`connect_failure`). 최대 시도 횟수 3이면 첫 시도 뒤 두 번 다시 부르고, 다시 부를 때마다 `downstream_call_retried` 줄을 남긴다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.recommendation.max-attempts=3",
        "resilience4j.retry.instances.recommendation.wait-duration=10ms",
        "weave.retry.budget.ratio=1",
        "weave.retry.budget.min-retries=1000",
        "weave.retry.budget.window=10s",
    ],
)
class FeedRetryConnectFailureScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-005 연결 실패는 다시 부른다: recommendation 주소에 듣는 프로세스가 없으면 200이고 missing은 recommendation 하나, connect_failure 재시도 줄이 두 개다") {
            val client = TracedFeedHttp(localPort())

            LogCapture().use { capture ->
                val response = client.getFeed()

                response.statusCode() shouldBe 200
                withClue("degradation.missing") { response.json().missing() shouldBe listOf("recommendation") }
                capture.awaitRequestLine(response.traceId())
                withClue("recommendation의 connect_failure downstream_call_retried 줄") {
                    capture.retriedLines("recommendation", "connect_failure") shouldHaveSize 2
                }
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) =
            DownstreamWireMock.registerBaseUrls(registry, closed = setOf("recommendation"))
    }
}

/**
 * `retry-policy` 스펙 "재시도하는 실패"의 "재시도하다 서킷이 열린다" (F-005).
 *
 * like 서킷은 최근 호출 2개 중 실패 50% 이상이면 열린다(창이 차기 전에는 열지 않는다). 첫 시도와 첫 재시도가 503으로 창을 채워 서킷을 열고,
 * 셋째 시도는 서킷이 막아(`circuit_open`, 재시도하지 않는 실패) 재시도가 멈춘다. 느린 호출 경계는 테스트 기본 상한(2초)으로 둔다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.max-attempts=3",
        "resilience4j.retry.instances.like.wait-duration=10ms",
        "resilience4j.circuitbreaker.instances.like.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.like.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.like.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.like.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.like.slow-call-duration-threshold=2s",
        "resilience4j.circuitbreaker.instances.like.wait-duration-in-open-state=10s",
        "weave.retry.budget.ratio=1",
        "weave.retry.budget.min-retries=1000",
        "weave.retry.budget.window=10s",
    ],
)
class FeedRetryCircuitOpensScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-005 재시도하다 서킷이 열린다: like 서킷이 최근 2개 중 50%에 열리고 like-service가 늘 503이면 좋아요 조회는 두 번만 가고 like 실패 로그의 cause는 circuit_open이다") {
            wireMock.stubFailure("like", 503)
            val client = TracedFeedHttp(localPort())

            LogCapture().use { capture ->
                val response = client.getFeed()

                response.statusCode() shouldBe 200
                withClue("like-service가 받은 좋아요 조회") { wireMock.requestsTo("/likes") shouldHaveSize 2 }
                capture.awaitRequestLine(response.traceId())
                withClue("like의 downstream_call_failed 줄의 cause") {
                    capture.failureLines("like").map { it["cause"] } shouldBe listOf("circuit_open")
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
 * `retry-policy` 스펙의 "재시도 간격" (F-005).
 *
 * like의 기본 간격 100ms, 배수 2, jitter 비율 0.5다. Resilience4j 스타터는 지수 backoff와 무작위 대기를 함께 켜면 둘을 합친 간격 함수
 * (`IntervalFunction.ofExponentialRandomBackoff`)를 만든다. 첫 재시도 전 50~150ms, 둘째 전 100~300ms에 시도 자체의 시간이 더해지므로,
 * 스펙의 범위(50~250ms, 100~400ms)는 그 여유를 둔 것이다. 간격은 WireMock이 기록한 요청 시각(밀리초)으로 잰다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.max-attempts=3",
        "resilience4j.retry.instances.like.wait-duration=100ms",
        "resilience4j.retry.instances.like.enable-exponential-backoff=true",
        "resilience4j.retry.instances.like.exponential-backoff-multiplier=2",
        "resilience4j.retry.instances.like.enable-randomized-wait=true",
        "resilience4j.retry.instances.like.randomized-wait-factor=0.5",
        "weave.retry.budget.ratio=1",
        "weave.retry.budget.min-retries=1000",
        "weave.retry.budget.window=10s",
    ],
)
class FeedRetryIntervalScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-005 간격이 늘어난다: like 기본 간격 100ms, 배수 2, jitter 0.5이고 like-service가 늘 503이면 요청 사이가 50~250ms, 100~400ms다") {
            wireMock.stubFailure("like", 503)

            FeedHttpClient(localPort()).getFeed().status shouldBe 200

            val requests = wireMock.requestsTo("/likes")
            withClue("like-service가 받은 좋아요 조회") { requests shouldHaveSize 3 }
            val times = requests.map { it.loggedDate.time }.sorted()
            withClue("첫 요청과 둘째 요청 사이(ms)") { (times[1] - times[0]) shouldBeInRange 50L..250L }
            withClue("둘째 요청과 셋째 요청 사이(ms)") { (times[2] - times[1]) shouldBeInRange 100L..400L }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/**
 * `retry-policy` 스펙 "남은 시간 예산 안에서만 재시도한다"의 "남은 예산이 모자라다" (F-005).
 *
 * 요청 시간 예산 250ms, like의 호출 시간 상한과 응답 타임아웃 200ms, 최대 시도 횟수 3이다. like-service는 100ms 늦게 503을 주므로 첫 시도는
 * 피드 요청 시작 뒤 100ms 넘어 끝나고, 남은 예산(150ms 미만)이 상한(200ms)과 기다릴 시간의 합보다 짧아 다시 부르지 않는다(결정 `deadline`).
 * 재시도 결정 지표는 피드 전후의 `/actuator/prometheus`로 본다(`@AutoConfigureMetrics`). 첫 요청의 클래스 로딩과 연결 수립이 예산 250ms에
 * 걸리지 않게 정상 피드를 한 번 먼저 부르고 요청 기록을 비운다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.max-attempts=3",
        "resilience4j.retry.instances.like.wait-duration=10ms",
        "resilience4j.timelimiter.instances.like.timeout-duration=200ms",
        "spring.http.serviceclient.like.read-timeout=200ms",
        "weave.feed.time-budget=250ms",
        "weave.retry.budget.ratio=1",
        "weave.retry.budget.min-retries=1000",
        "weave.retry.budget.window=10s",
    ],
)
class FeedRetryDeadlineScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
            FeedHttpClient(localPort()).getFeed()
            resetCircuits()
            wireMock.server.resetRequests()
        }

        test(
            "F-005 남은 예산이 모자라다: 예산 250ms, like 상한 200ms이고 like-service가 100ms 늦게 503을 주면 missing은 like 하나, 좋아요 조회는 한 번이고 deadline 결정이 1 늘었다",
        ) {
            wireMock.server.stubFor(get(urlPathEqualTo("/likes")).willReturn(aResponse().withStatus(503).withFixedDelay(100)))
            val port = localPort()
            val before = withClue("피드 전의 like deadline 재시도 결정") { retryCalls(port, "like", "deadline").shouldNotBeNull() }

            val response = FeedHttpClient(port).getFeed()

            response.status shouldBe 200
            withClue("degradation.missing") { response.json().missing() shouldBe listOf("like") }
            withClue("like-service가 받은 좋아요 조회") { wireMock.requestsTo("/likes") shouldHaveSize 1 }
            eventually(5.seconds) {
                withClue("피드 뒤 like deadline 재시도 결정의 증가") {
                    retryCalls(port, "like", "deadline")?.minus(before) shouldBe 1.0
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

private val HEALTHY_POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4))

private val HEALTHY_LIKE_STATES =
    listOf(
        LikeState(postId = 10, likeCount = 1, liked = false),
        LikeState(postId = 11, likeCount = 0, liked = false),
    )

/** [HEALTHY_LIKE_STATES]를 피드가 게시글마다 보여 주는 `like`. */
private val HEALTHY_LIKES = HEALTHY_LIKE_STATES.map { mapOf("count" to it.likeCount.toInt(), "myLike" to "NOT_LIKED") }

private fun DownstreamWireMock.stubAllHealthy() {
    stubPosts(HEALTHY_POSTS)
    stubUsers(listOf(3, 4))
    stubLikes(HEALTHY_LIKE_STATES)
    stubRecommendations()
    stubAds()
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

/** traceId가 [traceId]인 `http_request` 줄(요청을 마친 표지)이 잡힐 때까지 기다린다. 그 요청의 실패·재시도 로그는 이보다 먼저 남는다. */
private suspend fun LogCapture.awaitRequestLine(traceId: String) =
    eventually(5.seconds) {
        withClue("traceId가 $traceId 인 http_request 줄") {
            jsonLines().filter { it["event"] == "http_request" && it["traceId"] == traceId }.shouldNotBeEmpty()
        }
    }

/** [dependency]의 `downstream_call_failed` 줄. */
private fun LogCapture.failureLines(dependency: String): List<Map<String, Any?>> =
    jsonLines().filter { it["event"] == "downstream_call_failed" && it["dependency"] == dependency }

/** `downstream_call_retried` 줄. [dependency], [cause]를 주면 그 값인 줄만 남긴다. */
private fun LogCapture.retriedLines(
    dependency: String? = null,
    cause: String? = null,
): List<Map<String, Any?>> =
    jsonLines().filter {
        it["event"] == "downstream_call_retried" &&
            (dependency == null || it["dependency"] == dependency) &&
            (cause == null || it["cause"] == cause)
    }

private val prometheusClient: HttpClient = HttpClient.newHttpClient()

/** `/actuator/prometheus`의 `weave_retry_calls_total{name,outcome}` 값. 표본이 없으면 null이다. */
private fun retryCalls(
    port: Int,
    name: String,
    outcome: String,
): Double? {
    val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build()
    val response = prometheusClient.send(request, HttpResponse.BodyHandlers.ofString())
    response.statusCode() shouldBe 200
    return response
        .body()
        .lineSequence()
        .filter { it.startsWith("weave_retry_calls_total{") }
        .firstOrNull { """name="$name"""" in it && """outcome="$outcome"""" in it }
        ?.substringAfterLast('}')
        ?.trim()
        ?.split(' ')
        ?.first()
        ?.toDouble()
}
