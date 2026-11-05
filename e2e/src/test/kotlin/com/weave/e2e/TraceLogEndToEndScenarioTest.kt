package com.weave.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * `trace-logging` 스펙 중 서비스 6개를 실제 프로세스로 띄워 보는 시나리오 (F-004).
 *
 * "서비스 6개의 로그를 traceId로 잇는다"와 "요청의 값이 로그에 없다"를 본다.
 * 로그는 [ServiceLogs]로 `e2e/build/e2e-logs/<서비스>.log`를 줄마다 읽고, 요청의 줄은 응답의 `X-Trace-Id`로 찾는다.
 */
@Tags("Integration")
class TraceLogEndToEndScenarioTest :
    FunSpec({
        val services = ServiceProcesses()
        val logs = ServiceLogs()
        val http = HttpClient.newHttpClient()

        beforeSpec { services.start() }
        afterSpec { services.stop() }

        fun getFeed(
            path: String = "/feed",
            headers: Map<String, String> = mapOf("X-User-Id" to "1"),
        ): HttpResponse<String> {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:${services.port("feed-service")}$path"))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
            headers.forEach { (name, value) -> request.header(name, value) }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun HttpResponse<*>.traceId(): String =
            withClue("응답의 X-Trace-Id 헤더") { headers().firstValue("X-Trace-Id").orElse(null).shouldNotBeNull() }

        /** 서비스 6개 모두에 [traceId]의 요청 완료 로그가 나올 때까지 기다린다. */
        suspend fun awaitRequestLinesInAllServices(traceId: String) {
            eventually(15.seconds) {
                services.services.forEach { service ->
                    withClue("$service 로그(e2e/build/e2e-logs/$service.log)에 traceId $traceId 인 http_request 줄") {
                        logs.requestLines(service, traceId).shouldNotBeEmpty()
                    }
                }
            }
        }

        test("F-004 실제 프로세스로 띄운 서비스 6개: GET /feed 한 번의 http_request 줄이 서비스마다 응답의 X-Trace-Id로 남는다") {
            val response = getFeed()
            withClue("피드 응답: ${response.body()}") { response.statusCode() shouldBe 200 }

            awaitRequestLinesInAllServices(response.traceId())
        }

        test("F-004 요청의 값이 로그에 없다: X-User-Id, X-Marker 헤더와 쿼리 marker의 표식이 서비스 6개의 어떤 로그 줄에도 없다") {
            val marker = "mk" + UUID.randomUUID().toString().replace("-", "")
            val markerNumber = Random.nextLong(100_000_000L, 1_000_000_000L).toString()

            val response =
                getFeed(
                    path = "/feed?marker=$marker",
                    headers = mapOf("X-User-Id" to markerNumber, "X-Marker" to marker),
                )
            withClue("피드 응답: ${response.body()}") { response.statusCode() shouldBe 200 }
            awaitRequestLinesInAllServices(response.traceId())

            // 남기지 않음은 기다려도 판정할 수 없으니, 뒤에 보낸 요청의 줄이 서비스마다 나올 때까지 기다린다.
            // 비동기 appender는 들어온 순서대로 쓰므로, 그때까지 표식이 없으면 그 요청을 처리하며 남기지 않은 것이다.
            awaitRequestLinesInAllServices(getFeed().traceId())

            services.services.forEach { service ->
                withClue("$service 로그(e2e/build/e2e-logs/$service.log)에 표식 $marker 또는 $markerNumber 가 든 줄") {
                    logs
                        .rawLines(service)
                        .filter { it.contains(marker) || it.contains(markerNumber) }
                        .shouldBeEmpty()
                }
            }
        }
    })
