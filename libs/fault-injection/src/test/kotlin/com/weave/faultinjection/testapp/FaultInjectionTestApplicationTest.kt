package com.weave.faultinjection.testapp

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

/** 장애 주입 시나리오 테스트가 띄우는 테스트 앱이 실제 Tomcat에서 `GET /work`에 답하는지 본다 (F-007). */
@Tags("Integration")
@SpringBootTest(
    classes = [FaultInjectionTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class FaultInjectionTestApplicationTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-007 테스트 앱의 GET /work가 200과 본문을 돌려준다") {
            val port =
                testContextManager()
                    .testContext.applicationContext.environment
                    .getRequiredProperty("local.server.port")
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:$port/work")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            response.statusCode() shouldBe 200
            response.body() shouldBe WorkController.WORK_RESPONSE
        }
    })
