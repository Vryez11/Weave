package com.weave.feed.client

import com.weave.feed.client.ad.AdClient
import com.weave.feed.client.like.LikeClient
import com.weave.feed.client.post.PostClient
import com.weave.feed.client.recommendation.RecommendationClient
import com.weave.feed.client.user.UserClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.service.registry.ImportHttpServices

/**
 * 하위 서비스 HTTP 클라이언트 (design 결정 3, 아키텍처 5-1).
 *
 * 의존성마다 HTTP 서비스 그룹을 하나씩 만든다. 그룹 이름은 Resilience4j 인스턴스 이름과 같고,
 * 주소는 `spring.http.serviceclient.<그룹>.base-url`로 준다. Boot가 그룹마다 요청 팩토리를 새로 만들어
 * 그룹마다 HttpClient와 커넥션 풀이 따로 생긴다.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "post", types = [PostClient::class])
@ImportHttpServices(group = "user", types = [UserClient::class])
@ImportHttpServices(group = "like", types = [LikeClient::class])
@ImportHttpServices(group = "recommendation", types = [RecommendationClient::class])
@ImportHttpServices(group = "ad", types = [AdClient::class])
class HttpClientConfig {
    /**
     * HttpClient 5의 자동 재시도는 늘 끈다(design 결정 3). 기본 전략은 429·503에 1초 뒤 한 번 더 보내고 토글 POST도 다시 보낸다.
     * 그룹마다의 커넥션 풀 크기(라우트당 최대, 전체 최대)는 속성이 있을 때만 바꾸고, 없으면 HttpClient 기본값을 쓴다.
     */
    @Bean
    fun clientHttpRequestFactoryBuilder(
        @Value("\${weave.http-client.max-connections-per-route:#{null}}") maxConnectionsPerRoute: Int?,
    ): ClientHttpRequestFactoryBuilder<*> {
        val builder = ClientHttpRequestFactoryBuilder.httpComponents().withHttpClientCustomizer { it.disableAutomaticRetries() }
        if (maxConnectionsPerRoute == null) return builder
        return builder.withConnectionManagerCustomizer {
            it.setMaxConnPerRoute(maxConnectionsPerRoute).setMaxConnTotal(maxConnectionsPerRoute)
        }
    }
}
