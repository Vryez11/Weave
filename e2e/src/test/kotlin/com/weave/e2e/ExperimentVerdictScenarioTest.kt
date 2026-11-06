package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `fault-experiments` 스펙의 "기대대로 SLO를 못 지킨다", "기대와 다르다" 시나리오 (F-007).
 *
 * 미리 만든 k6 요약 파일로 판정 스크립트만 돌린다. Docker와 Compose 환경은 쓰지 않는다(design 결정 7, 8, 13).
 *
 * **명령:** `experiments/lib/verdict.sh <k6 요약 파일> <시나리오 ID> [기대 결과 파일]`
 * - k6 요약 파일: `k6 run --summary-export`의 결과. `metrics`의 키 중 `{scenario:measure}`가 붙은 지표의 threshold만 본다.
 *   이 형식에서 threshold 값 `true`는 기준을 넘었다(실패), `false`는 통과라는 뜻이다.
 * - SLO 충족: 측정 구간 threshold가 하나 이상 있고 모두 통과했으며, 보내지 못한 요청을 실패로 넣은 가용성이
 *   `experiments/slo.json`의 `availability` 이상이다. 가용성 = `availability{scenario:measure}`의 `passes`
 *   ÷ (`http_reqs{scenario:measure}`의 `count` + `dropped_iterations{scenario:measure}`의 `count`).
 *   k6의 `availability` Rate는 보낸 요청만 세므로 보내지 못한 요청은 이 계산으로만 들어간다.
 *   이 계산으로 넘었고 k6 `availability` threshold는 통과했으면 `failedThresholds`에
 *   `"availability{scenario:measure}: 보내지 못한 요청 포함 rate>=<SLO>"`를 넣는다.
 * - 기대 결과 파일: 기본은 `experiments/expectations.json`. 형식은 `{"stage": "고도화 2", "scenarios": {"S-01": "meet", "S-02": "miss", ...}}`
 *
 * **출력:** 표준 출력에 JSON 하나. 안내와 오류는 표준 오류에 쓴다.
 * ```
 * {
 *   "scenario": "S-02",
 *   "sloMet": false,
 *   "expectation": "miss",
 *   "matchesExpectation": true,
 *   "verdict": "SLO 미충족, 기대와 같음",
 *   "failedThresholds": ["feed_latency{scenario:measure}: p(99)<300"]
 * }
 * ```
 * - `verdict`: `SLO 충족` 또는 `SLO 미충족`, 그리고 `기대와 같음` 또는 `기대와 다름`을 `, `로 잇는다. 리포트에 그대로 싣는다.
 * - `failedThresholds`: 넘은 측정 구간 threshold마다 `"<지표>: <기준>"`. 없으면 빈 배열
 *
 * **종료 코드:** 0 기대와 같음, 1 기대와 다름, 2 판정하지 못함(요약 파일이 없거나 JSON이 아님, 측정 구간 threshold가 없음,
 * 기대 결과에 시나리오가 없음). 2일 때는 표준 출력에 아무것도 쓰지 않는다.
 */
@Tags("Integration")
class ExperimentVerdictScenarioTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val expectations = ExperimentScripts.fixture("expectations-stage2.json").toString()

        fun verdict(
            summary: String,
            scenario: String,
        ): Pair<ScriptResult, Map<String, Any?>> {
            val result = ExperimentScripts.run("lib/verdict.sh", ExperimentScripts.fixture(summary).toString(), scenario, expectations)
            val output = withClue(result) { mapper.readValue<Map<String, Any?>>(result.stdout) }
            return result to output
        }

        test("F-007 기대대로 SLO를 못 지킨다: 기대가 miss인 S-02가 p99 기준을 넘으면 'SLO 미충족, 기대와 같음'이고 0으로 끝난다") {
            val (result, output) = verdict("summary-S-02-p99-over.json", "S-02")
            withClue(result) {
                output["scenario"] shouldBe "S-02"
                output["sloMet"] shouldBe false
                output["expectation"] shouldBe "miss"
                output["matchesExpectation"] shouldBe true
                output["verdict"] shouldBe "SLO 미충족, 기대와 같음"
                (output["failedThresholds"] as List<*>) shouldContainExactly listOf("feed_latency{scenario:measure}: p(99)<300")
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
