package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `fault-experiments` 스펙 "단계별 기대 결과로 판정한다"의 "기대대로 SLO를 못 지킨다", "기대와 다르다", "기준별 기대와 같다",
 * "기준별 기대와 다르다", "지금 단계의 기대"와 "degrade 비율 기준"의 두 시나리오, "요청 배율 기준"의 세 시나리오,
 * "Retry-After 기준"의 판정 두 시나리오 (F-007, F-001, F-002, F-005).
 *
 * 미리 만든 k6 요약 파일과 기대 결과 파일로 판정 스크립트만 돌린다. 단계가 바뀌어도 판정 로직 테스트가 흔들리지 않게
 * 실제 `experiments/expectations.json`은 "지금 단계의 기대"만 읽는다. Docker와 Compose 환경은 쓰지 않는다
 * (add-fault-experiments design 결정 7, 8, 13, add-call-isolation design 결정 10, add-graceful-degradation design 결정 12).
 *
 * **명령:** `experiments/lib/verdict.sh <k6 요약 파일> <시나리오 ID> [기대 결과 파일] [판정 입력 파일]`
 * - k6 요약 파일: `k6 run --summary-export`의 결과. `metrics`의 키 중 `{scenario:measure}`가 붙은 지표의 threshold만 본다.
 *   이 형식에서 threshold 값 `true`는 기준을 넘었다(실패), `false`는 통과라는 뜻이다.
 * - SLO 충족: 측정 구간 threshold가 하나 이상 있고 모두 통과했으며, 보내지 못한 요청을 실패로 넣은 가용성이
 *   `experiments/slo.json`의 `availability` 이상이다. 가용성 = `availability{scenario:measure}`의 `passes`
 *   ÷ (`http_reqs{scenario:measure}`의 `count` + `dropped_iterations{scenario:measure}`의 `count`).
 *   k6의 `availability` Rate는 보낸 요청만 세므로 보내지 못한 요청은 이 계산으로만 들어간다.
 *   이 계산으로 넘었고 k6 `availability` threshold는 통과했으면 `failedThresholds`에
 *   `"availability{scenario:measure}: 보내지 못한 요청 포함 rate>=<SLO>"`를 넣는다.
 * - 기대 결과 파일: 기본은 `experiments/expectations.json`. 형식은 `{"stage": "고도화 4", "scenarios": {"S-01": {"availability": "meet", "p99": "meet", "degradeRatio": "meet"}, "S-02": "meet", ...}}`.
 *   시나리오 값이 문자열(`meet`, `miss`)이면 두 기준(가용성, p99)의 기대가 모두 그 값이고, 객체면 기준마다 따로다. 객체에는 두 기준이 모두 있어야 하고,
 *   `degradeRatio`는 있을 때만 판정한다(F-002).
 * - degrade 비율 기준: 의존성 5개의 degrade 비율(`lib/degrade.sh`, [ExperimentDegradeRatioScenarioTest])이 모두 `experiments/slo.json`의
 *   `degradeRatio`(0.01)보다 작으면 지킨 것이다. 200 응답이 없어 비율이 "없음"이면 지키지 못한 것이다. 지키지 못하면 `failedThresholds`에
 *   `"degradeRatio: <의존성> <비율> >= <상한>"`을 의존성마다 넣는다. 기대에 `degradeRatio`가 없으면 `criteria`에도 없다.
 * - 판정 입력 파일(넷째 인자, F-005): `run.sh`가 Prometheus에서 잰 값을 결과 폴더의 `verdict-input.json`으로 넘긴다(add-retry-policy design 결정 10).
 *   ```
 *   {
 *     "requestRatioMax": { "post": 1.0, "user": 1.07, "like": 1.08, "recommendation": 1.0, "ad": 0.99 },  // 의존성 5개. 질의가 실패하면 null
 *     "adRejections": { "rejected": 120, "allowed": 1240 }  // ad-service가 돌려준 429 수, 429를 받아도 되는 수. 모르면 null
 *   }
 *   ```
 *   기대 객체에 `requestRatio`나 `retryAfter`가 있는데 판정 입력 파일을 주지 않았거나 읽을 수 없으면 판정하지 못함(2)이고, 표준 오류에
 *   "판정 입력 파일"이 필요하다고 쓴다. 두 키가 없는 기대는 파일을 읽지 않는다.
 * - 요청 배율 기준(`requestRatio`): 의존성 5개의 `requestRatioMax`가 모두 `experiments/slo.json`의 `requestRatio`(1.2) 이하면 지킨 것이다.
 *   넘거나 null인 의존성마다 `failedThresholds`에 `"requestRatio: <의존성> <값> > <상한>"`(null이면 `"requestRatio: <의존성> 값 없음"`)을 넣는다.
 * - Retry-After 기준(`retryAfter`): `adRejections.rejected`가 `adRejections.allowed` 이하면 지킨 것이다. 넘으면
 *   `"retryAfter: ad 429 <rejected> > <allowed>"`, 둘 중 하나라도 null이면 `"retryAfter: 값 없음"`을 넣는다.
 * - 기준: `availability{scenario:measure}`의 threshold와 보내지 못한 요청을 넣은 가용성은 가용성, `feed_latency{scenario:measure}`의 threshold는
 *   p99다. 그 밖의 측정 구간 threshold(`http_reqs` 등의 `count>=0`)는 기준에 들지 않는다. 그런 threshold가 넘으면 `sloMet`은 false이고
 *   기대와 다른 것으로 본다(`mismatchedCriteria`에 `other`).
 *
 * **출력:** 표준 출력에 JSON 하나. 안내와 오류는 표준 오류에 쓴다.
 * ```
 * {
 *   "scenario": "S-02",
 *   "sloMet": false,
 *   "expectation": {"availability": "miss", "p99": "meet"},
 *   "criteria": {
 *     "availability": {"met": false, "expectation": "miss"},
 *     "p99": {"met": true, "expectation": "meet"}
 *   },
 *   "mismatchedCriteria": [],
 *   "matchesExpectation": true,
 *   "verdict": "SLO 미충족, 기대와 같음",
 *   "failedThresholds": ["availability{scenario:measure}: rate>=0.999"]
 * }
 * ```
 * - `expectation`: 기대 결과 파일의 값 그대로(문자열이나 객체)
 * - `sloMet`: 측정 구간 threshold가 모두 통과했다. `criteria`의 `met`은 기준마다의 같은 판단이다
 * - `sloMet`: 측정 구간 threshold와 판정한 기준(degrade 비율 포함)이 모두 통과했다
 * - `mismatchedCriteria`: `met`이 기대와 다른 기준(`availability`, `p99`, `degradeRatio`, `requestRatio`, `retryAfter`, `other`). `criteria`의 순서도 이 순서다. 비어 있으면 `matchesExpectation`이 true다
 * - `verdict`: `SLO 충족` 또는 `SLO 미충족`, 그리고 `기대와 같음` 또는 `기대와 다름`을 `, `로 잇는다. 리포트에 그대로 싣는다.
 * - `failedThresholds`: 넘은 측정 구간 threshold마다 `"<지표>: <기준>"`. 없으면 빈 배열
 *
 * **종료 코드:** 0 기대와 같음, 1 기대와 다름, 2 판정하지 못함(요약 파일이 없거나 JSON이 아님, 측정 구간 threshold가 없음,
 * 기대 결과에 시나리오가 없음, 판정 입력이 필요한데 없음). 2일 때는 표준 출력에 아무것도 쓰지 않는다.
 */
