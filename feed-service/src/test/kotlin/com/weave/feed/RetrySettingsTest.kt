package com.weave.feed

import com.weave.feed.gateway.RetryAfterProperties
import com.weave.feed.gateway.RetryProperties
import io.github.resilience4j.core.functions.Either
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import java.time.Duration

/**
 * 고도화 5의 재시도·외부 API 설정값 (F-005, add-retry-policy design 결정 11).
 *
 * [IsolationSettingsTest]처럼 `spring.config.location`으로 main의 `application.yml`만 읽는다. 테스트 기본 설정은 재시도를 끄기 때문에
 * (design 결정 12) 섞이면 실제 값을 볼 수 없다. 값을 바꾸면 design의 근거와 함께 이 테스트를 고친다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application.yml"],
)
class RetrySettingsTest :
    FunSpec({
        extensions(SpringExtension())

        suspend fun context(): ApplicationContext = testContextManager().testContext.applicationContext

        val retried = listOf("post", "user", "like", "recommendation")

        context("재시도 횟수·간격·jitter (tasks 4.1)") {
            test("F-005 post·user·like·recommendation은 최대 3번 시도하고, ad는 재시도 인스턴스가 없다") {
                val registry = context().getBean(RetryRegistry::class.java)
                retried.forEach { dependency ->
                    val retry = withClue("$dependency 재시도 인스턴스") { registry.find(dependency).orElseThrow() }
                    withClue("$dependency 최대 시도 횟수") { retry.retryConfig.maxAttempts shouldBe 3 }
                }
                withClue("ad 재시도 인스턴스") { registry.find("ad").isPresent shouldBe false }
            }

            test("F-005 첫 재시도 전에는 10ms±50%, 둘째 재시도 전에는 20ms±50%를 기다린다(지수 backoff ×2, jitter 0.5)") {
                val registry = context().getBean(RetryRegistry::class.java)
                retried.forEach { dependency ->
                    val interval =
                        registry
                            .find(dependency)
                            .orElseThrow()
                            .retryConfig
                            .getIntervalBiFunction<Any>()
                    val failure = Either.left<Throwable, Any>(RuntimeException("시험"))
                    repeat(200) {
                        withClue("$dependency 첫 재시도 전 기다림") { interval.apply(1, failure) shouldBeInRange 5L..15L }
                        withClue("$dependency 둘째 재시도 전 기다림") { interval.apply(2, failure) shouldBeInRange 10L..30L }
                    }
                }
            }
        }

        context("재시도 예산 (tasks 4.2)") {
            test("F-005 재시도 예산은 최근 1초 원래 호출의 10%이고 창마다 최소 1번이다") {
                val budget = context().getBean(RetryProperties::class.java).budget
                budget.ratio shouldBe 0.1
                budget.window shouldBe Duration.ofSeconds(1)
                budget.minRetries shouldBe 1
            }
        }

        context("ad 요청 수 상한 (tasks 4.3)") {
            test("F-005 ad는 100ms마다 54개까지 부르고 허용을 기다리지 않는다. 다른 의존성에는 상한이 없다") {
                val registry = context().getBean(RateLimiterRegistry::class.java)
                val config = registry.find("ad").orElseThrow().rateLimiterConfig
                config.limitRefreshPeriod shouldBe Duration.ofMillis(100)
                config.limitForPeriod shouldBe 54
                config.timeoutDuration shouldBe Duration.ZERO
                retried.forEach { dependency ->
                    withClue("$dependency 요청 수 상한") { registry.find(dependency).isPresent shouldBe false }
                }
            }
        }

        context("Retry-After 대기 (tasks 4.4)") {
            test("F-005 Retry-After가 없거나 읽지 못하면 1초, 길어도 60초까지만 부르지 않는다") {
                val properties = context().getBean(RetryAfterProperties::class.java)
                properties.defaultHold shouldBe Duration.ofSeconds(1)
                properties.maxHold shouldBe Duration.ofSeconds(60)
            }
        }
    })
