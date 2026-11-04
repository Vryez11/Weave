package com.weave.feed

import com.weave.feed.client.ad.AdClient
import com.weave.feed.client.like.LikeClient
import com.weave.feed.client.post.PostClient
import com.weave.feed.client.recommendation.RecommendationClient
import com.weave.feed.client.user.UserClient
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
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

/**
 * architecture 4-2: MVP는 직접 정한 타임아웃 없이 HttpClient 5와 Tomcat의 기본값을 쓴다 (F-006).
 *
 * 고도화 2 기준선의 전제가 되는 값이다. 라이브러리를 올려 기본값이 바뀌면 이 테스트가 먼저 알려 준다.
 * 커넥션 풀 크기는 [HttpClientPoolCapacityTest], 스레드 풀은 [TaskExecutorCapacityTest]와 [TaskExecutorKeepAliveTest]가 본다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MvpTimeoutDefaultsTest :
    FunSpec({
        extensions(SpringExtension())

        val threeMinutes = Timeout.ofMinutes(3)
        val clientTypes =
            listOf(
                PostClient::class.java,
                UserClient::class.java,
                LikeClient::class.java,
                RecommendationClient::class.java,
                AdClient::class.java,
            )

        test("F-006 HttpClient 5: 요청마다의 응답 타임아웃은 비어 있고, 커넥션 풀 대기는 3분이다") {
            val context = testContextManager().testContext.applicationContext
            clientTypes.forEach { type ->
                val httpClient = HttpClientPools.findAll(context.getBean(type), CloseableHttpClient::class.java).single()
                val requestConfig = HttpClientPools.findAll(httpClient, RequestConfig::class.java)
                requestConfig.shouldNotBeEmpty()
                requestConfig.forEach {
                    it.responseTimeout.shouldBeNull()
                    it.connectionRequestTimeout shouldBe threeMinutes
                }
            }
        }

        test("F-006 HttpClient 5: 연결 타임아웃은 3분이고, 연결 단위 소켓 타임아웃이 비어 있어 소켓 설정의 3분이 쓰인다") {
            val context = testContextManager().testContext.applicationContext
            clientTypes.forEach { type ->
                val manager = HttpClientPools.connectionManagerOf(context.getBean(type))
                val connectionConfigs = HttpClientPools.findAll(manager, ConnectionConfig::class.java)
                connectionConfigs.shouldNotBeEmpty()
                connectionConfigs.forEach {
                    it.connectTimeout shouldBe threeMinutes
                    it.socketTimeout.shouldBeNull()
                }
                val socketConfigs = HttpClientPools.findAll(manager, SocketConfig::class.java)
                socketConfigs.shouldNotBeEmpty()
                socketConfigs.forEach { it.soTimeout shouldBe threeMinutes }
            }
        }

        test("F-006 Tomcat: 최대 스레드 200, 최소 여유 스레드 10, 최대 연결 8192, accept 대기열 100이다") {
            val context = testContextManager().testContext.applicationContext as WebServerApplicationContext
            val protocol = (context.webServer as TomcatWebServer).tomcat.connector.protocolHandler as AbstractProtocol<*>

            protocol.maxThreads shouldBe 200
            protocol.minSpareThreads shouldBe 10
            protocol.maxConnections shouldBe 8192
            protocol.acceptCount shouldBe 100
        }
    })
