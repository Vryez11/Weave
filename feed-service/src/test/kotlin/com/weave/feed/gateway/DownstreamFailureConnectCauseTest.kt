package com.weave.feed.gateway

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.hc.client5.http.ConnectTimeoutException
import org.apache.hc.client5.http.HttpRoute
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.ConnectionRequestTimeoutException
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.CompletionException

/** design 결정 6의 표를 넓힌 `connect_failure`와, 커넥션 풀에서 연결을 얻지 못한 경우의 값 (작업 6.3). */
class DownstreamFailureConnectCauseTest :
    FunSpec({
        test("F-004 원인에 NoRouteToHostException, UnknownHostException, HttpClient 5 ConnectTimeoutException이 있으면 connect_failure다") {
            listOf(
                NoRouteToHostException("No route to host"),
                UnknownHostException("weave-unresolvable.invalid"),
                ConnectTimeoutException("Connect to http://10.255.255.1:80 failed: Connect timed out"),
            ).forEach { inner ->
                val error = ResourceAccessException("I/O error", IOException("wrapped", inner))

                DownstreamFailure.of(CompletionException(error)) shouldBe DownstreamFailure("connect_failure", inner.javaClass.name)
            }
        }

        test("F-004 커넥션 풀에서 연결을 얻지 못해 끝난 호출은 connect_failure가 아니라 io_failure다") {
            val connectionManager =
                PoolingHttpClientConnectionManagerBuilder
                    .create()
                    .setMaxConnTotal(1)
                    .setMaxConnPerRoute(1)
                    .build()
            val held =
                connectionManager
                    .lease("held", HttpRoute(HttpHost("http", "localhost", 1)), Timeout.ofSeconds(1), null)
                    .get(Timeout.ofSeconds(1))
            val httpClient =
                HttpClients
                    .custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(100)).build())
                    .build()
            val restClient =
                RestClient
                    .builder()
                    .requestFactory(HttpComponentsClientHttpRequestFactory(httpClient))
                    .baseUrl("http://localhost:1")
                    .build()

            try {
                val error =
                    shouldThrow<ResourceAccessException> {
                        restClient
                            .get()
                            .uri("/posts")
                            .retrieve()
                            .toBodilessEntity()
                    }

                DownstreamFailure.of(CompletionException(error)) shouldBe
                    DownstreamFailure("io_failure", ConnectionRequestTimeoutException::class.java.name)
            } finally {
                connectionManager.release(held, null, TimeValue.ZERO_MILLISECONDS)
                httpClient.close()
            }
        }
    })