@Tags("Integration")
class ExperimentVerdictScenarioTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val expectations = ExperimentScripts.fixture("expectations-stage2.json").toString()
        val stage3 = ExperimentScripts.fixture("expectations-stage3.json").toString()
        val stage4 = ExperimentScripts.fixture("expectations-stage4.json").toString()
        val stage5 = ExperimentScripts.fixture("expectations-stage5.json").toString()

        fun verdict(
            summary: String,
            scenario: String,
            expectationsFile: String = expectations,
            verdictInput: String? = null,
        ): Pair<ScriptResult, Map<String, Any?>> {
            val args =
                listOf(ExperimentScripts.fixture(summary).toString(), scenario, expectationsFile) +
                    listOfNotNull(verdictInput?.let { ExperimentScripts.fixture(it).toString() })
            val result = ExperimentScripts.run("lib/verdict.sh", *args.toTypedArray())
            val output = withClue(result) { mapper.readValue<Map<String, Any?>>(result.stdout) }
            return result to output
        }

        test("F-007 기대대로 SLO를 못 지킨다: 기대가 miss인 S-02가 가용성과 p99 기준을 모두 넘으면 'SLO 미충족, 기대와 같음'이고 0으로 끝난다") {
            val (result, output) = verdict("summary-S-02-both-over.json", "S-02")
            withClue(result) {
                output["scenario"] shouldBe "S-02"
                output["sloMet"] shouldBe false
                output["expectation"] shouldBe "miss"
                output["criteria"] shouldBe
                    mapOf(
                        "availability" to mapOf("met" to false, "expectation" to "miss"),
                        "p99" to mapOf("met" to false, "expectation" to "miss"),
                    )
                (output["mismatchedCriteria"] as List<*>).shouldBeEmpty()
                output["matchesExpectation"] shouldBe true
                output["verdict"] shouldBe "SLO 미충족, 기대와 같음"
                (output["failedThresholds"] as List<*>) shouldContainExactlyInAnyOrder
                    listOf("feed_latency{scenario:measure}: p(99)<300", "availability{scenario:measure}: rate>=0.999")
                result.exitCode shouldBe 0
            }
        }

        test("F-001 기준별 기대와 같다: 기대가 가용성 miss·p99 meet인 S-02가 p99는 지키고 가용성은 넘으면 'SLO 미충족, 기대와 같음'이고 0으로 끝난다") {
            val (result, output) = verdict("summary-S-02-availability-over.json", "S-02", stage3)
            withClue(result) {
                output["sloMet"] shouldBe false
                output["expectation"] shouldBe mapOf("availability" to "miss", "p99" to "meet")
                output["criteria"] shouldBe
                    mapOf(
                        "availability" to mapOf("met" to false, "expectation" to "miss"),
                        "p99" to mapOf("met" to true, "expectation" to "meet"),
                    )
                (output["mismatchedCriteria"] as List<*>).shouldBeEmpty()
                output["matchesExpectation"] shouldBe true
                output["verdict"] shouldBe "SLO 미충족, 기대와 같음"
                (output["failedThresholds"] as List<*>) shouldContainExactly listOf("availability{scenario:measure}: rate>=0.999")
                result.exitCode shouldBe 0
            }
        }

        test("F-001 기준별 기대와 다르다: 기대가 가용성 miss·p99 meet인 S-02가 가용성과 p99를 모두 넘으면 'SLO 미충족, 기대와 다름'이고 기대와 다른 기준은 p99다") {
            val (result, output) = verdict("summary-S-02-both-over.json", "S-02", stage3)
            withClue(result) {
                output["sloMet"] shouldBe false
                (output["mismatchedCriteria"] as List<*>) shouldContainExactly listOf("p99")
                output["matchesExpectation"] shouldBe false
                output["verdict"] shouldBe "SLO 미충족, 기대와 다름"
                result.exitCode shouldBe 1
            }
        }

        test(
            "F-005 F-003 지금 단계의 기대: experiments/expectations.json은 고도화 6이고 S-01~S-09 모두 가용성·p99·요청 배율이 meet, S-01은 degrade 비율, S-07은 Retry-After도 meet이다",
        ) {
            val actual = mapper.readValue<Map<String, Any?>>(ExperimentScripts.dir.resolve("expectations.json").toFile())
            actual["stage"] shouldBe "고도화 6"
            val scenarios = actual["scenarios"] as Map<*, *>
            scenarios.keys shouldBe (1..9).map { "S-0$it" }.toSet()
            val base = mapOf("availability" to "meet", "p99" to "meet", "requestRatio" to "meet")
            scenarios["S-01"] shouldBe base + ("degradeRatio" to "meet")
            scenarios["S-07"] shouldBe base + ("retryAfter" to "meet")
            listOf(2, 3, 4, 5, 6, 8, 9).forEach { n ->
                withClue("S-0$n") { scenarios["S-0$n"] shouldBe base }
            }
        }

        test(
            "F-005 재시도가 하위 서비스 요청을 늘린다: 기대가 요청 배율까지 meet인 S-09가 가용성과 p99는 지키고 like 요청 배율 최댓값이 1.35면 'SLO 미충족, 기대와 다름'이고 기대와 다른 기준은 요청 배율이다",
        ) {
            val (result, output) = verdict("summary-S-01-met.json", "S-09", stage5, "verdict-input-S-09-amplified.json")
            withClue(result) {
                output["sloMet"] shouldBe false
                output["criteria"] shouldBe
                    mapOf(
                        "availability" to mapOf("met" to true, "expectation" to "meet"),
                        "p99" to mapOf("met" to true, "expectation" to "meet"),
                        "requestRatio" to mapOf("met" to false, "expectation" to "meet"),
                    )
                (output["mismatchedCriteria"] as List<*>) shouldContainExactly listOf("requestRatio")
                output["verdict"] shouldBe "SLO 미충족, 기대와 다름"
                (output["failedThresholds"] as List<*>) shouldContainExactly listOf("requestRatio: like 1.35 > 1.2")
                result.exitCode shouldBe 1
            }
        }

        test("F-005 상한 안이다: 기대가 요청 배율까지 meet인 S-09가 가용성과 p99를 지키고 요청 배율 최댓값이 모두 1.08 이하면 'SLO 충족, 기대와 같음'이다") {
            val (result, output) = verdict("summary-S-01-met.json", "S-09", stage5, "verdict-input-S-09-within.json")
            withClue(result) {
                output["sloMet"] shouldBe true
                (output["criteria"] as Map<*, *>).keys shouldBe setOf("availability", "p99", "requestRatio")
                output["verdict"] shouldBe "SLO 충족, 기대와 같음"
                (output["failedThresholds"] as List<*>).shouldBeEmpty()
                result.exitCode shouldBe 0
            }
        }

        test("F-005 판정 입력이 없다: 기대가 요청 배율까지 meet인 S-09를 판정 입력 파일 없이 판정하면 2로 끝나고 판정 입력 파일이 필요하다고 알린다") {
            val result =
                ExperimentScripts.run("lib/verdict.sh", ExperimentScripts.fixture("summary-S-01-met.json").toString(), "S-09", stage5)
            withClue(result) {
                result.exitCode shouldBe 2
                result.stdout.trim() shouldBe ""
                result.stderr shouldContain "판정 입력 파일"
            }
        }

        test(
            "F-005 Retry-After를 지키지 않는다: 기대가 Retry-After까지 meet인 S-07에서 ad 429가 60,000개이고 받아도 되는 수가 1,240개면 'SLO 미충족, 기대와 다름'이고 기대와 다른 기준은 Retry-After다",
        ) {
            val (result, output) = verdict("summary-S-01-met.json", "S-07", stage5, "verdict-input-S-07-ignored.json")
            withClue(result) {
                output["sloMet"] shouldBe false
                (output["criteria"] as Map<*, *>)["retryAfter"] shouldBe mapOf("met" to false, "expectation" to "meet")
                (output["mismatchedCriteria"] as List<*>) shouldContainExactly listOf("retryAfter")
                output["verdict"] shouldBe "SLO 미충족, 기대와 다름"
                (output["failedThresholds"] as List<*>) shouldContainExactly listOf("retryAfter: ad 429 60000 > 1240")
                result.exitCode shouldBe 1
            }
        }

        test("F-005 Retry-After를 지킨다: 기대가 Retry-After까지 meet인 S-07에서 ad 429가 120개이고 받아도 되는 수가 1,240개면 'SLO 충족, 기대와 같음'이다") {
            val (result, output) = verdict("summary-S-01-met.json", "S-07", stage5, "verdict-input-S-07-honored.json")
            withClue(result) {
                output["sloMet"] shouldBe true
                (output["criteria"] as Map<*, *>).keys shouldBe setOf("availability", "p99", "requestRatio", "retryAfter")
                output["verdict"] shouldBe "SLO 충족, 기대와 같음"
                result.exitCode shouldBe 0
            }
        }

        test(
            "F-002 평시에 영역이 빠진다: 기대가 degrade 비율까지 meet인 S-01이 가용성과 p99는 지키고 like degrade 비율이 0.02면 'SLO 미충족, 기대와 다름'이고 기대와 다른 기준은 degrade 비율이다",
        ) {
            val (result, output) = verdict("summary-S-01-like-degraded.json", "S-01", stage4)
            withClue(result) {
                output["sloMet"] shouldBe false
                output["criteria"] shouldBe
                    mapOf(
                        "availability" to mapOf("met" to true, "expectation" to "meet"),
                        "p99" to mapOf("met" to true, "expectation" to "meet"),
                        "degradeRatio" to mapOf("met" to false, "expectation" to "meet"),
                    )
                (output["mismatchedCriteria"] as List<*>) shouldContainExactly listOf("degradeRatio")
                output["verdict"] shouldBe "SLO 미충족, 기대와 다름"
                (output["failedThresholds"] as List<*>) shouldContainExactly listOf("degradeRatio: like 0.02 >= 0.01")
                result.exitCode shouldBe 1
            }
        }

        test("F-002 장애 시나리오의 degrade 비율은 판정하지 않는다: 기대가 meet인 S-02가 가용성과 p99는 지키고 recommendation degrade 비율이 1이면 'SLO 충족, 기대와 같음'이다") {
            val (result, output) = verdict("summary-S-02-recommendation-degraded.json", "S-02", stage4)
            withClue(result) {
                output["sloMet"] shouldBe true
                (output["criteria"] as Map<*, *>).keys shouldBe setOf("availability", "p99")
                output["verdict"] shouldBe "SLO 충족, 기대와 같음"
                result.exitCode shouldBe 0
            }
        }

        test("F-007 기대와 다르다: 기대가 meet인 S-01이 p99 기준을 넘으면 'SLO 미충족, 기대와 다름'이고 판정 코드는 1이다") {
            val (result, output) = verdict("summary-S-01-p99-over.json", "S-01")
            withClue(result) {
                output["sloMet"] shouldBe false
                output["expectation"] shouldBe "meet"
                output["matchesExpectation"] shouldBe false
                output["verdict"] shouldBe "SLO 미충족, 기대와 다름"
                (output["failedThresholds"] as List<*>) shouldContainExactly listOf("feed_latency{scenario:measure}: p(99)<300")
                result.exitCode shouldBe 1
            }
        }

        test("F-007 기대와 같다: 측정 구간 threshold가 모두 통과한 S-01은 준비 구간 threshold가 넘어도 'SLO 충족, 기대와 같음'이다") {
            val (result, output) = verdict("summary-S-01-met.json", "S-01")
            withClue(result) {
                output["sloMet"] shouldBe true
                output["matchesExpectation"] shouldBe true
                output["verdict"] shouldBe "SLO 충족, 기대와 같음"
                (output["failedThresholds"] as List<*>).shouldBeEmpty()
                result.exitCode shouldBe 0
            }
        }

        test("F-007 기대와 다르다: 기대가 miss인 S-02가 SLO를 지키면 'SLO 충족, 기대와 다름'이고 판정 코드는 1이다") {
            val (result, output) = verdict("summary-S-01-met.json", "S-02")
            withClue(result) {
                output["sloMet"] shouldBe true
                output["verdict"] shouldBe "SLO 충족, 기대와 다름"
                result.exitCode shouldBe 1
            }
        }

        test("F-007 보내지 못한 요청도 실패다: 보낸 요청이 모두 200이어도 보내지 못한 요청이 0.2%인 S-01은 'SLO 미충족, 기대와 다름'이다") {
            val (result, output) = verdict("summary-S-01-dropped.json", "S-01")
            withClue(result) {
                output["sloMet"] shouldBe false
                output["verdict"] shouldBe "SLO 미충족, 기대와 다름"
                (output["failedThresholds"] as List<*>) shouldContainExactly
                    listOf("availability{scenario:measure}: 보내지 못한 요청 포함 rate>=0.999")
                result.exitCode shouldBe 1
            }
        }

        test("F-007 판정하지 못함: k6 요약 파일이 없으면 2로 끝나고 이유를 표준 오류에 쓴다") {
            val missing = ExperimentScripts.fixture("expectations-stage2.json").resolveSibling("no-such-summary.json").toString()
            val result = ExperimentScripts.run("lib/verdict.sh", missing, "S-01", expectations)
            withClue(result) {
                result.exitCode shouldBe 2
                result.stdout.trim() shouldBe ""
                result.stderr.shouldNotBeBlank()
            }
        }
    })
