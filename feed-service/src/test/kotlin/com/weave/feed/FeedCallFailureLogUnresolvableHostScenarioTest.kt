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
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.time.Duration.Companion.seconds

/**
 * `feed-aggregation` 델타의 "하위 서비스의 호스트 이름을 찾을 수 없다" 실패 로그 시나리오 (F-004).
 *
 * recommendation 주소만 [UNRESOLVABLE_URL]로 준다. `.invalid`는 RFC 2606이 예약한 최상위 도메인이라 어느 DNS에서도 풀리지 않는다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedCallFailureLogUnresolvableHostScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach { wireMock.reset() }

        test("F-004 하위 서비스의 호스트 이름을 찾을 수 없다: recommendation 호스트가 weave-unresolvable.invalid면 recommendation connect_failure 실패 로그가 남는다") {
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3)))
            wireMock.stubUsers(listOf(3))
            wireMock.stubLikes(listOf(LikeState(postId = 10, likeCount = 0, liked = false)))
            wireMock.stubAds()

            LogCapture().use { capture ->
                val environment = testContextManager().testContext.applicationContext.environment
                val traceId = TracedFeedHttp(environment.getRequiredProperty("local.server.port").toInt()).getFeed().traceId()

                eventually(5.seconds) {
                    withClue("traceId가 X-Trace-Id와 같은 recommendation의 connect_failure downstream_call_failed 줄") {
                        capture
                            .jsonLines()
                            .filter {
                                it["event"] == "downstream_call_failed" &&
                                    it["dependency"] == "recommendation" &&
                                    it["traceId"] == traceId &&
                                    it["cause"] == "connect_failure"
                            }.shouldNotBeEmpty()
                    }
                }
            }
        }
    }) {
    companion object {
        private const val UNRESOLVABLE_URL = "http://weave-unresolvable.invalid"

        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) {
            DownstreamWireMock.dependencies.forEach { dependency ->
                registry.add("spring.http.serviceclient.$dependency.base-url") {
                    if (dependency == "recommendation") UNRESOLVABLE_URL else DownstreamWireMock.server.baseUrl()
                }
            }
        }
    }
}
