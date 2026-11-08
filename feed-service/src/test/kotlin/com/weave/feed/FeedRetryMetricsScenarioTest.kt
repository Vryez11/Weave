package com.weave.feed

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario
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
import io.kotest.matchers.collections.shouldNotBeEmpty
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
 * `retry-policy` 스펙 "재시도와 외부 API 지표"의 "지표가 있다", "재시도를 센다" (F-005).
 *
 * 지표 이름은 add-retry-policy design 결정 9의 Micrometer 이름을 Prometheus 형식으로 바꾼 것이다.
 *
 * | 무엇 | 지표 |
 * |---|---|
 * | 재시도 결정 수 | `weave_retry_calls_total{name,outcome}`. `outcome`은 `retried`, `budget_exhausted`, `deadline`, `attempts_exhausted` |
 * | Retry-After 대기로 막은 호출 수 | `weave_retry_after_rejected_calls_total{name}` |
 * | 남은 Retry-After 대기 시간(초) | `weave_retry_after_hold_remaining_seconds{name}` |
 * | 요청 수 상한으로 막은 호출 수 | `weave_ratelimiter_rejected_calls_total{name="ad"}` |
 *
 * 모두 시작할 때 0으로 등록하므로 "지표가 있다"는 피드를 부르기 **전에** 본다. 그래서 이 클래스의 첫 시나리오이고, 컨텍스트를 다른 클래스와
 * 같이 쓰지 않도록 이 클래스만의 속성 조합을 준다(like 최대 시도 횟수 3, 간격 10ms, 넉넉한 재시도 예산). Boot 테스트는 메트릭 내보내기를 꺼서
 * `prometheus` 엔드포인트가 404이므로 `@AutoConfigureMetrics`로 켠다. "재시도를 센다"는 첫 피드의 클래스 로딩과 연결 수립을 치우려 정상 피드를
 * 한 번 먼저 부르고, 503 피드 전후의 값을 뺀다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.max-attempts=3",
        "resilience4j.retry.instances.like.wait-duration=10ms",
        "weave.retry.budget.ratio=1",
        "weave.retry.budget.min-retries=1000",
        "weave.retry.budget.window=10s",
    ],
)
class FeedRetryMetricsScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock
        val dependencies = DownstreamWireMock.dependencies

        beforeEach {
            wireMock.reset()
            wireMock.stubAllHealthy()
            resetCircuits()
        }

        test("F-005 지표가 있다: 피드를 부르기 전에 의존성 다섯마다 재시도 결정 네 가지, Retry-After로 막은 호출 수, 남은 대기 시간이 있고 ad의 상한으로 막은 호출 수가 있다") {
            val samples = RetrySamples.scrape(localPort())

            dependencies.forEach { dependency ->
                withClue("$dependency 의 재시도·대기 지표") {
                    RETRY_OUTCOMES.forEach { outcome ->
                        samples.shouldHave("weave_retry_calls_total", "name" to dependency, "outcome" to outcome)
                    }
                    samples.shouldHave("weave_retry_after_rejected_calls_total", "name" to dependency)
                    samples.shouldHave("weave_retry_after_hold_remaining_seconds", "name" to dependency)
                }
            }
            samples.shouldHave("weave_ratelimiter_rejected_calls_total", "name" to "ad")
        }

        test("F-005 재시도를 센다: like 최대 시도 횟수 3이고 like-service가 첫 좋아요 조회에 503을 주면 weave_retry_calls_total{name=like,outcome=retried}이 1 늘었다") {
            val port = localPort()
            FeedHttpClient(port).getFeed().status shouldBe 200
            wireMock.stubLikesFailingOnce()
            val before =
                withClue("피드 전의 like retried 재시도 결정") {
                    RetrySamples.scrape(port).value("weave_retry_calls_total", "name" to "like", "outcome" to "retried").shouldNotBeNull()
                }

            FeedHttpClient(port).getFeed().status shouldBe 200

            eventually(5.seconds) {
                withClue("피드 뒤 like retried 재시도 결정의 증가") {
                    RetrySamples
                        .scrape(port)
                        .value("weave_retry_calls_total", "name" to "like", "outcome" to "retried")
                        ?.minus(before) shouldBe 1.0
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

private val RETRY_OUTCOMES = listOf("retried", "budget_exhausted", "deadline", "attempts_exhausted")

/** Prometheus 텍스트 형식의 표본 줄(`이름{라벨="값",...} 값`)을 값과 함께 읽는다. 주석 줄(`#`)은 건너뛴다. */
private class RetrySamples(
    private val samples: List<Sample>,
) {
    data class Sample(
        val name: String,
        val labels: Map<String, String>,
        val value: Double,
    )

    /** [name]이고 [labels]를 모두 가진 표본 하나의 값. 없으면 null, 여럿이면 테스트를 실패시킨다. */
    fun value(
        name: String,
        vararg labels: Pair<String, String>,
    ): Double? {
        val matched = matching(name, labels)
        if (matched.isEmpty()) return null
        return withClue("$name${labels.toMap()} 표본") { matched.shouldHaveSize(1).single().value }
    }

    /** [name]이고 [labels]를 모두 가진 표본이 있어야 한다. 없으면 같은 이름의 표본 라벨을 보여 준다. */
    fun shouldHave(
        name: String,
        vararg labels: Pair<String, String>,
    ) {
        withClue("$name${labels.toMap()} 표본이 없다. 같은 이름의 표본 라벨: ${samples.filter { it.name == name }.map { it.labels }.take(10)}") {
            matching(name, labels).shouldNotBeEmpty()
        }
    }

    private fun matching(
        name: String,
        labels: Array<out Pair<String, String>>,
    ): List<Sample> = samples.filter { sample -> sample.name == name && labels.all { (key, value) -> sample.labels[key] == value } }

    companion object {
        private val client: HttpClient = HttpClient.newHttpClient()
        private val label = Regex("""(\w+)="((?:[^"\\]|\\.)*)"""")

        fun scrape(port: Int): RetrySamples {
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            return parse(response.body())
        }

        private fun parse(body: String): RetrySamples {
            val samples =
                body
                    .lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { line ->
                        val closeAt = line.lastIndexOf('}')
                        val braceAt = line.indexOf('{')
                        val valueText =
                            line
                                .substring(if (closeAt >= 0) closeAt + 1 else line.indexOf(' '))
                                .trim()
                                .split(' ')
                                .first()
                        if (braceAt >= 0 && closeAt > braceAt) {
                            val labelText = line.substring(braceAt + 1, closeAt)
                            Sample(
                                name = line.substring(0, braceAt),
                                labels = label.findAll(labelText).associate { it.groupValues[1] to it.groupValues[2] },
                                value = valueText.toDouble(),
                            )
                        } else {
                            Sample(name = line.substring(0, line.indexOf(' ')), labels = emptyMap(), value = valueText.toDouble())
                        }
                    }.toList()
            return RetrySamples(samples)
        }
    }
}

private val HEALTHY_LIKE_STATES =
    listOf(
        LikeState(postId = 10, likeCount = 1, liked = false),
        LikeState(postId = 11, likeCount = 0, liked = false),
    )

private fun DownstreamWireMock.stubAllHealthy() {
    stubPosts(listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4)))
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

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()

private suspend fun circuitBreakerRegistry(): CircuitBreakerRegistry =
    testContextManager().testContext.applicationContext.getBean(CircuitBreakerRegistry::class.java)

/** 앞 시나리오가 남긴 서킷 상태와 기록을 지운다. 레지스트리의 서킷을 모두 닫힘으로 되돌린다. */
private suspend fun resetCircuits() = circuitBreakerRegistry().allCircuitBreakers.forEach { it.reset() }
