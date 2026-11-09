package com.weave.feed

import com.weave.feed.client.ad.AdClient
import com.weave.feed.client.like.LikeClient
import com.weave.feed.client.post.PostClient
import com.weave.feed.client.recommendation.RecommendationClient
import com.weave.feed.client.user.UserClient
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.apache.coyote.AbstractProtocol
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.io.SocketConfig
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.tomcat.TomcatWebServer
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationContext
import java.time.Duration

/**
 * 고도화 3의 격리 설정값 (F-001, add-call-isolation design 결정 3~7).
 *
 * `spring.config.location`으로 main의 `application.yml`만 읽는다. 테스트 기본값(`classpath:/config/application.yml`)이 섞이면
 * 실제 값을 볼 수 없어서다. 값을 바꾸면 design의 근거와 함께 이 테스트를 고친다.
 *
 * MVP 값은 `MvpTimeoutDefaultsTest`, `HttpClientPoolCapacityTest`, `TaskExecutorCapacityTest`가 보던 것을 이 테스트가 바꿨다.
 * MVP 값의 기록은 `docs/architecture.md` 4-2의 표다.
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application.yml"],
)
class IsolationSettingsTest :
    FunSpec({
        extensions(SpringExtension())

        // 의존성마다 호출 시간 상한 (design 결정 3)
        val callTimeouts =
            mapOf(
                "post" to Duration.ofMillis(100),
                "user" to Duration.ofMillis(100),
                "like" to Duration.ofMillis(100),
                "recommendation" to Duration.ofMillis(200),
                "ad" to Duration.ofMillis(200),
            )
        val clientTypes =
            mapOf(
                "post" to PostClient::class.java,
                "user" to UserClient::class.java,
                "like" to LikeClient::class.java,
                "recommendation" to RecommendationClient::class.java,
                "ad" to AdClient::class.java,
            )

        suspend fun context(): ApplicationContext = testContextManager().testContext.applicationContext

        context("HTTP 클라이언트 (design 결정 3, 5)") {
            test("F-001 의존성마다 연결·응답 타임아웃이 호출 시간 상한과 같다") {
                val context = context()
                clientTypes.forEach { (dependency, type) ->
                    val expected = Timeout.of(callTimeouts.getValue(dependency))
                    val manager = HttpClientPools.connectionManagerOf(context.getBean(type))
                    val connectionConfigs = HttpClientPools.findAll(manager, ConnectionConfig::class.java)
                    val socketConfigs = HttpClientPools.findAll(manager, SocketConfig::class.java)
                    connectionConfigs.shouldNotBeEmpty()
                    connectionConfigs.forEach { config ->
                        withClue("$dependency 연결 타임아웃") { config.connectTimeout shouldBe expected }
                        // 연결 단위 소켓 타임아웃이 비어 있으면 소켓 설정의 값이 쓰인다
                        val effective = config.socketTimeout ?: socketConfigs.single().soTimeout
                        withClue("$dependency 응답 타임아웃") { effective shouldBe expected }
                    }
                }
            }

            test("F-001 커넥션 풀에서 연결을 기다리는 상한은 모든 의존성에서 100ms다") {
                val context = context()
                clientTypes.forEach { (dependency, type) ->
                    val httpClient = HttpClientPools.findAll(context.getBean(type), CloseableHttpClient::class.java).single()
                    val requestConfigs = HttpClientPools.findAll(httpClient, RequestConfig::class.java)
                    requestConfigs.shouldNotBeEmpty()
                    requestConfigs.forEach {
                        withClue("$dependency 커넥션 대기") { it.connectionRequestTimeout shouldBe Timeout.ofMilliseconds(100) }
                    }
                }
            }

            test("F-001 커넥션 풀은 라우트당·전체 40이고 like만 50이다(읽기 벌크헤드 40 + 쓰기 10)") {
                connectionManagersByDependency().forEach { (dependency, manager) ->
                    val expected = if (dependency == "like") 50 else 40
                    (dependency to manager.defaultMaxPerRoute) shouldBe (dependency to expected)
                    (dependency to manager.maxTotal) shouldBe (dependency to expected)
                }
            }
        }

        context("Resilience4j (design 결정 3, 5, 6, 7)") {
            test("F-001 의존성마다 TimeLimiter 상한이 호출 시간 상한이고, 시간이 지나면 future를 취소한다") {
                val registry = context().getBean(TimeLimiterRegistry::class.java)
                callTimeouts.forEach { (dependency, timeout) ->
                    val config = registry.timeLimiter(dependency).timeLimiterConfig
                    (dependency to config.timeoutDuration) shouldBe (dependency to timeout)
                    (dependency to config.shouldCancelRunningFuture()) shouldBe (dependency to true)
                }
            }

            test("F-001 의존성마다 ThreadPool 벌크헤드가 스레드 40, 대기열 0이고 트레이스 문맥을 넘긴다") {
                val registry = context().getBean(ThreadPoolBulkheadRegistry::class.java)
                callTimeouts.keys.forEach { dependency ->
                    val config = registry.bulkhead(dependency).bulkheadConfig
                    withClue(dependency) {
                        config.coreThreadPoolSize shouldBe 40
                        config.maxThreadPoolSize shouldBe 40
                        config.queueCapacity shouldBe 0
                        config.getContextPropagator().map { it.javaClass.simpleName } shouldContain "TraceContextPropagator"
                    }
                }
            }

            test("F-001 Semaphore 벌크헤드는 의존성마다 40, 쓰기(like-write)는 10이고 모두 기다리지 않는다") {
                val registry = context().getBean(BulkheadRegistry::class.java)
                (callTimeouts.keys.associateWith { 40 } + ("like-write" to 10)).forEach { (name, maxCalls) ->
                    val config = registry.bulkhead(name).bulkheadConfig
                    withClue(name) {
                        config.maxConcurrentCalls shouldBe maxCalls
                        config.maxWaitDuration shouldBe Duration.ZERO
                    }
                }
            }

            test("F-001 의존성마다 서킷이 최근 10초(최소 호출 100개) 중 실패·느린 호출 50% 이상이면 열리고, 5초 뒤 시험 호출 10개로 판단한다") {
                val registry = context().getBean(CircuitBreakerRegistry::class.java)
                callTimeouts.forEach { (dependency, timeout) ->
                    val config = registry.circuitBreaker(dependency).circuitBreakerConfig
                    withClue(dependency) {
                        config.slidingWindowType shouldBe SlidingWindowType.TIME_BASED
                        config.slidingWindowSize shouldBe 10
                        config.minimumNumberOfCalls shouldBe 100
                        config.failureRateThreshold shouldBe 50f
                        config.slowCallDurationThreshold shouldBe timeout.multipliedBy(4).dividedBy(5)
                        config.slowCallRateThreshold shouldBe 50f
                        config.waitIntervalFunctionInOpenState.apply(1) shouldBe 5_000L
                        config.permittedNumberOfCallsInHalfOpenState shouldBe 10
                        config.isAutomaticTransitionFromOpenToHalfOpenEnabled shouldBe true
                    }
                }
            }
        }

        context("피드와 서버 (design 결정 4, 5)") {
            test("F-001 피드 요청의 시간 예산은 250ms다") {
                context().environment.getProperty("weave.feed.time-budget", Duration::class.java) shouldBe Duration.ofMillis(250)
            }

            test("F-001 하위 서비스 호출이 쓰지 않는 공유 풀의 용량 설정이 없다") {
                context().environment.getProperty("spring.task.execution.pool.core-size").shouldBeNull()
            }

            test("F-006 Tomcat: 최대 스레드 200, 최소 여유 스레드 10, 최대 연결 8192, accept 대기열 100이다") {
                val context = context() as WebServerApplicationContext
                val protocol = (context.webServer as TomcatWebServer).tomcat.connector.protocolHandler as AbstractProtocol<*>

                protocol.maxThreads shouldBe 200
                protocol.minSpareThreads shouldBe 10
                protocol.maxConnections shouldBe 8192
                protocol.acceptCount shouldBe 100
            }
        }
    })
