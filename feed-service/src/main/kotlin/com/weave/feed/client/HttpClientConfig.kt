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
import org.springframework.core.ResolvableType
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpMessageConverterExtractor
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer
import org.springframework.web.service.registry.ImportHttpServices
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

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

    /**
     * 오류 응답의 예외 메시지에 하위 서비스의 응답 본문을 넣지 않는다(`trace-logging` "로그에 남기지 않는 것").
     * 기본 처리기는 메시지 끝에 본문을 붙이고, 처리되지 않은 예외를 Tomcat이 메시지·스택 트레이스와 함께 ERROR로 남겨 본문이 로그에 실린다.
     * 예외 종류, 상태 코드, 본문 바이트는 기본 처리기와 같아 실패 분류와 응답(500)은 바뀌지 않는다.
     *
     * 기본 처리기처럼 그 클라이언트의 메시지 변환기로 본문 변환 함수를 넣어 `getResponseBodyAs(...)`로 본문을 읽을 수 있다.
     * 변환기 목록은 `configureMessageConvertersList`로 빌더가 `build()`에서 만드는 최종 목록을 받는다.
     * 폐기 예정인 `messageConverters(Consumer)`는 부르는 자리에서 목록을 굳혀 뒤에 오는 변환기 설정을 놓친다.
     */
    @Bean
    fun bodylessErrorMessages(): RestClientHttpServiceGroupConfigurer =
        RestClientHttpServiceGroupConfigurer { groups ->
            groups.forEachClient { _, builder ->
                var converters: List<HttpMessageConverter<*>> = emptyList()
                builder.configureMessageConverters { it.configureMessageConvertersList { list -> converters = list } }
                builder.defaultStatusHandler(HttpStatusCode::isError) { _, response ->
                    throw errorWithoutBodyInMessage(response, converters)
                }
            }
        }

    /** 처리기의 조건이 [HttpStatusCode.isError]라 4xx·5xx만 온다. */
    private fun errorWithoutBodyInMessage(
        response: ClientHttpResponse,
        converters: List<HttpMessageConverter<*>>,
    ): RestClientResponseException {
        val status = response.statusCode
        val statusText = response.statusText
        val message = "${status.value()} $statusText"
        val body = response.body.readAllBytes()
        val charset = response.headers.contentType?.charset
        val error =
            if (status.is4xxClientError) {
                HttpClientErrorException.create(message, status, statusText, response.headers, body, charset)
            } else {
                HttpServerErrorException.create(message, status, statusText, response.headers, body, charset)
            }
        error.setBodyConvertFunction { type -> readBody(type, response, body, converters) }
        return error
    }

    private fun readBody(
        type: ResolvableType,
        response: ClientHttpResponse,
        body: ByteArray,
        converters: List<HttpMessageConverter<*>>,
    ): Any? {
        val bufferedResponse =
            object : ClientHttpResponse by response {
                override fun getBody(): InputStream = ByteArrayInputStream(body)
            }
        return try {
            HttpMessageConverterExtractor<Any>(type.type, converters).extractData(bufferedResponse)
        } catch (e: IOException) {
            throw RestClientException("Error while extracting response for type [$type]", e)
        }
    }
}
