package com.weave.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * `trace-logging` 스펙 중 Docker Compose로 띄운 환경에서 보는 시나리오 (F-004).
 *
 * "Loki에서 traceId로 로그 조회"와 "Tempo에서 traceId로 트레이스 조회"를 본다(design 결정 10).
 * Compose를 띄우고 내리는 것은 `:e2e:composeTest` 태스크가 맡고, 주소는 아래 시스템 속성으로 받는다.
 * - `weave.compose.feed-url`: feed-service 주소. 예) `http://localhost:8080`
 * - `weave.compose.loki-url`: Loki 주소. 예) `http://localhost:3100`
 * - `weave.compose.tempo-url`: Tempo HTTP API 주소. 예) `http://localhost:3200`
 *
 * 서비스 이름은 로그의 `service` 필드와 스팬의 `service.name`(둘 다 `spring.application.name`)으로 본다.
 * Compose 서비스 이름에 기대지 않는다.
 */
@Tags("Compose")
class TraceLookupComposeScenarioTest :
    FunSpec({
        val expectedServices =
            listOf(
                "feed-service",
                "post-service",
                "user-service",
                "like-service",
                "recommendation-service",
                "ad-service",
            )
        val mapper = jacksonObjectMapper()
        val http = HttpClient.newHttpClient()

        fun address(property: String): String =
            withClue("시스템 속성 $property (:e2e:composeTest가 넘긴다)") {
                System.getProperty(property).shouldNotBeNull().trimEnd('/')
            }

        fun get(
            url: String,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse<String> {
            val request =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
            headers.forEach { (name, value) -> request.header(name, value) }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        /** `GET /feed`를 한 번 부르고 응답의 `X-Trace-Id`를 돌려준다. */
        fun feedTraceId(): String {
            val response = get("${address("weave.compose.feed-url")}/feed", mapOf("X-User-Id" to "1"))
            withClue("피드 응답: ${response.body()}") { response.statusCode() shouldBe 200 }
            return withClue("응답의 X-Trace-Id 헤더") {
                response
                    .headers()
                    .firstValue("X-Trace-Id")
                    .orElse(null)
                    .shouldNotBeNull()
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

        test("F-004 Loki에서 traceId로 로그 조회: GET /feed 한 번의 X-Trace-Id로 서비스 6개의 로그가 @timestamp가 줄지 않는 순서로 나온다") {
            val requestedAt = Instant.now()
            val traceId = feedTraceId()
            val loki = address("weave.compose.loki-url")
            val query = URLEncoder.encode("{service=~\".+\"} | traceId=\"$traceId\"", StandardCharsets.UTF_8)

            eventually(60.seconds) {
                val start = requestedAt.minusSeconds(300).toEpochMilli() * 1_000_000
                val end = Instant.now().plusSeconds(60).toEpochMilli() * 1_000_000
                val response =
                    get("$loki/loki/api/v1/query_range?query=$query&start=$start&end=$end&direction=forward&limit=1000")
                withClue("Loki 응답: ${response.body()}") { response.statusCode() shouldBe 200 }

                // 스트림(라벨 조합)마다 나뉘어 오는 결과를 Loki 시각 순으로 합친다. Grafana가 보여 주는 순서와 같다.
                val entries =
                    mapper
                        .readValue<Map<String, Any?>>(response.body())["data"]
                        .asMap()["result"]
                        .asList()
                        .flatMap { stream -> stream.asMap()["values"].asList().map { it.asList() } }
                        .map { (nanos, line) -> (nanos as String).toLong() to mapper.readValue<Map<String, Any?>>(line as String) }
                        .sortedBy { it.first }

                withClue("Loki에서 traceId $traceId 로 조회한 로그의 service") {
                    entries.map { it.second["service"] }.distinct() shouldContainAll expectedServices
                }
                // Loki 시각이 줄의 @timestamp여야 Loki 시각 순서가 곧 @timestamp 순서다
                entries.forEach { (nanos, entry) ->
                    val timestamp = OffsetDateTime.parse(entry["@timestamp"] as String).toInstant()
                    withClue("Loki 시각(ns)과 @timestamp: $entry") {
                        nanos shouldBe timestamp.epochSecond * 1_000_000_000 + timestamp.nano
                    }
                }
                val timestamps = entries.map { OffsetDateTime.parse(it.second["@timestamp"] as String).toInstant() }
                withClue("Loki 조회 결과의 @timestamp 순서: $timestamps") {
                    timestamps shouldBe timestamps.sorted()
                }
            }
        }

        test("F-004 Tempo에서 traceId로 트레이스 조회: GET /feed 한 번의 X-Trace-Id로 서비스 6개의 스팬이 한 트레이스에 나온다") {
            val traceId = feedTraceId()
            val tempo = address("weave.compose.tempo-url")

            eventually(60.seconds) {
                val response = get("$tempo/api/traces/$traceId", mapOf("Accept" to "application/json"))
                withClue("Tempo 응답: ${response.body()}") { response.statusCode() shouldBe 200 }

                // OTLP JSON. Tempo 버전에 따라 리소스 목록이 batches 또는 resourceSpans에, 스팬 묶음이 scopeSpans
                // 또는 instrumentationLibrarySpans에 있다.
                val root = mapper.readValue<Map<String, Any?>>(response.body())
                val trace = root["trace"]?.asMap() ?: root
                val servicesWithSpans =
                    (trace["batches"] ?: trace["resourceSpans"]).asList().mapNotNull { batch ->
                        val resource = batch.asMap()
                        val serviceName =
                            resource["resource"]
                                .asMap()["attributes"]
                                .asList()
                                .map { it.asMap() }
                                .firstOrNull { it["key"] == "service.name" }
                                ?.get("value")
                                .asMap()["stringValue"]
                        val spanCount =
                            (resource["scopeSpans"] ?: resource["instrumentationLibrarySpans"])
                                .asList()
                                .sumOf { it.asMap()["spans"].asList().size }
                        serviceName.takeIf { spanCount > 0 }
                    }

                withClue("Tempo 트레이스 $traceId 에 스팬이 있는 service.name") {
                    servicesWithSpans.distinct() shouldContainAll expectedServices
                }
            }
        }
    })
