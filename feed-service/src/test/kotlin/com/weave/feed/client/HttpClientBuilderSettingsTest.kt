package com.weave.feed.client

import com.weave.feed.HttpClientPools
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder
import java.time.Duration

/** `HttpClientConfig`의 요청 팩토리 빌더가 `weave.http-client.*` 값을 HttpClient에 넣는다 (F-001, add-call-isolation design 결정 3, 5). */
class HttpClientBuilderSettingsTest :
    FunSpec({
        fun HttpComponentsClientHttpRequestFactoryBuilder.httpClient(): CloseableHttpClient =
            HttpClientPools.findAll(build(HttpClientSettings.defaults()), CloseableHttpClient::class.java).single()

        fun CloseableHttpClient.connectionManager(): PoolingHttpClientConnectionManager = HttpClientPools.connectionManagerOf(this)

        test("F-001 커넥션 대기 상한이 HttpClient 기본 요청 설정에 들어간다") {
            val builder =
                HttpClientConfig().clientHttpRequestFactoryBuilder(
                    HttpClientProperties(connectionRequestTimeout = Duration.ofMillis(123)),
                )

            val requestConfigs = HttpClientPools.findAll(builder.httpClient(), RequestConfig::class.java)

            requestConfigs.shouldNotBeEmpty()
            requestConfigs.forEach { it.connectionRequestTimeout.toMilliseconds() shouldBe 123 }
        }

        test("F-001 그룹 풀 크기를 뒤에 더하면 모든 그룹의 풀 크기를 덮는다") {
            val builder = HttpClientConfig().clientHttpRequestFactoryBuilder(HttpClientProperties(maxConnectionsPerRoute = 7))

            val shared = builder.httpClient().connectionManager()
            val overridden = builder.withMaxConnections(9).httpClient().connectionManager()

            (shared.defaultMaxPerRoute to shared.maxTotal) shouldBe (7 to 7)
            (overridden.defaultMaxPerRoute to overridden.maxTotal) shouldBe (9 to 9)
        }

        test("F-001 값이 없는 그룹은 덮어쓰지 않는다") {
            val properties = HttpClientProperties(groups = mapOf("like" to HttpClientProperties.Group(maxConnections = 50)))

            properties.maxConnectionsOverrideOf("like") shouldBe 50
            properties.maxConnectionsOverrideOf("post") shouldBe null
        }
    })
