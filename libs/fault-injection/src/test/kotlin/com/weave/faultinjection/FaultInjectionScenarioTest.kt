package com.weave.faultinjection

import com.weave.faultinjection.testapp.FaultInjectionTestApplication
import com.weave.faultinjection.testapp.WorkController
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest

/**
 * `fault-injection` 스펙의 시나리오를 테스트 앱으로 본다 (F-007).
 *
 * 하위 서비스의 `application-experiment.yml`이 하는 일(장애 주입을 켜고 `faults`를 노출)을 속성으로 대신한다.
 * 업무 요청은 테스트 앱의 `GET /work`가 맡는다. 장애 설정은 컨텍스트에 하나라 테스트마다 앞에서 `DELETE`로 되돌린다.
 * "실험 프로필로 띄운다"와 "feed-service에는 없다"는 서비스 테스트에서 본다.
 */
@Tags("Integration")
@AutoConfigureMetrics
@SpringBootTest(
    classes = [FaultInjectionTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "weave.fault-injection.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus,faults",
    ],
)
class FaultInjectionScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        lateinit var http: FaultsHttpClient
        val defaults = FaultSettings(latencyMedianMs = 20.0, latencyP99Ms = 50.0, errorRate = 0.0)

        fun currentSettings(): FaultSettings {
            val response = http.get("/actuator/faults")
            response.status shouldBe 200
            return FaultSettings.from(response.json())
        }

        fun changeFaults(json: String) {
            http.post("/actuator/faults", json).status shouldBeInRange 200..299
        }

        beforeTest {
            http =
                FaultsHttpClient(
                    testContextManager()
                        .testContext.applicationContext.environment
                        .getRequiredProperty("local.server.port")
                        .toInt(),
                )
            http.delete("/actuator/faults")
        }

        test("F-007 기본 분포를 따른다: 기본 설정에서 업무 요청 1,000번의 중앙값은 20~30ms, p99는 40~65ms다") {
            // 첫 요청들의 DispatcherServlet 초기화·JIT 비용이 꼬리에 섞이지 않게 먼저 데운다
            repeat(20) { http.get("/work") }

            val elapsed = List(1_000) { http.get("/work").also { it.status shouldBe 200 }.elapsedMs }

            elapsed.percentile(0.50) shouldBeGreaterThanOrEqual 20.0
            elapsed.percentile(0.50) shouldBeLessThanOrEqual 30.0
            elapsed.percentile(0.99) shouldBeGreaterThanOrEqual 40.0
            elapsed.percentile(0.99) shouldBeLessThanOrEqual 65.0
        }

        test("F-007 고정 지연: 중앙값·p99를 300ms로 바꾸면 업무 요청 20번이 모두 300ms 이상 걸리고 GET이 바꾼 값을 돌려준다") {
            changeFaults("""{"latencyMedianMs":300,"latencyP99Ms":300}""")

            repeat(20) {
                val response = http.get("/work")
                response.status shouldBe 200
                response.elapsedMs shouldBeGreaterThanOrEqual 300.0
            }
            currentSettings() shouldBe defaults.copy(latencyMedianMs = 300.0, latencyP99Ms = 300.0)
        }

        test("F-007 에러율 1: 업무 요청 20번이 모두 500이다") {
            changeFaults("""{"errorRate":1}""")

            List(20) { http.get("/work").status } shouldContainOnly listOf(500)
        }

        test("F-007 에러율 0.5: 업무 요청 1,000번 중 500의 비율이 0.45~0.55이고 나머지는 200이다") {
            changeFaults("""{"errorRate":0.5}""")

            val statuses = List(1_000) { http.get("/work").status }

            statuses.toSet() shouldBe setOf(200, 500)
            val errorRatio = statuses.count { it == 500 } / statuses.size.toDouble()
            errorRatio shouldBeGreaterThanOrEqual 0.45
            errorRatio shouldBeLessThanOrEqual 0.55
        }

        test("F-007 되돌린다: DELETE를 두 번 불러도 모두 2xx이고 기본값으로 돌아가 업무 요청이 200이다") {
            changeFaults("""{"latencyMedianMs":300,"latencyP99Ms":300,"errorRate":1}""")

            repeat(2) { http.delete("/actuator/faults").status shouldBeInRange 200..299 }

            currentSettings() shouldBe defaults
            val work = http.get("/work")
            work.status shouldBe 200
            work.body shouldBe WorkController.WORK_RESPONSE
        }

        test("F-007 장애 중의 헬스 체크: 지연 1,000ms·에러율 1에서도 health, prometheus, faults가 200이고 1,000ms보다 빠르다") {
            changeFaults("""{"latencyMedianMs":1000,"latencyP99Ms":1000,"errorRate":1}""")

            listOf("/actuator/health", "/actuator/prometheus", "/actuator/faults").forEach { path ->
                val response = http.get(path)
                response.status shouldBe 200
                response.elapsedMs shouldBeLessThan 1_000.0
            }
        }

        mapOf(
            "에러율 1.5" to """{"errorRate":1.5}""",
            "중앙값 -1ms" to """{"latencyMedianMs":-1}""",
            "중앙값 100ms·p99 50ms" to """{"latencyMedianMs":100,"latencyP99Ms":50}""",
        ).forEach { (case, json) ->
            test("F-007 범위를 벗어난 값: $case 이면 400이고 설정이 바뀌지 않는다") {
                changeFaults("""{"latencyMedianMs":30,"latencyP99Ms":80,"errorRate":0.1}""")
                val before = currentSettings()

                http.post("/actuator/faults", json).status shouldBe 400

                currentSettings() shouldBe before
            }
        }
    })
