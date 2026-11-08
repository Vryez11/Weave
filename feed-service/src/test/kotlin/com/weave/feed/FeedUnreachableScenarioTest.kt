package com.weave.feed

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `graceful-degradation` 스펙 "중요·선택 등급의 실패는 피드를 실패시키지 않는다"의 연결 실패 (F-002).
 *
 * recommendation 주소만 아무것도 떠 있지 않은 포트(1번)로 주고, 나머지는 WireMock이 정상으로 응답한다.
 * 고도화 3까지는 `feed-aggregation`의 "하위 서비스 하나에 연결할 수 없다"(피드 500)였다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedUnreachableScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach { wireMock.reset() }

        test("F-002 선택 의존성에 연결할 수 없다: recommendation 주소에 아무것도 없으면 피드는 200이고 recommendation이 빠진다") {
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

            response.status shouldBe 200
            @Suppress("UNCHECKED_CAST")
            (response.json()["degradation"] as Map<String, Any?>)["missing"] shouldBe listOf("recommendation")
            // 실패 원인이 WireMock의 응답이 아니라 연결 거부임을 확인한다
            wireMock.requestsTo("/recommendations").shouldBeEmpty()
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) =
            DownstreamWireMock.registerBaseUrls(registry, closed = setOf("recommendation"))
    }
}
