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
 * `call-isolation` 델타의 "전파가 없으면 끊긴다" (F-001, F-004, design 결정 8).
 *
 * 벌크헤드 스레드로 넘어갈 때 트레이스 문맥을 넘기는 것은 `resilience4j.thread-pool-bulkhead.configs.default.context-propagators`에
 * 등록한 전파기다. 이 테스트는 그 목록을 빈 값으로 덮어써 전파를 끈다. 벌크헤드 스레드에는 요청의 관찰이 없어 RestClient가 새 trace를
 * 시작하므로, 하위 서비스가 받은 `traceparent`의 traceId가 응답의 `X-Trace-Id`와 다르다.
 * 전파를 붙인 뒤에도 속성으로 끄므로 이 테스트는 그대로 통과하며 "전파를 끄면 끊긴다"는 기록으로 남는다([FeedTracePropagationOffTest]와 같은 방식).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["resilience4j.thread-pool-bulkhead.configs.default.context-propagators="],
)
class FeedBulkheadTracePropagationOffTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        suspend fun feed(): TracedFeedHttp {
            val environment = testContextManager().testContext.applicationContext.environment
            return TracedFeedHttp(environment.getRequiredProperty("local.server.port").toInt())
        }

        beforeEach { wireMock.reset() }

        test("F-001 F-004 전파가 없으면 끊긴다: 벌크헤드 전파기를 비우면 하위 서비스 다섯 요청의 traceparent traceId가 X-Trace-Id와 다르다") {
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
