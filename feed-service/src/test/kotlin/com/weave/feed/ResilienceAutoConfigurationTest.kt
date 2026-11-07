package com.weave.feed

import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resilience4j 스타터(`resilience4j-spring-boot4` 2.4.0)가 Boot 4.1.2에서 되는지 (F-001, add-call-isolation design 결정 2).
 *
 * 레지스트리 네 개가 `resilience4j.*` 설정의 `instances.<이름>`으로 인스턴스를 미리 만드는지(호출 전에 `find`로 찾는다),
 * `/actuator/prometheus`에 그 인스턴스의 지표가 `name` 라벨로 나오는지 본다. 컨텍스트가 뜨는 것이 Boot 버전 검사
 * (`SpringBoot4VerifierAutoConfiguration`)가 시작을 막지 않는다는 확인이다.
 *
 * 값은 main 설정과 겹치지 않게 일부러 다른 숫자(창 7, 스레드 3, 동시 4·2, 상한 1.5초)를 준다. 그래야 그 값이 설정에서 왔다는 것이 보인다.
 *
 * ThreadPool 벌크헤드에는 거절 카운터 지표가 없다. 대신 `onCallRejected` 이벤트가 오는 것을 보고, feed-service가 그 이벤트로
 * `weave.bulkhead.rejected.calls`를 센다(design Open Questions, tasks 2.4).
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "resilience4j.circuitbreaker.instances.post.sliding-window-size=7",
        "resilience4j.circuitbreaker.instances.user.sliding-window-size=7",
        "resilience4j.circuitbreaker.instances.like.sliding-window-size=7",
        "resilience4j.circuitbreaker.instances.recommendation.sliding-window-size=7",
        "resilience4j.circuitbreaker.instances.ad.sliding-window-size=7",
        "resilience4j.thread-pool-bulkhead.instances.post.core-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.post.max-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.post.queue-capacity=0",
        "resilience4j.thread-pool-bulkhead.instances.user.core-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.user.max-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.user.queue-capacity=0",
        "resilience4j.thread-pool-bulkhead.instances.like.core-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.like.max-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.like.queue-capacity=0",
        "resilience4j.thread-pool-bulkhead.instances.recommendation.core-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.recommendation.max-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.recommendation.queue-capacity=0",
        "resilience4j.thread-pool-bulkhead.instances.ad.core-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.ad.max-thread-pool-size=3",
        "resilience4j.thread-pool-bulkhead.instances.ad.queue-capacity=0",
        "resilience4j.bulkhead.instances.post.max-concurrent-calls=4",
        "resilience4j.bulkhead.instances.user.max-concurrent-calls=4",
        "resilience4j.bulkhead.instances.like.max-concurrent-calls=4",
        "resilience4j.bulkhead.instances.recommendation.max-concurrent-calls=4",
        "resilience4j.bulkhead.instances.ad.max-concurrent-calls=4",
        "resilience4j.bulkhead.instances.like-toggle.max-concurrent-calls=2",
        "resilience4j.bulkhead.instances.like-toggle.max-wait-duration=0",
        "resilience4j.timelimiter.instances.post.timeout-duration=1500ms",
        "resilience4j.timelimiter.instances.user.timeout-duration=1500ms",
        "resilience4j.timelimiter.instances.like.timeout-duration=1500ms",
        "resilience4j.timelimiter.instances.recommendation.timeout-duration=1500ms",
        "resilience4j.timelimiter.instances.ad.timeout-duration=1500ms",
    ],
)
class ResilienceAutoConfigurationTest :
    FunSpec({
        extensions(SpringExtension())

        val dependencies = DownstreamWireMock.dependencies

        suspend fun context(): ApplicationContext = testContextManager().testContext.applicationContext

        suspend fun prometheus(): String {
            val port = context().environment.getRequiredProperty("local.server.port").toInt()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            return response.body()
        }

        /** [body]에서 [prefix]로 시작하는 표본 줄 중 `name="[name]"` 라벨을 가진 것의 지표 이름. */
        fun metricNames(
            body: String,
            prefix: String,
            name: String,
        ): Set<String> =
            body
                .lineSequence()
                .filter { it.startsWith(prefix) && "name=\"$name\"" in it }
                .map { it.substringBefore('{') }
                .toSet()

        context("레지스트리 (design 결정 2)") {
            test("F-001 서킷 레지스트리가 설정의 의존성 다섯마다 서킷을 미리 만든다") {
                val registry = context().getBean(CircuitBreakerRegistry::class.java)
                dependencies.forEach { dependency ->
                    withClue(dependency) {
                        registry.find(dependency).map { it.circuitBreakerConfig.slidingWindowSize }.orElse(null) shouldBe 7
                    }
                }
            }

            test("F-001 ThreadPool 벌크헤드 레지스트리가 설정의 의존성 다섯마다 벌크헤드를 미리 만든다") {
                val registry = context().getBean(ThreadPoolBulkheadRegistry::class.java)
                dependencies.forEach { dependency ->
                    withClue(dependency) {
                        val config = registry.find(dependency).map { it.bulkheadConfig }.orElse(null)
                        config?.coreThreadPoolSize shouldBe 3
                        config?.maxThreadPoolSize shouldBe 3
                        config?.queueCapacity shouldBe 0
                    }
                }
            }

            test("F-001 Semaphore 벌크헤드 레지스트리가 설정의 의존성 다섯과 like-toggle마다 벌크헤드를 미리 만든다") {
                val registry = context().getBean(BulkheadRegistry::class.java)
                (dependencies.associateWith { 4 } + ("like-toggle" to 2)).forEach { (name, maxCalls) ->
                    withClue(name) {
                        val config = registry.find(name).map { it.bulkheadConfig }.orElse(null)
                        config?.maxConcurrentCalls shouldBe maxCalls
                    }
                }
                registry
                    .find("like-toggle")
                    .get()
                    .bulkheadConfig.maxWaitDuration shouldBe Duration.ZERO
            }

            test("F-001 TimeLimiter 레지스트리가 설정의 의존성 다섯마다 TimeLimiter를 미리 만든다") {
                val registry = context().getBean(TimeLimiterRegistry::class.java)
                dependencies.forEach { dependency ->
                    withClue(dependency) {
                        registry.find(dependency).map { it.timeLimiterConfig.timeoutDuration }.orElse(null) shouldBe
                            Duration.ofMillis(1_500)
                    }
                }
            }
        }

        context("Prometheus 지표 (design 결정 2, 9)") {
            test("F-001 의존성 다섯마다 서킷 상태와 막은 호출 수 지표가 name 라벨로 있다") {
                val body = prometheus()
                dependencies.forEach { dependency ->
                    withClue("$dependency 서킷 지표") {
                        metricNames(body, "resilience4j_circuitbreaker_", dependency) shouldContainAll
                            setOf("resilience4j_circuitbreaker_state", "resilience4j_circuitbreaker_not_permitted_calls_total")
                    }
                }
            }

            test("F-001 의존성 다섯마다 ThreadPool 벌크헤드의 사용 중·최대 스레드와 대기열 지표가 name 라벨로 있다") {
                val body = prometheus()
                dependencies.forEach { dependency ->
                    withClue("$dependency ThreadPool 벌크헤드 지표") {
                        metricNames(body, "resilience4j_bulkhead_", dependency) shouldContainAll
                            setOf(
                                "resilience4j_bulkhead_active_thread_count",
                                "resilience4j_bulkhead_thread_pool_size",
                                "resilience4j_bulkhead_max_thread_pool_size",
                                "resilience4j_bulkhead_queue_depth",
                                "resilience4j_bulkhead_queue_capacity",
                            )
                    }
                }
            }

            test("F-001 의존성 다섯과 like-toggle마다 Semaphore 벌크헤드의 남은 자리와 최대 동시 호출 지표가 name 라벨로 있다") {
                val body = prometheus()
                (dependencies + "like-toggle").forEach { name ->
                    withClue("$name Semaphore 벌크헤드 지표") {
                        metricNames(body, "resilience4j_bulkhead_", name) shouldContainAll
                            setOf("resilience4j_bulkhead_available_concurrent_calls", "resilience4j_bulkhead_max_allowed_concurrent_calls")
                    }
                }
            }

            test("F-001 의존성 다섯마다 시간 상한 초과 수 지표가 호출 전에도 0으로 있다") {
                val body = prometheus()
                dependencies.forEach { dependency ->
                    withClue("$dependency TimeLimiter 지표") {
                        body
                            .lineSequence()
                            .filter {
                                it.startsWith("resilience4j_timelimiter_calls_total{") &&
                                    "name=\"$dependency\"" in it &&
                                    "kind=\"timeout\"" in it
                            }.map { it.substringAfterLast(' ').toDouble() }
                            .toList() shouldBe listOf(0.0)
                    }
                }
            }

            test("F-001 벌크헤드 거절 카운터는 Resilience4j 지표에 없고, ThreadPool 벌크헤드가 차면 onCallRejected 이벤트가 온다") {
                withClue("Resilience4j가 내보내는 거절 지표") {
                    prometheus()
                        .lineSequence()
                        .filter {
                            it.startsWith(
                                "resilience4j_bulkhead_",
                            ) &&
                                "reject" in it
                        }.toList()
                        .shouldBeEmpty()
                }

                val bulkhead = context().getBean(ThreadPoolBulkheadRegistry::class.java).find("recommendation").get()
                val rejected = AtomicInteger()
                bulkhead.eventPublisher.onCallRejected { rejected.incrementAndGet() }
                val release = CountDownLatch(1)
                val started = CountDownLatch(3)
                val running =
                    (1..3).map {
                        bulkhead.submit {
                            started.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                    }
                try {
                    started.await(5, TimeUnit.SECONDS) shouldBe true
                    shouldThrow<BulkheadFullException> { bulkhead.submit { } }
                    rejected.get() shouldBe 1
                } finally {
                    release.countDown()
                    running.forEach { it.toCompletableFuture().get(5, TimeUnit.SECONDS) }
                }
            }
        }

        test("F-001 Boot 버전 검사 자동 설정이 들어 있고 시작을 막지 않는다") {
            context().beanDefinitionNames.filter { "SpringBoot4Verifier" in it }.shouldNotBeEmpty()
        }
    })
