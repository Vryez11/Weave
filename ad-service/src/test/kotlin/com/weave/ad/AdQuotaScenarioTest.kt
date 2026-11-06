package com.weave.ad

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `ad-serving` 스펙의 "요청 한도" 시나리오를 ad-service로 본다 (F-007).
 *
 * `experiment` 프로필로 띄워 실제 HTTP로 부른다. `quota`를 켜고 노출하는 것은 서비스의 `application-experiment.yml`이 맡는다.
 * `POST /actuator/quota`는 JSON으로 `limit`(창 안의 요청 수, 0 이상)과 `windowSeconds`(창 길이, 1 이상)를 받고,
 * `DELETE /actuator/quota`는 한도를 끈다(design 결정 3).
 *
 * 시각은 [AdjustableClock]으로 테스트마다 멈춰 두고, "다음 창"만 실제 시계로 `Retry-After`만큼 기다린다.
 * 한도는 컨텍스트에 하나라 테스트마다 앞에서 `DELETE`로 끈다.
 * "실험 프로필이 아니다"는 [AdQuotaDisabledScenarioTest]에서 본다.
 */
@Tags("Integration")
@ActiveProfiles("experiment")
@Import(AdjustableClockConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdQuotaScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        lateinit var http: AdHttpClient
        lateinit var clock: AdjustableClock

        fun enableQuota(
            limit: Int,
            windowSeconds: Int,
        ) {
            http.post("/actuator/quota", """{"limit":$limit,"windowSeconds":$windowSeconds}""").statusCode() shouldBeInRange 200..299
        }

        fun HttpResponse<String>.retryAfterSeconds(): Int =
            headers()
                .firstValue("Retry-After")
                .orElse(null)
                .shouldNotBeNull()
                .toIntOrNull()
                .shouldNotBeNull()

        beforeTest {
            val context = testContextManager().testContext.applicationContext
            http = AdHttpClient(context.environment.getRequiredProperty("local.server.port").toInt())
            clock = context.getBean(AdjustableClock::class.java)
            clock.freeze()
            http.delete("/actuator/quota")
        }

        test("F-007 한도를 넘는다: 창 10초에 3개면 같은 창의 4번째 GET /ads가 429이고 Retry-After가 1~10의 정수다") {
            enableQuota(limit = 3, windowSeconds = 10)

            List(3) { http.get("/ads").statusCode() } shouldContainOnly listOf(200)
            clock.advance(Duration.ofSeconds(4))
            val fourth = http.get("/ads")

            fourth.statusCode() shouldBe 429
            fourth.retryAfterSeconds() shouldBeInRange 1..10
        }

        test("F-007 다음 창: 창 2초에 1개로 429를 받은 뒤 Retry-After만큼 기다리면 GET /ads가 200이다") {
            clock.useRealTime()
            enableQuota(limit = 1, windowSeconds = 2)

            http.get("/ads").statusCode() shouldBe 200
            val rejected = http.get("/ads")
            rejected.statusCode() shouldBe 429
            Thread.sleep(Duration.ofSeconds(rejected.retryAfterSeconds().toLong()))

            http.get("/ads").statusCode() shouldBe 200
        }

        test("F-007 한도 0: 창 10초에 0개면 GET /ads가 429이고 Retry-After가 온다") {
            enableQuota(limit = 0, windowSeconds = 10)

            val response = http.get("/ads")

            response.statusCode() shouldBe 429
            response.retryAfterSeconds() shouldBeInRange 1..10
        }

        test("F-007 꺼진 한도: 한도를 켰다가 DELETE로 끄면 GET /ads 100번이 모두 200이다") {
            enableQuota(limit = 1, windowSeconds = 10)

            http.delete("/actuator/quota").statusCode() shouldBeInRange 200..299

            List(100) { http.get("/ads").statusCode() } shouldContainOnly listOf(200)
        }

        mapOf(
            "요청 수 -1" to """{"limit":-1,"windowSeconds":10}""",
            "창 길이 0초" to """{"limit":3,"windowSeconds":0}""",
        ).forEach { (case, json) ->
            test("F-007 잘못된 한도: $case 이면 400이고 한도가 꺼진 그대로다") {
                val before = http.get("/actuator/quota")
                before.statusCode() shouldBe 200

                http.post("/actuator/quota", json).statusCode() shouldBe 400

                http.get("/actuator/quota").body() shouldBe before.body()
                List(5) { http.get("/ads").statusCode() } shouldContainOnly listOf(200)
            }
        }
    })

/** ad-service를 실제 HTTP로 부르는 도구. 429의 `Retry-After`를 보려고 응답을 그대로 돌려준다. */
class AdHttpClient(
    private val port: Int,
) {
    private val client = HttpClient.newHttpClient()

    fun get(path: String): HttpResponse<String> = send(HttpRequest.newBuilder(uri(path)).GET())

    fun post(
        path: String,
        json: String,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)),
        )

    fun delete(path: String): HttpResponse<String> = send(HttpRequest.newBuilder(uri(path)).DELETE())

    private fun uri(path: String) = URI.create("http://localhost:$port$path")

    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
}
