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
 * `fault-experiments` 스펙의 "feed-service의 실험 지표" (F-007).
 *
 * 하위 서비스 다섯이 정상 응답하게 하고([DownstreamWireMock]) `GET /feed`를 한 번 부른 뒤 `/actuator/prometheus` 본문을 본다.
 * 지표 이름은 add-fault-experiments design 결정 4의 표를 따른다. 의존성은 `client_name`(호출 지연)과 `httpclient`(커넥션 풀) 라벨의 값으로 찾는다.
 * MVP의 공유 호출 스레드 풀(`applicationTaskExecutor`) 지표는 고도화 3에서 의존성별 벌크헤드 지표로 바뀌었다(F-001). 벌크헤드 지표는
 * Resilience4j Micrometer 연동의 이름(`resilience4j_bulkhead_active_thread_count`, `resilience4j_bulkhead_queue_depth`)이고 의존성은
 * `name` 라벨로 찾는다. 이 이름은 add-call-isolation tasks 2.1의 호환 확인에서 다시 본다.
 * 서버 요청 지표는 응답을 보낸 뒤 기록될 수 있어 몇 초 동안 다시 긁는다.
 *
 * Boot 테스트는 메트릭 내보내기를 꺼서 `prometheus` 엔드포인트가 404다. `@AutoConfigureMetrics`로 실제 실행과 같게 켠다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedExperimentMetricsScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock
        val dependencies = DownstreamWireMock.dependencies

        suspend fun port(): Int =
            testContextManager()
                .testContext.applicationContext.environment
                .getRequiredProperty("local.server.port")
                .toInt()

        fun stubAllHealthy() {
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3)))
            wireMock.stubUsers(listOf(3))
            wireMock.stubLikes(listOf(LikeState(postId = 10, likeCount = 1, liked = false)))
            wireMock.stubRecommendations()
            wireMock.stubAds()
        }

        /** 피드를 한 번 부르고, [assertions]가 통과할 때까지(최대 5초) `/actuator/prometheus`를 다시 긁는다. */
        suspend fun afterFeed(assertions: (PrometheusSamples) -> Unit) {
            val port = port()
            stubAllHealthy()
            FeedHttpClient(port).getFeed().status shouldBe 200

            eventually(5.seconds) {
                assertions(PrometheusSamples.scrape(port))
            }
        }

        beforeEach { wireMock.reset() }

        context("지표가 있다") {
            test("F-007 지표가 있다: Tomcat 사용 중 스레드 값이 있다") {
                afterFeed { samples ->
                    samples.shouldHave("tomcat_threads_busy_threads")
                }
            }

            dependencies.forEach { dependency ->
                test("F-001 지표가 있다: $dependency 벌크헤드의 사용 중 스레드와 대기열 값이 있다") {
                    afterFeed { samples ->
                        samples.shouldHave("resilience4j_bulkhead_active_thread_count", "name" to dependency)
                        samples.shouldHave("resilience4j_bulkhead_queue_depth", "name" to dependency)
                    }
                }

                test("F-007 지표가 있다: $dependency 커넥션 풀의 사용 중·대기 값이 있다") {
                    afterFeed { samples ->
                        samples.shouldHave(
                            "httpcomponents_httpclient_pool_total_connections",
                            "httpclient" to dependency,
                            "state" to "leased",
                        )
                        samples.shouldHave("httpcomponents_httpclient_pool_total_pending", "httpclient" to dependency)
                    }
                }
            }

            dependencies.forEach { dependency ->
                test("F-007 지표가 있다: $dependency 호출 지연 히스토그램 버킷이 있다") {
                    afterFeed { samples ->
                        samples.shouldHave("http_client_requests_seconds_bucket", "client_name" to dependency)
                    }
                }
            }

            test("F-007 지표가 있다: /feed 요청 지연 히스토그램 버킷이 있다") {
                afterFeed { samples ->
                    samples.shouldHave("http_server_requests_seconds_bucket", "uri" to "/feed")
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

/** Prometheus 텍스트 형식의 표본 줄(`이름{라벨="값",...} 값`)을 읽는다. 주석 줄(`#`)은 건너뛴다. */
private class PrometheusSamples(
    private val samples: List<Sample>,
) {
    data class Sample(
        val name: String,
        val labels: Map<String, String>,
    )

    /** [name]이고 [labels]를 모두 가진 표본이 있어야 한다. 없으면 같은 이름의 표본 라벨을 보여 준다. */
    fun shouldHave(
        name: String,
        vararg labels: Pair<String, String>,
    ) {
        val sameName = samples.filter { it.name == name }
        val matched = sameName.filter { sample -> labels.all { (key, value) -> sample.labels[key] == value } }
        withClue("$name${labels.toMap()} 표본이 없다. 같은 이름의 표본 라벨: ${sameName.map { it.labels }.take(10)}") {
            matched.shouldNotBeEmpty()
        }
    }

    companion object {
        private val client: HttpClient = HttpClient.newHttpClient()
        private val label = Regex("""(\w+)="((?:[^"\\]|\\.)*)"""")

        fun scrape(port: Int): PrometheusSamples {
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            return parse(response.body())
        }

        fun parse(body: String): PrometheusSamples {
            val samples =
                body
                    .lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { line ->
                        val braceAt = line.indexOf('{')
                        val spaceAt = line.indexOf(' ')
                        if (braceAt in 0 until spaceAt) {
                            val labelText = line.substring(braceAt + 1, line.lastIndexOf('}'))
                            Sample(
                                name = line.substring(0, braceAt),
                                labels = label.findAll(labelText).associate { it.groupValues[1] to it.groupValues[2] },
                            )
                        } else {
                            Sample(name = line.substring(0, spaceAt), labels = emptyMap())
                        }
                    }.toList()
            return PrometheusSamples(samples)
        }
    }
}
