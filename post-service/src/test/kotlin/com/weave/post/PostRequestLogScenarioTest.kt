package com.weave.post

import com.weave.observability.testing.LogCapture
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds

/**
 * `trace-logging` 스펙의 "요청 완료 로그" 중 post-service 경로를 쓰는 시나리오 (F-004).
 *
 * 실제 Tomcat으로 띄우고(design 결정 8), 그 요청의 줄은 응답의 `X-Trace-Id`와 같은 `traceId`로 찾는다.
 * 앞 테스트의 요청 로그가 비동기 appender를 거쳐 늦게 와도 섞이지 않는다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostRequestLogScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val http = HttpClient.newHttpClient()

        suspend fun get(path: String): HttpResponse<Void> {
            val port =
                testContextManager()
                    .testContext.applicationContext.environment
                    .getRequiredProperty("local.server.port")
                    .toInt()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
            return http.send(request, HttpResponse.BodyHandlers.discarding())
        }

        fun HttpResponse<*>.traceId(): String =
            withClue("응답의 X-Trace-Id 헤더") { headers().firstValue("X-Trace-Id").orElse(null).shouldNotBeNull() }

        fun LogCapture.requestLines(): List<Map<String, Any?>> = jsonLines().filter { it["event"] == "http_request" }

        test("F-004 요청 하나에 한 줄: GET /posts?size=3이 200이면 method, path, status, durationMs가 담긴 http_request 줄이 하나다") {
            LogCapture().use { capture ->
                val response = get("/posts?size=3")
                response.statusCode() shouldBe 200
                val traceId = response.traceId()

                eventually(5.seconds) {
                    val lines = capture.requestLines().filter { it["traceId"] == traceId }
                    lines shouldHaveSize 1
                    val line = lines.single()

                    line["method"] shouldBe "GET"
                    line["path"] shouldBe "/posts"
                    line["status"] shouldBe 200
                    // 정수여야 한다. 1.5 같은 실수면 Jackson이 Double로 읽는다
                    val durationMs = line["durationMs"]
                    (durationMs is Int || durationMs is Long) shouldBe true
                    (durationMs as Number).toLong() shouldBeGreaterThanOrEqual 0L
                }
            }
        }

        test("F-004 헬스 체크: GET /actuator/health는 http_request 줄을 남기지 않는다") {
            LogCapture().use { capture ->
                get("/actuator/health").statusCode() shouldBe 200
                // 남기지 않음은 기다려도 판정할 수 없으니, 뒤에 보낸 요청의 줄이 나올 때까지 기다린다.
                // 비동기 appender는 들어온 순서대로 쓰므로 그때까지 헬스 체크 줄이 없으면 남기지 않은 것이다.
                val probeTraceId = get("/posts?size=1").traceId()

                eventually(5.seconds) {
                    val lines = capture.requestLines()
                    lines.filter { it["traceId"] == probeTraceId } shouldHaveSize 1
                    lines.filter { (it["path"] as? String)?.startsWith("/actuator") == true }.shouldBeEmpty()
                }
            }
        }
    })
