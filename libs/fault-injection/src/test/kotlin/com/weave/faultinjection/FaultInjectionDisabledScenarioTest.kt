package com.weave.faultinjection

import com.weave.faultinjection.testapp.FaultInjectionTestApplication
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest

/**
 * `fault-injection` 스펙의 "프로필 없이 띄운다"를 테스트 앱으로 본다 (F-007).
 *
 * `weave.fault-injection.*` 속성 없이 띄운다. 노출 목록에는 `faults`를 넣어, 404가 노출 목록이 아니라
 * 장애 주입이 꺼져 엔드포인트가 없어서인지를 본다.
 */
@Tags("Integration")
@SpringBootTest(
    classes = [FaultInjectionTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.endpoints.web.exposure.include=health,info,faults"],
)
class FaultInjectionDisabledScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-007 프로필 없이 띄운다: GET /actuator/faults가 404다") {
            val http =
                FaultsHttpClient(
                    testContextManager()
                        .testContext.applicationContext.environment
                        .getRequiredProperty("local.server.port")
                        .toInt(),
                )

            http.get("/actuator/faults").status shouldBe 404
        }
    })
