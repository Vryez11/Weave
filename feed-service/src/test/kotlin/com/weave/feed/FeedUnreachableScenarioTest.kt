package com.weave.feed

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `feed-aggregation` 스펙의 "하위 서비스 하나에 연결할 수 없다" 시나리오 (F-006).
 *
 * recommendation 주소만 아무것도 떠 있지 않은 포트로 주고, 나머지는 WireMock이 정상으로 응답한다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedUnreachableScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach { wireMock.reset() }

        test("F-006 하위 서비스 하나에 연결할 수 없다: recommendation 주소에 아무것도 없으면 피드는 500이다") {
            wireMock.stubPosts(listOf(DownstreamWireMock.Post(id = 10, authorId = 3)))
            wireMock.stubUsers(listOf(3))
            wireMock.stubLikes(listOf(DownstreamWireMock.LikeState(postId = 10, likeCount = 0, liked = false)))
            wireMock.stubAds()

            val port =
                testContextManager()
                    .testContext.applicationContext.environment
                    .getRequiredProperty("local.server.port")
                    .toInt()
            val response = FeedHttpClient(port).getFeed()

            response.status shouldBe 500
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) =
            DownstreamWireMock.registerBaseUrls(registry, closed = setOf("recommendation"))
    }
}
