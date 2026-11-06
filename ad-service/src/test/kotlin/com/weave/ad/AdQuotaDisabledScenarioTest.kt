package com.weave.ad

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest

/**
 * `ad-serving` 스펙의 "실험 프로필이 아니다"와 `fault-injection` 스펙의 "프로필 없이 띄운다"를 본다 (F-007).
 *
 * 프로필 없이 띄운다. 노출 목록에는 `quota`와 `faults`를 넣어, 404가 노출 목록이 아니라
 * 요청 한도와 장애 주입이 꺼져 있어서인지를 본다(design 결정 2, 3: `application-experiment.yml`에서만 켠다).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.endpoints.web.exposure.include=health,info,quota,faults"],
)
class AdQuotaDisabledScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-007 실험 프로필이 아니다: 프로필 없이 띄운 ad-service의 GET /actuator/quota가 404다") {
            val http =
                AdHttpClient(
                    testContextManager()
                        .testContext.applicationContext.environment
                        .getRequiredProperty("local.server.port")
                        .toInt(),
                )

            http.get("/actuator/quota").statusCode() shouldBe 404
        }

        test("F-007 프로필 없이 띄운다: 프로필 없이 띄운 ad-service의 GET /actuator/faults가 404다") {
            val http =
                AdHttpClient(
                    testContextManager()
                        .testContext.applicationContext.environment
                        .getRequiredProperty("local.server.port")
                        .toInt(),
                )

            http.get("/actuator/faults").statusCode() shouldBe 404
        }
    })
