package com.weave.faultinjection

import com.weave.faultinjection.testapp.FaultInjectionTestApplication
import com.weave.observability.testing.LogCapture
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
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
 * 장애 주입 필터가 `libs/observability`의 추적·요청 로그 필터 뒤에 있는지 본다 (F-007, design 결정 2).
 *
 * 넣은 500도 응답에 `X-Trace-Id`가 있고, 그 traceId의 `http_request` 줄에 500과 넣은 지연이 담겨야 한다.
 * 장애 설정은 다른 스펙과 같은 컨텍스트일 수 있어 테스트 뒤에 `DELETE`로 되돌린다.
 */
@Tags("Integration")
@SpringBootTest(
    classes = [FaultInjectionTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.fault-injection.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus,faults",
    ],
)
class FaultInjectionRequestLogTest :
    FunSpec({
        extensions(SpringExtension())

        val http = HttpClient.newHttpClient()

        suspend fun port(): Int =
            testContextManager()
                .testContext.applicationContext.environment
                .getRequiredProperty("local.server.port")
                .toInt()

        afterTest { FaultsHttpClient(port()).delete("/actuator/faults") }

        test("F-007 넣은 500의 요청 완료 로그: 응답의 X-Trace-Id와 같은 traceId의 http_request 줄이 500과 넣은 지연을 담는다") {
            val port = port()
            FaultsHttpClient(port)
                .post("/actuator/faults", """{"latencyMedianMs":100,"latencyP99Ms":100,"errorRate":1}""")
                .status shouldBe 200

            LogCapture().use { capture ->
                val response =
                    http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:$port/work")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                response.statusCode() shouldBe 500
                response.body() shouldBe """{"error":"injected fault"}"""
                val traceId =
                    withClue("응답의 X-Trace-Id 헤더") {
                        response
                            .headers()
                            .firstValue("X-Trace-Id")
                            .orElse(null)
                            .shouldNotBeNull()
                    }

                eventually(5.seconds) {
                    val lines = capture.jsonLines().filter { it["event"] == "http_request" && it["traceId"] == traceId }
                    lines shouldHaveSize 1
                    val line = lines.single()
                    line["path"] shouldBe "/work"
                    line["status"] shouldBe 500
                    (line["durationMs"] as Number).toLong() shouldBeGreaterThanOrEqual 100L
                }
            }
        }
    })
