package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 공유 스레드 풀에서 traceId가 끊기는 것을 남기는 재현 테스트 (F-004, add-trace-logging design 결정 4의 2번).
 *
 * `spring.task.execution.propagate-context=false`를 테스트에 명시한다. 풀 스레드에는 요청의 관찰이 넘어가지 않아
 * RestClient가 새 trace를 시작하므로, 하위 서비스가 받은 `traceparent`의 traceId가 응답의 `X-Trace-Id`와 다르다.
 * 라이브러리 기본값을 `true`로 바꾼 뒤에도 속성을 명시하므로 이 테스트는 그대로 통과하며 "전파를 끄면 끊긴다"는 기록으로 남는다.
 *
 * 고도화 3(add-call-isolation)부터 기본 구성의 하위 서비스 조회는 공유 풀이 아니라 의존성별 ThreadPool 벌크헤드에서 돈다.
 * 그 끊김은 [FeedBulkheadTracePropagationOffTest]가 남긴다. 공유 풀(`applicationTaskExecutor`)에서 조회를 돌리는 것은
 * Semaphore 모드(design 결정 11)이므로 이 테스트는 `weave.isolation.bulkhead-type=semaphore`로 띄운다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.task.execution.propagate-context=false",
        "weave.isolation.bulkhead-type=semaphore",
    ],
)
class FeedTracePropagationOffTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed(): TracedFeedHttp {
            val environment = testContextManager().testContext.applicationContext.environment
            return TracedFeedHttp(environment.getRequiredProperty("local.server.port").toInt())
        }

        beforeEach { wireMock.reset() }

        test("F-004 F-001 전파를 끄면 끊긴다: Semaphore 모드에서 propagate-context=false이면 하위 서비스 다섯 요청의 traceparent traceId가 X-Trace-Id와 다르다") {
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

            val response = feed().getFeed()

            response.statusCode() shouldBe 200
            val traceId = response.traceId()
            wireMock.paths.forEach { (dependency, path) ->
                val requests = wireMock.requestsTo(path)
                requests shouldHaveSize 1
                withClue("$dependency 요청의 traceparent traceId는 있지만 X-Trace-Id와 다르다") {
                    requests.single().traceparentTraceId().shouldNotBeNull() shouldNotBe traceId
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
