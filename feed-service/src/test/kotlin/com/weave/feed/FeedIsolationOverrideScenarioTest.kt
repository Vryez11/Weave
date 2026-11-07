package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration

/**
 * `call-isolation` 델타의 "격리 설정값은 설정으로 바꾼다" 중 "환경 변수로 상한을 바꾼다" (F-001, design 결정 12).
 *
 * **테스트에서 환경 변수를 주는 방법:** JVM의 실제 환경 변수는 테스트 안에서 바꿀 수 없다. 대신 [EnvironmentOverride]가 컨텍스트를
 * 새로 고치기 전에 [SystemEnvironmentPropertySource]를 환경의 맨 앞에 붙인다. 이름을 `...-systemEnvironment`로 끝나게 둬야 Boot가
 * 실제 시스템 환경과 같은 규칙(`RESILIENCE4J_TIMELIMITER_INSTANCES_RECOMMENDATION_TIMEOUTDURATION` → `resilience4j.timelimiter.
 * instances.recommendation.timeout-duration`)으로 읽는다(`SpringConfigurationPropertySource`). 맨 앞이라 아래의 파일 값보다 우선한다.
 *
 * 설정 파일의 recommendation 상한은 `@SpringBootTest(properties)`로 200ms를 준다(main의 값과 같다. 테스트 기본값 2초로 두면 500ms 지연이
 * 덮어쓰기 없이도 상한 안이라 시나리오가 아무것도 가르지 못한다). 환경 변수가 바인딩되지 않으면 500ms 지연이 200ms 상한에 걸려 피드가 500이다.
 * 바인딩 결과는 [TimeLimiterRegistry]의 인스턴스 설정으로도 본다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["resilience4j.timelimiter.instances.recommendation.timeout-duration=200ms"],
)
@ContextConfiguration(initializers = [FeedIsolationOverrideScenarioTest.EnvironmentOverride::class])
class FeedIsolationOverrideScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock

        beforeEach { wireMock.reset() }

        test("F-001 환경 변수로 상한을 바꾼다: recommendation 상한을 환경 변수로 1,000ms로 바꿔 띄우고 recommendation이 500ms 늦으면 200과 함께 추천이 채워진다") {
            val context = testContextManager().testContext.applicationContext
            withClue("환경 변수로 준 recommendation TimeLimiter 상한") {
                context
                    .getBean(TimeLimiterRegistry::class.java)
                    .timeLimiter("recommendation")
                    .timeLimiterConfig.timeoutDuration shouldBe Duration.ofMillis(1_000)
            }
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3)))
            wireMock.stubUsers(listOf(3))
            wireMock.stubLikes(listOf(LikeState(postId = 10, likeCount = 1, liked = false)))
            wireMock.stubRecommendations(delayMs = 500)
            wireMock.stubAds()

            val response = FeedHttpClient(context.environment.getRequiredProperty("local.server.port").toInt()).getFeed()

            response.status shouldBe 200
            response.json()["recommendations"] shouldBe wireMock.recommendations
        }
    }) {
    /** 환경 변수 형식의 이름을 실제 시스템 환경처럼 읽히는 속성 소스로 맨 앞에 붙인다. */
    class EnvironmentOverride : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            context.environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    "test-systemEnvironment",
                    mapOf<String, Any>("RESILIENCE4J_TIMELIMITER_INSTANCES_RECOMMENDATION_TIMEOUTDURATION" to "1000ms"),
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
