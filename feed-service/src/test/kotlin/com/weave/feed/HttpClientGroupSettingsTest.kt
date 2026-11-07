package com.weave.feed

import com.weave.feed.client.ad.AdClient
import com.weave.feed.client.like.LikeClient
import com.weave.feed.client.post.PostClient
import com.weave.feed.client.recommendation.RecommendationClient
import com.weave.feed.client.user.UserClient
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.io.SocketConfig
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.test.context.SpringBootTest

/**
 * 그룹마다의 연결·응답 타임아웃(Boot 그룹 설정), 커넥션 대기 상한, 풀 크기 덮어쓰기가 그 그룹의 HttpClient에 들어간다
 * (F-001, add-call-isolation design 결정 3, 5).
 *
 * 그룹마다 값을 다르게 줘 다른 그룹의 값이 섞이지 않는지 본다. 테스트 기본값(`config/application.yml`의 2초)은 속성이 덮는다.
 */
@Tags("Integration")
@SpringBootTest(
    properties = [
        "spring.http.serviceclient.post.connect-timeout=110ms",
        "spring.http.serviceclient.post.read-timeout=120ms",
        "spring.http.serviceclient.user.connect-timeout=130ms",
        "spring.http.serviceclient.user.read-timeout=140ms",
        "spring.http.serviceclient.like.connect-timeout=150ms",
        "spring.http.serviceclient.like.read-timeout=160ms",
        "spring.http.serviceclient.recommendation.connect-timeout=210ms",
        "spring.http.serviceclient.recommendation.read-timeout=220ms",
        "spring.http.serviceclient.ad.connect-timeout=230ms",
        "spring.http.serviceclient.ad.read-timeout=240ms",
        "weave.http-client.connection-request-timeout=123ms",
        "weave.http-client.max-connections-per-route=7",
        "weave.http-client.groups.like.max-connections=9",
        "weave.http-client.groups.recommendation.max-connections=11",
    ],
)
class HttpClientGroupSettingsTest :
    FunSpec({
        extensions(SpringExtension())

        // 의존성 → (연결 타임아웃, 응답 타임아웃) ms
        val timeouts =
            mapOf(
                "post" to (110L to 120L),
                "user" to (130L to 140L),
                "like" to (150L to 160L),
                "recommendation" to (210L to 220L),
                "ad" to (230L to 240L),
            )
        val clientTypes =
            mapOf(
                "post" to PostClient::class.java,
                "user" to UserClient::class.java,
                "like" to LikeClient::class.java,
                "recommendation" to RecommendationClient::class.java,
                "ad" to AdClient::class.java,
            )

        test("F-001 그룹마다 연결·응답 타임아웃이 그 그룹의 속성 값이다") {
            val context = testContextManager().testContext.applicationContext
            clientTypes.forEach { (dependency, type) ->
                val (connect, read) = timeouts.getValue(dependency)
                val manager = HttpClientPools.connectionManagerOf(context.getBean(type))
                val connectionConfigs = HttpClientPools.findAll(manager, ConnectionConfig::class.java)
                val socketConfigs = HttpClientPools.findAll(manager, SocketConfig::class.java)
                connectionConfigs.shouldNotBeEmpty()
                connectionConfigs.forEach { config ->
                    withClue("$dependency 연결 타임아웃") { config.connectTimeout shouldBe Timeout.ofMilliseconds(connect) }
                    // 연결 단위 소켓 타임아웃이 비어 있으면 소켓 설정의 값이 쓰인다
                    val effective = config.socketTimeout ?: socketConfigs.single().soTimeout
                    withClue("$dependency 응답 타임아웃") { effective shouldBe Timeout.ofMilliseconds(read) }
                }
            }
        }

        test("F-001 커넥션 대기 상한이 모든 그룹에 들어간다") {
            val context = testContextManager().testContext.applicationContext
            clientTypes.forEach { (dependency, type) ->
                val httpClient = HttpClientPools.findAll(context.getBean(type), CloseableHttpClient::class.java).single()
                val requestConfigs = HttpClientPools.findAll(httpClient, RequestConfig::class.java)
                requestConfigs.shouldNotBeEmpty()
                requestConfigs.forEach {
                    withClue("$dependency 커넥션 대기") { it.connectionRequestTimeout shouldBe Timeout.ofMilliseconds(123) }
                }
            }
        }

        test("F-001 그룹별 풀 크기가 있으면 그 그룹만 덮고, 나머지는 모든 그룹의 값이다") {
            connectionManagersByDependency().forEach { (dependency, manager) ->
                val expected =
                    when (dependency) {
                        "like" -> 9
                        "recommendation" -> 11
                        else -> 7
                    }
                (dependency to manager.defaultMaxPerRoute) shouldBe (dependency to expected)
                (dependency to manager.maxTotal) shouldBe (dependency to expected)
            }
        }
    })
