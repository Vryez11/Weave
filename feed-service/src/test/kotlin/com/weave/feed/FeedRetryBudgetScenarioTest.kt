package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
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
 * `retry-policy` 스펙 "재시도 예산"의 "예산이 다 된다" (F-005).
 *
 * 예산 비율 0.1, 최솟값 2, 창 10초(`weave.retry.budget.*`, add-retry-policy design 결정 5)이고 like 최대 시도 횟수 3, 간격 10ms다.
 * 다시 부를 수 있는 것은 창 안의 재시도가 `max(최솟값, floor(비율 × 창 안의 원래 호출 수))`보다 적을 때다. 피드 20번이면 원래 호출이 20개라
 * `floor(0.1 × 20) = 2`, 최솟값과 같은 2라서 첫 피드의 재시도 둘만 나가고 like-service는 20 + 2 = 22개를 받는다.
 *
 * like 서킷은 창 안의 호출이 1,000개가 되기 전에는 열지 않아(최소 호출 수) 22개의 503에도 닫혀 있다. 예산은 컨텍스트에 남으므로 이 클래스는
 * 시나리오 하나만 두고 정상 피드로 미리 데우지 않는다(데우는 피드도 원래 호출로 센다). 재시도 결정 지표는 `/actuator/prometheus`로 본다.
 * 창이 다른 "창이 지나면 다시 쓴다"는 [FeedRetryBudgetWindowScenarioTest]다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.max-attempts=3",
        "resilience4j.retry.instances.like.wait-duration=10ms",
        "resilience4j.circuitbreaker.instances.like.minimum-number-of-calls=1000",
        "weave.retry.budget.ratio=0.1",
        "weave.retry.budget.min-retries=2",
        "weave.retry.budget.window=10s",
    ],
)
class FeedRetryBudgetScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-005 예산이 다 된다: 비율 0.1, 최솟값 2, 창 10초에 like-service가 늘 503이면 10초 안에 피드 20번으로 좋아요 조회는 22개이고 budget_exhausted 결정이 늘었다") {
            wireMock.stubFailure("like", 503)
            val port = localPort()
            val before = withClue("피드 전의 like budget_exhausted 재시도 결정") { retryCalls(port, "like", "budget_exhausted").shouldNotBeNull() }
            val client = FeedHttpClient(port)

            repeat(FEEDS) { index ->
                withClue("${index + 1}번째 피드") { client.getFeed().status shouldBe 200 }
            }

            withClue("like-service가 받은 좋아요 조회") { wireMock.requestsTo("/likes") shouldHaveSize FEEDS + 2 }
            eventually(5.seconds) {
                withClue("피드 뒤 like budget_exhausted 재시도 결정") {
                    retryCalls(port, "like", "budget_exhausted").shouldNotBeNull() shouldBeGreaterThan before
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
 * `retry-policy` 스펙 "재시도 예산"의 "창이 지나면 다시 쓴다" (F-005).
 *
 * [FeedRetryBudgetScenarioTest]와 같은 설정에서 창만 1초다. 피드 20번으로 예산을 쓴 뒤 1.5초 기다리면 창 안에 원래 호출도 재시도도 없어
 * 최솟값(2)만큼 다시 부를 수 있으므로, 다음 피드 하나로 like-service는 좋아요 조회를 세 번 받는다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.max-attempts=3",
        "resilience4j.retry.instances.like.wait-duration=10ms",
        "resilience4j.circuitbreaker.instances.like.minimum-number-of-calls=1000",
        "weave.retry.budget.ratio=0.1",
        "weave.retry.budget.min-retries=2",
        "weave.retry.budget.window=1s",
    ],
)
class FeedRetryBudgetWindowScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-005 창이 지나면 다시 쓴다: 창이 1초이고 피드 20번으로 예산을 쓴 뒤 1.5초 뒤 피드를 한 번 부르면 그 피드로 like-service는 좋아요 조회를 세 번 받는다") {
            wireMock.stubFailure("like", 503)
            val client = FeedHttpClient(localPort())
            repeat(FEEDS) { client.getFeed() }

            Thread.sleep(WINDOW_PASSED_MS)
            wireMock.server.resetRequests()
            client.getFeed().status shouldBe 200

            withClue("창이 지난 뒤의 피드로 like-service가 받은 좋아요 조회") { wireMock.requestsTo("/likes") shouldHaveSize 3 }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}

/** 예산을 쓰는 피드 수. */
private const val FEEDS = 20

/** "창이 지나면 다시 쓴다"에서 창(1초)이 지나도록 기다리는 시간. */
private const val WINDOW_PASSED_MS = 1_500L

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
