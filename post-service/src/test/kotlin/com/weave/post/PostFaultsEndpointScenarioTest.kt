package com.weave.post

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `fault-injection` 스펙의 "실험 프로필로 띄운다"를 post-service로 본다 (F-007).
 *
 * `experiment` 프로필로 띄워 실제 HTTP로 `GET /actuator/faults`를 부른다. 본문은 지연 분포의 중앙값·p99와
 * 에러율을 `latencyMedianMs`, `latencyP99Ms`, `errorRate` 숫자로 담는다(design 결정 2의 엔드포인트).
 */
@Tags("Integration")
@ActiveProfiles("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostFaultsEndpointScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-007 실험 프로필로 띄운다: post-service의 GET /actuator/faults가 200이고 지연 중앙값·p99와 에러율이 온다") {
            val port =
                testContextManager()
                    .testContext.applicationContext.environment
                    .getRequiredProperty("local.server.port")
                    .toInt()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/faults")).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            response.statusCode() shouldBe 200
            val body = JsonMapper.builder().build().readTree(response.body())
            listOf("latencyMedianMs", "latencyP99Ms", "errorRate").forAll { field ->
                body.path(field).isNumber shouldBe true
            }
        }
    })
