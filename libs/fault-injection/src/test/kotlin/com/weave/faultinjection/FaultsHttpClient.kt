package com.weave.faultinjection

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.ceil

/** 시나리오 테스트가 테스트 앱을 실제 HTTP로 부르는 도구. 응답 시간은 보내기 전부터 본문을 다 받을 때까지 잰다. */
class FaultsHttpClient(
    private val port: Int,
) {
    private val client = HttpClient.newHttpClient()

    fun get(path: String): TimedResponse = send(HttpRequest.newBuilder(uri(path)).GET())

    fun post(
        path: String,
        json: String,
    ): TimedResponse =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)),
        )

    fun delete(path: String): TimedResponse = send(HttpRequest.newBuilder(uri(path)).DELETE())

    private fun uri(path: String) = URI.create("http://localhost:$port$path")

    private fun send(builder: HttpRequest.Builder): TimedResponse {
        val start = System.nanoTime()
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        return TimedResponse(response.statusCode(), response.body(), elapsedMs)
    }
}

data class TimedResponse(
    val status: Int,
    val body: String,
    val elapsedMs: Double,
) {
    fun json(): JsonNode = mapper.readTree(body)

    private companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()
    }
}

/**
 * `GET /actuator/faults` 본문의 장애 설정. 필드 이름은 `POST`가 받는 이름과 같다(design 결정 2).
 * 정수·실수 표기 차이에 흔들리지 않게 모두 실수로 읽는다.
 */
data class FaultSettings(
    val latencyMedianMs: Double,
    val latencyP99Ms: Double,
    val errorRate: Double,
) {
    companion object {
        fun from(node: JsonNode) =
            FaultSettings(
                latencyMedianMs = node.required("latencyMedianMs").asDouble(),
                latencyP99Ms = node.required("latencyP99Ms").asDouble(),
                errorRate = node.required("errorRate").asDouble(),
            )
    }
}

/** 가장 가까운 순위 방식의 백분위수. 1,000개에서 p99는 작은 쪽부터 990번째 값이다. */
fun List<Double>.percentile(p: Double): Double {
    val sorted = sorted()
    val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
