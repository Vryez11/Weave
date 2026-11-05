package com.weave.observability.testapp

import com.weave.observability.testing.LogCapture
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.inspectors.forNone
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds

/**
 * `trace-logging` 스펙의 "JSON 로그 형식" 시나리오 (F-004).
 *
 * 작은 웹 애플리케이션을 실제 Tomcat으로 띄우고(design 결정 8), 요청을 처리하며 쓴 로그를 표준 출력에서 읽는다.
 */
@Tags("Integration")
@SpringBootTest(
    classes = [LoggingTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.application.name=$APP_NAME"],
)
class JsonLogFormatScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val http = HttpClient.newHttpClient()

        suspend fun get(path: String): Int {
            val port =
                testContextManager()
                    .testContext.applicationContext.environment
                    .getRequiredProperty("local.server.port")
                    .toInt()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }

        test("F-004 요청 처리 중의 로그: 표준 출력의 그 줄이 JSON 객체 하나이고 공통 필드와 traceId, spanId가 있다") {
            LogCapture().use { capture ->
                get("/log") shouldBe 200

                eventually(5.seconds) {
                    val line =
                        capture
                            .jsonLines()
                            .singleOrNull { it["message"] == LoggingTestController.REQUEST_LOG_MESSAGE }
                            .shouldNotBeNull()

                    line.shouldContainKeys("@timestamp", "level", "service", "logger", "thread", "message", "traceId", "spanId")
                    line["service"] shouldBe APP_NAME
                    line["level"] shouldBe "INFO"
                    (line["@timestamp"] as String) shouldMatch ISO_8601_MILLIS
                    (line["traceId"] as String) shouldMatch Regex("[0-9a-f]{32}")
                    (line["spanId"] as String) shouldMatch Regex("[0-9a-f]{16}")
                }
            }
        }

        test("F-004 예외가 있는 로그: stack_trace에 예외 클래스 이름이 있고 여러 줄 스택 트레이스여도 로그는 한 줄이다") {
            LogCapture().use { capture ->
                get("/log-exception") shouldBe 200

                eventually(5.seconds) {
                    val lines = capture.lines()
                    val json =
                        lines
                            .single { it.raw.contains(LoggingTestController.EXCEPTION_LOG_MESSAGE) }
                            .json
                            .shouldNotBeNull()

                    val stackTrace = (json["stack_trace"] as String?).shouldNotBeNull()
                    stackTrace shouldContain "IllegalStateException"
                    // 스택 트레이스 자체는 여러 줄이지만 JSON 문자열 안에 들어가 출력은 한 줄이다
                    stackTrace shouldContain "\n"
                    lines.filter { it.json == null }.forNone { it.raw shouldContain "IllegalStateException" }
                }
            }
        }
    })

private const val APP_NAME = "logging-test-app"

// 예: 2026-11-05T14:50:12.345+09:00, 2026-11-05T05:50:12.345Z
private val ISO_8601_MILLIS = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})""")
