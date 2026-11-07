package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import com.weave.observability.testing.LogCapture
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * Semaphore 모드(가상 스레드 + Semaphore 벌크헤드) 구성 확인 (F-001, F-004, add-call-isolation design 결정 11, tasks 2.9).
 *
 * 가상 스레드 비교 실험의 구성(`spring.threads.virtual.enabled=true`, `weave.isolation.bulkhead-type=semaphore`)으로 띄운다.
 * 조회는 `applicationTaskExecutor`(가상 스레드)에서 돌고 의존성별 Semaphore 벌크헤드가 동시 호출 수를 묶는다. trace는 공유 풀의
 * `propagate-context`(라이브러리 기본값 `true`)가 잇는다. 끄면 끊기는 것은 [FeedTracePropagationOffTest]가 본다.
 * 격리 값은 테스트 기본값(상한 2초, 예산 5초)을 쓰고, 동시 호출 상한만 recommendation 2, 나머지 10으로 준다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.threads.virtual.enabled=true",
        "weave.isolation.bulkhead-type=semaphore",
        "resilience4j.bulkhead.instances.recommendation.max-concurrent-calls=2",
        "resilience4j.bulkhead.instances.post.max-concurrent-calls=10",
        "resilience4j.bulkhead.instances.user.max-concurrent-calls=10",
        "resilience4j.bulkhead.instances.like.max-concurrent-calls=10",
        "resilience4j.bulkhead.instances.ad.max-concurrent-calls=10",
    ],
)
class FeedSemaphoreIsolationScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun localPort(): Int =
            testContextManager()
                .testContext.applicationContext.environment
                .getRequiredProperty("local.server.port")
                .toInt()

        beforeEach {
            wireMock.reset()
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4)))
            wireMock.stubUsers(listOf(3, 4))
            wireMock.stubLikes(
                listOf(
                    LikeState(postId = 10, likeCount = 1, liked = false),
                    LikeState(postId = 11, likeCount = 0, liked = false),
                ),
            )
            wireMock.stubRecommendations()
            wireMock.stubAds()
        }

        test("F-001 F-004 Semaphore 모드의 피드: 가상 스레드와 Semaphore 벌크헤드로 띄우면 피드가 200이고 다섯 요청의 traceparent traceId가 X-Trace-Id와 같다") {
            val response = TracedFeedHttp(localPort()).getFeed()

            response.statusCode() shouldBe 200
            val traceId = response.traceId()
            wireMock.paths.forEach { (dependency, path) ->
                withClue("$dependency 요청의 traceparent traceId") {
                    wireMock.requestsTo(path).map { it.traceparentTraceId() } shouldBe listOf(traceId)
                }
            }
        }

        test(
            "F-001 Semaphore 모드의 동시 호출 상한: recommendation 동시 상한이 2이고 1초 늦을 때 피드 5개를 동시에 보내면 recommendation은 2개만 받고 bulkhead_full 실패 로그가 3개다",
        ) {
            wireMock.stubRecommendations(delayMs = 1_000)
            val client = FeedHttpClient(localPort())

            LogCapture().use { capture ->
                Executors.newFixedThreadPool(5).use { pool ->
                    (1..5).map { pool.submit(Callable { client.getFeed() }) }.forEach { it.get() }
                }

                wireMock.requestsTo("/recommendations") shouldHaveSize 2
                eventually(5.seconds) {
                    withClue("recommendation의 bulkhead_full downstream_call_failed 줄") {
                        capture
                            .jsonLines()
                            .filter {
                                it["event"] == "downstream_call_failed" &&
                                    it["dependency"] == "recommendation" &&
                                    it["cause"] == "bulkhead_full"
                            }.shouldHaveSize(3)
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
