package com.weave.feed

import com.weave.feed.DownstreamWireMock.Post
import io.github.resilience4j.retry.RetryRegistry
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `retry-policy` 스펙 "재시도와 외부 API 설정값은 설정으로 바꾼다"의 "환경 변수로 최대 시도 횟수를 바꾼다" (F-005).
 *
 * 환경 변수는 [FeedIsolationOverrideScenarioTest]와 같은 방법으로 준다. [EnvironmentOverride]가 컨텍스트를 새로 고치기 전에
 * `...-systemEnvironment`로 끝나는 [SystemEnvironmentPropertySource]를 환경의 맨 앞에 붙이면, Boot가 실제 시스템 환경과 같은 규칙
 * (`RESILIENCE4J_RETRY_INSTANCES_LIKE_MAXATTEMPTS` → `resilience4j.retry.instances.like.max-attempts`)으로 읽고 파일 값보다 우선한다.
 *
 * 테스트 기본 설정은 like 최대 시도 횟수를 1로 둔다(재시도 끔, add-retry-policy design 결정 12). 환경 변수가 바인딩되지 않으면 like-service는
 * 좋아요 조회를 한 번만 받는다. 간격은 `@SpringBootTest(properties)`로 10ms, 재시도 예산은 시나리오가 닿지 않게 넉넉히 준다. 바인딩 결과는
 * [RetryRegistry]의 인스턴스 설정으로도 본다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.retry.instances.like.wait-duration=10ms",
        "weave.retry.budget.ratio=1",
        "weave.retry.budget.min-retries=1000",
        "weave.retry.budget.window=10s",
    ],
)
@ContextConfiguration(initializers = [FeedRetryOverrideScenarioTest.EnvironmentOverride::class])
class FeedRetryOverrideScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach { wireMock.reset() }

        test("F-005 환경 변수로 최대 시도 횟수를 바꾼다: like 최대 시도 횟수를 환경 변수로 2로 바꿔 띄우고 like-service가 늘 503이면 좋아요 조회를 두 번 받는다") {
            val context = testContextManager().testContext.applicationContext
            withClue("환경 변수로 준 like 재시도 최대 시도 횟수") {
                context
                    .getBean(RetryRegistry::class.java)
                    .retry("like")
                    .retryConfig.maxAttempts shouldBe 2
            }
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3)))
            wireMock.stubUsers(listOf(3))
            wireMock.stubFailure("like", 503)
            wireMock.stubRecommendations()
            wireMock.stubAds()

            val response = FeedHttpClient(context.environment.getRequiredProperty("local.server.port").toInt()).getFeed()

            response.status shouldBe 200
            withClue("like-service가 받은 좋아요 조회") { wireMock.requestsTo("/likes") shouldHaveSize 2 }
        }
    }) {
    /** 환경 변수 형식의 이름을 실제 시스템 환경처럼 읽히는 속성 소스로 맨 앞에 붙인다. */
    class EnvironmentOverride : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            context.environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    "test-systemEnvironment",
                    mapOf<String, Any>("RESILIENCE4J_RETRY_INSTANCES_LIKE_MAXATTEMPTS" to "2"),
                ),
            )
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}
