package com.weave.faultinjection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.Random

/** 평시 지연 분포에서 뽑은 값이 설정한 중앙값·p99를 따르는지 시드를 준 난수로 본다 (F-007, design 결정 2). */
class LatencyDistributionTest :
    FunSpec({
        fun samples(
            medianMs: Double,
            p99Ms: Double,
        ): List<Double> {
            val distribution = LatencyDistribution(medianMs, p99Ms)
            val random = Random(7)
            return List(10_000) { distribution.sampleMs(random) }
        }

        listOf(20.0 to 50.0, 50.0 to 200.0, 100.0 to 120.0).forEach { (medianMs, p99Ms) ->
            test("F-007 중앙값 ${medianMs}ms, p99 ${p99Ms}ms로 10,000번 뽑으면 중앙값과 p99가 설정의 ±5% 안이다") {
                val values = samples(medianMs, p99Ms)

                values.percentile(0.5) shouldBe (medianMs plusOrMinus medianMs * 0.05)
                values.percentile(0.99) shouldBe (p99Ms plusOrMinus p99Ms * 0.05)
            }
        }

        test("F-007 중앙값과 p99가 같으면 늘 그 값이다") {
            samples(300.0, 300.0) shouldContainOnly listOf(300.0)
        }

        test("F-007 중앙값이 0이면 지연을 더하지 않는다") {
            samples(0.0, 0.0) shouldContainOnly listOf(0.0)
            samples(0.0, 50.0) shouldContainOnly listOf(0.0)
        }
    })
