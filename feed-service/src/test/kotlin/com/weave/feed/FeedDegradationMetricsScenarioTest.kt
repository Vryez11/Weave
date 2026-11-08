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
 * `graceful-degradation` 스펙 "대체 지표" (F-002).
 *
 * `/actuator/prometheus` 본문을 본다. 지표 이름은 design 결정 10이다(대시보드 Compose 테스트도 같은 이름을 기대한다).
 *
 * | 무엇 | Prometheus 이름 | 라벨 |
 * |---|---|---|
 * | 200 피드 응답의 영역 결과 | `weave_feed_sections_total` (`weave.feed.sections`) | `section`(의존성 5개), `outcome`(`fresh`, `cached`, `stale`, `missing`) |
 * | post 캐시 읽기 결과 | `weave_post_cache_reads_total` (`weave.post.cache.reads`) | `outcome`(`hit`, `expired`, `miss`, `error`) |
 * | post 캐시 쓰기 결과 | `weave_post_cache_writes_total` (`weave.post.cache.writes`) | `outcome`(`success`, `error`, `dropped`) |
 * | user 캐시 조회 적중·누락 | `cache_gets_total` (Caffeine `CaffeineCacheMetrics`) | `cache="user-profiles"`, `result`(`hit`, `miss`) |
 *
 * - **값은 차이로 본다:** 같은 설정의 Spring 컨텍스트는 클래스 사이에 다시 쓰이고 카운터는 컨텍스트에 남는다. 그래서 피드를 부르기 전과 뒤에 긁어
 *   그 사이에 늘어난 값을 단언한다. "정상 값이 1, 나머지는 0"은 "정상이 1 늘고 나머지는 늘지 않았다"로 본다.
 * - **0부터 있다:** "지표가 있다"는 피드를 부르기 전에 긁은 본문에 시계열이 모두 있는지도 본다. 영역 결과 20개는 시작할 때 0으로 등록한다(design
 *   결정 10).
 * - 카운터는 응답 뒤에 기록될 수 있어 몇 초 동안 다시 긁는다. Boot 테스트는 메트릭 내보내기를 꺼서 `@AutoConfigureMetrics`로 켠다.
 * - user 로컬 캐시 값은 main 값이 없는 동안(tasks 4.2 전) 클래스 속성으로 준다. 캐시 저장소(Redis)는 테스트 기본값(죽은 저장소)이다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.user-cache.expire-after-write=1h",
        "weave.user-cache.maximum-size=100",
    ],
)
class FeedDegradationMetricsScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock
        val dependencies = DownstreamWireMock.dependencies

        beforeEach { wireMock.reset() }

        context("대체 지표") {
            test(
                "F-002 지표가 있다: 하위 서비스 5개가 정상이면 피드를 부르기 전부터 영역 결과·post 캐시 읽기·쓰기·user 캐시 적중·누락 시계열이 있고, 한 번 부르면 의존성마다 fresh만 1 늘고 나머지는 늘지 않는다",
            ) {
                val port = localPort()
                wireMock.stubAllHealthy()

                val before = MetricSamples.scrape(port)
                dependencies.forEach { dependency ->
                    SECTION_OUTCOMES.forEach { outcome ->
                        before.shouldHave(SECTIONS, "section" to dependency, "outcome" to outcome)
                    }
                }
                POST_CACHE_READ_OUTCOMES.forEach { before.shouldHave(POST_CACHE_READS, "outcome" to it) }
                POST_CACHE_WRITE_OUTCOMES.forEach { before.shouldHave(POST_CACHE_WRITES, "outcome" to it) }
                listOf("hit", "miss").forEach { before.shouldHave(CACHE_GETS, "cache" to USER_CACHE, "result" to it) }

                FeedHttpClient(port).getFeed().status shouldBe 200

                eventually(5.seconds) {
                    val after = MetricSamples.scrape(port)
                    dependencies.forEach { dependency ->
                        SECTION_OUTCOMES.forEach { outcome ->
                            val labels = arrayOf("section" to dependency, "outcome" to outcome)
                            withClue("$dependency 영역 $outcome 의 증가") {
                                after.value(SECTIONS, *labels) - before.value(SECTIONS, *labels) shouldBe
                                    (if (outcome == "fresh") 1.0 else 0.0)
                            }
                        }
                    }
                }
            }

            test(
                "F-002 대체를 센다: 캐시에 작성자가 모두 있고 user-service와 recommendation-service가 503이면 피드 한 번에 user cached와 recommendation missing이 각각 1 늘었다",
            ) {
                val port = localPort()
                val userCached = arrayOf("section" to "user", "outcome" to "cached")
                val recommendationMissing = arrayOf("section" to "recommendation", "outcome" to "missing")
                val userFresh = arrayOf("section" to "user", "outcome" to "fresh")
                val beforeSeed = MetricSamples.scrape(port).value(SECTIONS, *userFresh)
                wireMock.stubAllHealthy()
                withClue("작성자 프로필을 캐시에 남기는 정상 요청") { FeedHttpClient(port).getFeed().status shouldBe 200 }
                // 정상 요청의 카운터(user fresh +1)가 기록된 뒤의 값을 기준으로 둔다
                val before =
                    eventually(5.seconds) {
                        MetricSamples.scrape(port).also { samples -> samples.value(SECTIONS, *userFresh) shouldBe beforeSeed + 1.0 }
                    }
                wireMock.stubFailure("user", 503)
                wireMock.stubFailure("recommendation", 503)

                FeedHttpClient(port).getFeed().status shouldBe 200

                eventually(5.seconds) {
                    val after = MetricSamples.scrape(port)
                    withClue("user 영역 cached의 증가") {
                        after.value(SECTIONS, *userCached) - before.value(SECTIONS, *userCached) shouldBe 1.0
                    }
                    withClue("recommendation 영역 missing의 증가") {
                        after.value(SECTIONS, *recommendationMissing) - before.value(SECTIONS, *recommendationMissing) shouldBe 1.0
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

private const val SECTIONS = "weave_feed_sections_total"
private const val POST_CACHE_READS = "weave_post_cache_reads_total"
private const val POST_CACHE_WRITES = "weave_post_cache_writes_total"
private const val CACHE_GETS = "cache_gets_total"
private const val USER_CACHE = "user-profiles"

private val SECTION_OUTCOMES = listOf("fresh", "cached", "stale", "missing")
private val POST_CACHE_READ_OUTCOMES = listOf("hit", "expired", "miss", "error")
private val POST_CACHE_WRITE_OUTCOMES = listOf("success", "error", "dropped")

private val POSTS = listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4))

private fun DownstreamWireMock.stubAllHealthy() {
    stubPosts(POSTS)
    stubUsers(POSTS.map { it.authorId })
    stubLikes(POSTS.map { LikeState(postId = it.id, likeCount = 1, liked = false) })
    stubRecommendations()
    stubAds()
}

private suspend fun localPort(): Int =
    testContextManager()
        .testContext.applicationContext.environment
        .getRequiredProperty("local.server.port")
        .toInt()

/** Prometheus 텍스트 형식의 표본 줄(`이름{라벨="값",...} 값`)을 값과 함께 읽는다. 주석 줄(`#`)은 건너뛴다. */
private class MetricSamples(
    private val samples: List<Sample>,
) {
    data class Sample(
        val name: String,
        val labels: Map<String, String>,
        val value: Double,
    )

    /** [name]이고 [labels]를 모두 가진 표본 하나의 값. 없거나 여럿이면 실패한다. */
    fun value(
        name: String,
        vararg labels: Pair<String, String>,
    ): Double {
        shouldHave(name, *labels)
        val matched = matching(name, labels)
        withClue("$name${labels.toMap()} 표본이 하나가 아니다: ${matched.map { it.labels }}") { matched.size shouldBe 1 }
        return matched.single().value
    }

    /** [name]이고 [labels]를 모두 가진 표본이 있어야 한다. 없으면 같은 이름의 표본 라벨을 보여 준다. */
    fun shouldHave(
        name: String,
        vararg labels: Pair<String, String>,
    ) {
        withClue("$name${labels.toMap()} 표본이 없다. 같은 이름의 표본 라벨: ${samples.filter { it.name == name }.map { it.labels }.take(20)}") {
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

        fun scrape(port: Int): MetricSamples {
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            return parse(response.body())
        }

        fun parse(body: String): MetricSamples {
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
            return MetricSamples(samples)
        }
    }
}
