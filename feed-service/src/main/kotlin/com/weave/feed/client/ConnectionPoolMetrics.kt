package com.weave.feed.client

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.http.client.autoconfigure.HttpClientSettingsPropertyMapper
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties
import org.springframework.boot.ssl.SslBundles
import org.springframework.core.Ordered
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer
import org.springframework.web.service.registry.HttpServiceGroupConfigurer

/**
 * 그룹마다의 커넥션 풀 지표를 그룹 이름(`httpclient="post"` 등)으로 내보낸다 (add-fault-experiments design 결정 4).
 *
 * Boot는 그룹마다 만든 커넥션 매니저를 넘겨주지 않는다. 그래서 Boot의 configurer(`HIGHEST_PRECEDENCE + 10`) 뒤에서
 * Boot와 같은 방법(그룹 속성 → [HttpClientSettingsPropertyMapper] → [ClientHttpRequestFactoryBuilder] Bean)으로 요청 팩토리를
 * 다시 만들어 넣고, 그 HttpClient의 내부 필드 `InternalHttpClient.connManager`에서 매니저를 읽어 바인더를 묶는다.
 * 재시도 끔, 풀 크기, 타임아웃은 Bean과 Boot 설정 그대로다. Boot가 먼저 만든 팩토리는 연결을 연 적이 없어 버려도 남는 자원이 없다.
 *
 * 매니저를 못 읽으면 시작할 때 예외로 멈춘다. 지표 없이 조용히 뜨지 않게, httpclient5를 올려 내부가 바뀌면 바로 드러나게 한다.
 */
class ConnectionPoolMetrics(
    private val properties: HttpServiceClientProperties,
    sslBundles: ObjectProvider<SslBundles>,
    httpClientSettings: ObjectProvider<HttpClientSettings>,
    private val requestFactoryBuilder: ClientHttpRequestFactoryBuilder<*>,
    private val meterRegistry: ObjectProvider<MeterRegistry>,
) : RestClientHttpServiceGroupConfigurer {
    private val settingsMapper = HttpClientSettingsPropertyMapper(sslBundles.getIfAvailable(), httpClientSettings.getIfAvailable())

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 20

    override fun configureGroups(groups: HttpServiceGroupConfigurer.Groups<RestClient.Builder>) {
        groups.forEachClient { group, builder ->
            val settings = settingsMapper.map(properties.get(group.name()))
            val requestFactory = requestFactoryBuilder.build(settings)
            builder.requestFactory(requestFactory)
            val manager = connectionManagerOf(group.name(), requestFactory)
            meterRegistry.ifAvailable { PoolingHttpClientConnectionManagerMetricsBinder(manager, group.name()).bindTo(it) }
        }
    }

    private fun connectionManagerOf(
        group: String,
        requestFactory: Any,
    ): PoolingHttpClientConnectionManager {
        val httpClient =
            (requestFactory as? HttpComponentsClientHttpRequestFactory)
                ?.httpClient
                ?.takeIf { it.javaClass.name == INTERNAL_HTTP_CLIENT }
                ?: error(
                    "$group 그룹의 커넥션 풀 지표를 묶지 못했다: 요청 팩토리가 HttpClient 5($INTERNAL_HTTP_CLIENT)가 아니다 " +
                        "(${requestFactory.javaClass.name}). HttpClientConfig.clientHttpRequestFactoryBuilder를 확인한다",
                )
        // InternalHttpClient는 package-private이라 클래스 이름으로 확인하고 필드를 읽는다.
        val field =
            runCatching { httpClient.javaClass.getDeclaredField(CONNECTION_MANAGER_FIELD).apply { isAccessible = true } }
                .getOrElse {
                    error(
                        "$group 그룹의 커넥션 풀 지표를 묶지 못했다: $INTERNAL_HTTP_CLIENT.$CONNECTION_MANAGER_FIELD 필드를 읽을 수 없다 ($it). " +
                            "httpclient5 버전이 바뀌었으면 design 결정 4의 매니저 얻기를 다시 본다",
                    )
                }
        return field.get(httpClient) as? PoolingHttpClientConnectionManager
            ?: error(
                "$group 그룹의 커넥션 풀 지표를 묶지 못했다: 커넥션 매니저가 PoolingHttpClientConnectionManager가 아니다 " +
                    "(${field.get(httpClient)?.javaClass?.name})",
            )
    }

    private companion object {
        const val INTERNAL_HTTP_CLIENT = "org.apache.hc.client5.http.impl.classic.InternalHttpClient"
        const val CONNECTION_MANAGER_FIELD = "connManager"
    }
}
