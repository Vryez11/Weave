package com.weave.post

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 라이브러리 기본 속성이 연 `prometheus` 엔드포인트를 서비스가 따로 설정하지 않아도 쓸 수 있는지 본다 (F-004, design 결정 2·7).
 *
 * Boot 테스트는 메트릭 내보내기를 꺼서 `PrometheusMeterRegistry`가 없고 엔드포인트가 404다.
 * `@AutoConfigureMetrics`로 실제 실행과 같게 켠다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostPrometheusEndpointTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-004 Prometheus 엔드포인트: GET /actuator/prometheus가 200이고 Prometheus 텍스트 형식이다") {
            val port =
                testContextManager()
                    .testContext.applicationContext.environment
                    .getRequiredProperty("local.server.port")
                    .toInt()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            response.statusCode() shouldBe 200
            response.body() shouldContain "# TYPE"
        }
    })
