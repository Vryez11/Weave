package com.weave.feed

import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `trace-logging` 시나리오 테스트가 feed-service를 부르는 도우미 (F-004).
 *
 * [FeedHttpClient]는 응답 헤더를 돌려주지 않아서, 응답의 `X-Trace-Id`를 보거나 `traceparent`를 실어 보내야 하는 테스트는 이것을 쓴다.
 * [headers]는 그대로 요청에 붙는다. 사용자 헤더도 기본으로 넣지 않으니 필요하면 [USER_HEADERS]를 준다.
 */
class TracedFeedHttp(
    private val port: Int,
) {
    fun getFeed(
        query: String = "",
        headers: Map<String, String> = USER_HEADERS,
    ): HttpResponse<String> = send(HttpRequest.newBuilder(uri("/feed$query")).GET(), headers)

    fun putLike(
        postId: Long,
        headers: Map<String, String> = USER_HEADERS,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri("/feed/likes/$postId"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("""{"liked":true,"version":7}""")),
            headers,
        )

    private fun uri(path: String) = URI.create("http://localhost:$port$path")

    private fun send(
        builder: HttpRequest.Builder,
        headers: Map<String, String>,
    ): HttpResponse<String> {
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        val USER_HEADERS = mapOf("X-User-Id" to "1")
        private val client: HttpClient = HttpClient.newHttpClient()
    }
}

private val responseMapper = jacksonObjectMapper()

/** 응답 본문을 JSON 객체로 읽는다. */
fun HttpResponse<String>.json(): Map<String, Any?> = responseMapper.readValue<Map<String, Any?>>(body())

/** 응답의 `X-Trace-Id`. 없으면 테스트를 실패시킨다. */
fun HttpResponse<*>.traceId(): String = withClue("응답의 X-Trace-Id 헤더") { headers().firstValue("X-Trace-Id").orElse(null).shouldNotBeNull() }

/** 하위 서비스가 받은 `traceparent`(`00-<traceId>-<spanId>-<flags>`)의 traceId. 헤더가 없으면 null이다. */
fun LoggedRequest.traceparentTraceId(): String? =
    if (containsHeader("traceparent")) header("traceparent").firstValue().split("-").getOrNull(1) else null
