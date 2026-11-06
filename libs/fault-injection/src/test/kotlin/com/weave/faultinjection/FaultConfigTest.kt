package com.weave.faultinjection

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

/** 장애 설정 검증: 에러율 0~1, 지연 0 이상, p99 ≥ 중앙값 (F-007, design 결정 2). */
class FaultConfigTest :
    FunSpec({
        mapOf(
            "에러율이 0보다 작다" to { FaultConfig(20.0, 50.0, -0.1) },
            "에러율이 1보다 크다" to { FaultConfig(20.0, 50.0, 1.5) },
            "에러율이 NaN이다" to { FaultConfig(20.0, 50.0, Double.NaN) },
            "중앙값이 음수다" to { FaultConfig(-1.0, 50.0, 0.0) },
            "p99가 음수다" to { FaultConfig(0.0, -1.0, 0.0) },
            "p99가 중앙값보다 작다" to { FaultConfig(100.0, 50.0, 0.0) },
            "중앙값이 NaN이다" to { FaultConfig(Double.NaN, 50.0, 0.0) },
            "p99가 무한대다" to { FaultConfig(20.0, Double.POSITIVE_INFINITY, 0.0) },
        ).forEach { (case, create) ->
            test("F-007 ${case}면 거절한다") {
                shouldThrow<IllegalArgumentException> { create() }
            }
        }

        test("F-007 바꾼 필드가 틀리면 copy도 거절한다") {
            shouldThrow<IllegalArgumentException> { FaultConfig(20.0, 50.0, 0.0).copy(latencyMedianMs = 100.0) }
        }

        mapOf(
            "기본값" to { FaultConfig(20.0, 50.0, 0.0) },
            "에러율 0과 1" to {
                FaultConfig(20.0, 50.0, 0.0)
                FaultConfig(20.0, 50.0, 1.0)
            },
            "지연 0" to { FaultConfig(0.0, 0.0, 0.0) },
            "중앙값과 p99가 같다" to { FaultConfig(300.0, 300.0, 0.0) },
        ).forEach { (case, create) ->
            test("F-007 경계의 맞는 값은 받는다: $case") {
                shouldNotThrowAny { create() }
            }
        }
    })
