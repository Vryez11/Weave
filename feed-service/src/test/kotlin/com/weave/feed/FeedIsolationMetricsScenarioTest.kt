package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
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
 * `call-isolation` 델타의 "격리 지표" (F-001).
 *
 * 하위 서비스 다섯이 정상 응답하게 하고 `GET /feed`를 한 번 부른 뒤 `/actuator/prometheus` 본문을 본다. 지표는 Resilience4j Micrometer
 * 연동의 이름(design 결정 2)이고 의존성은 `name` 라벨로 찾는다.
 *
 * | 무엇 | 지표 |
 * |---|---|
 * | 서킷 상태 | `resilience4j_circuitbreaker_state{name,state}`. 지금 상태의 `state` 표본 값이 1이다 |
 * | 서킷이 막은 호출 수 | `resilience4j_circuitbreaker_not_permitted_calls_total{name}` |
 * | 벌크헤드 사용 중·최대 스레드, 대기열 | `resilience4j_bulkhead_active_thread_count`, `resilience4j_bulkhead_max_thread_pool_size`, `resilience4j_bulkhead_queue_depth` |
 * | 시간 상한 초과 수 | `resilience4j_timelimiter_calls_total{name,kind="timeout"}` |
 *
 * 이름은 add-call-isolation tasks 2.1의 호환 확인에서 다시 본다. 지표가 장애 없이도 시계열을 갖는지(0부터 등록되는지)도 함께 본다.
 * Boot 테스트는 메트릭 내보내기를 꺼서 `prometheus` 엔드포인트가 404다. `@AutoConfigureMetrics`로 실제 실행과 같게 켠다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedIsolationMetricsScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock
        val dependencies = DownstreamWireMock.dependencies

        suspend fun port(): Int =
            testContextManager()
                .testContext.applicationContext.environment
                .getRequiredProperty("local.server.port")
                .toInt()

        beforeEach {
            wireMock.reset()
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3)))
            wireMock.stubUsers(listOf(3))
            wireMock.stubLikes(listOf(LikeState(postId = 10, likeCount = 1, liked = false)))
            wireMock.stubRecommendations()
            wireMock.stubAds()
        }

        test("F-001 지표가 있다: 피드를 한 번 부르면 의존성 다섯마다 서킷 상태, 벌크헤드 사용량, 시간 상한 초과 지표가 있고 다섯 서킷이 모두 닫힘이다") {
            val port = port()
            FeedHttpClient(port).getFeed().status shouldBe 200

            eventually(5.seconds) {
                val samples = IsolationSamples.scrape(port)
                dependencies.forEach { dependency ->
                    withClue("$dependency 의 격리 지표") {
                        samples.shouldHave("resilience4j_circuitbreaker_state", "name" to dependency)
                        samples.shouldHave("resilience4j_circuitbreaker_not_permitted_calls_total", "name" to dependency)
                        samples.shouldHave("resilience4j_bulkhead_active_thread_count", "name" to dependency)
                        samples.shouldHave("resilience4j_bulkhead_max_thread_pool_size", "name" to dependency)
                        samples.shouldHave("resilience4j_bulkhead_queue_depth", "name" to dependency)
                        samples.shouldHave("resilience4j_timelimiter_calls_total", "name" to dependency, "kind" to "timeout")
                    }
                    withClue("$dependency 서킷의 closed 상태 값") {
                        samples
                            .values("resilience4j_circuitbreaker_state", "name" to dependency, "state" to "closed")
                            .shouldNotBeEmpty()
                            .single() shouldBe 1.0
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

/** Prometheus 텍스트 형식의 표본 줄(`이름{라벨="값",...} 값`)을 값과 함께 읽는다. 주석 줄(`#`)은 건너뛴다. */
private class IsolationSamples(
    private val samples: List<Sample>,
) {
    data class Sample(
        val name: String,
        val labels: Map<String, String>,
        val value: Double,
    )

    /** [name]이고 [labels]를 모두 가진 표본의 값. */
    fun values(
        name: String,
        vararg labels: Pair<String, String>,
    ): List<Double> = matching(name, labels).map { it.value }

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

        fun scrape(port: Int): IsolationSamples {
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            return parse(response.body())
        }

        fun parse(body: String): IsolationSamples {
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
            return IsolationSamples(samples)
        }
    }
}
