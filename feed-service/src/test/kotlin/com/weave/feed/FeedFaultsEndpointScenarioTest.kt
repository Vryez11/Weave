package com.weave.feed

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `fault-injection` 스펙의 "feed-service에는 없다" (F-007).
 *
 * `experiment` 프로필로 띄우고, 노출 목록에 `faults`를 넣어 404가 노출 목록이 아니라
 * 장애 주입 모듈이 없어서인지를 본다(design 결정 2: 하위 서비스 5개에만 붙인다).
 */
@Tags("Integration")
@ActiveProfiles("experiment")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.endpoints.web.exposure.include=health,info,prometheus,faults"],
)
class FeedFaultsEndpointScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-007 feed-service에는 없다: experiment 프로필로 띄워도 GET /actuator/faults가 404다") {
            val port =
                testContextManager()
                    .testContext.applicationContext.environment
                    .getRequiredProperty("local.server.port")
                    .toInt()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/faults")).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            response.statusCode() shouldBe 404
        }
    })
