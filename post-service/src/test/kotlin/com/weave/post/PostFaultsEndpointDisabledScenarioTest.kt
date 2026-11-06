package com.weave.post

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `fault-injection` 스펙의 "프로필 없이 띄운다"를 post-service로 본다 (F-007).
 *
 * 프로필 없이 띄운다. 노출 목록에는 `faults`를 넣어, 404가 노출 목록이 아니라
 * 장애 주입이 꺼져 있어서인지를 본다(design 결정 2: `application-experiment.yml`에서만 켠다).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.endpoints.web.exposure.include=health,info,faults"],
)
class PostFaultsEndpointDisabledScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-007 프로필 없이 띄운다: 프로필 없이 띄운 post-service의 GET /actuator/faults가 404다") {
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
