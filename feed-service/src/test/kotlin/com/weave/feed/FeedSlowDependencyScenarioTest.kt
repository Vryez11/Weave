package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `graceful-degradation` 스펙 "중요·선택 등급의 실패는 피드를 실패시키지 않는다"의 "느린 선택 의존성" (F-002).
 *
 * recommendation의 호출 시간 상한과 응답 타임아웃을 200ms로 준다. 나머지는 테스트 기본값(2초)이고 지연 없이 응답한다.
 * 상한이 달라 같은 요구사항의 다른 시나리오(`FeedDegradationScenarioTest`)와 컨텍스트를 나눈다.
 * MVP에서는 2초를 다 기다린 뒤 추천까지 채운 200이었고(F-006), 고도화 3에서는 400ms 안에 500이었다(F-001).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.timelimiter.instances.recommendation.timeout-duration=200ms",
        "spring.http.serviceclient.recommendation.read-timeout=200ms",
    ],
)
class FeedSlowDependencyScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach { wireMock.reset() }

        test("F-002 느린 선택 의존성: recommendation 상한이 200ms이고 recommendation이 2초 늦으면 400ms 안에 200이 오고 recommendation이 빠진다") {
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3)))
            wireMock.stubUsers(listOf(3))
            wireMock.stubLikes(listOf(LikeState(postId = 10, likeCount = 0, liked = false)))
            wireMock.stubAds()
            wireMock.stubRecommendations()
            val port =
                testContextManager()
                    .testContext.applicationContext.environment
                    .getRequiredProperty("local.server.port")
                    .toInt()
            // 첫 요청의 클래스 로딩과 연결 수립이 측정에 섞이지 않게 지연 없이 한 번 먼저 부른다.
            FeedHttpClient(port).getFeed()
            wireMock.stubRecommendations(delayMs = 2_000)

            val response = FeedHttpClient(port).getFeed()

            response.status shouldBe 200
            response.elapsedMs shouldBeLessThan 400
            @Suppress("UNCHECKED_CAST")
            ((response.json()["degradation"] as Map<String, Any?>)["missing"] as List<String>) shouldContain "recommendation"
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}
