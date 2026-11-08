package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `fault-experiments` 스펙 "degrade 비율"의 "비율을 계산한다" 시나리오 (F-002).
 *
 * 미리 만든 k6 요약 파일로 계산 스크립트만 돌린다. Docker와 Compose 환경은 쓰지 않는다(add-graceful-degradation design 결정 12).
 * "선택 의존성이 느리다"(실제 S-02 짧은 실행)는 Compose 테스트 [ExperimentRunComposeScenarioTest]에 있다.
 *
 * **명령:** `experiments/lib/degrade.sh <k6 요약 파일>`
 * - k6 요약 파일: `k6 run --summary-export`의 결과. 아래 측정 구간 지표의 값을 읽는다. 지표가 없으면 0으로 본다.
 *   - `availability{scenario:measure}`의 `passes`: 측정 구간의 200 응답 수(분모)
 *   - `degraded_sections{scenario:measure,section:<의존성>}`의 `count`: 그 의존성이 `degradation.missing`이나 `degradation.stale`에 있던 200 응답 수.
 *     의존성은 `post`, `user`, `like`, `recommendation`, `ad`다. k6 요약에 나오도록 `feed.js`가 하위 지표마다 `count>=0` 기준을 단다
 *   - `degraded_responses{scenario:measure}`의 `count`: 영역이 하나라도 빠지거나 stale인 200 응답 수
 *
 * **출력:** 표준 출력에 JSON 하나. 의존성 5개와 `any`마다 비율(소수 넷째 자리에서 반올림).
 * ```
 * {"post": 0.1, "user": 0, "like": 0, "recommendation": 0.25, "ad": 0, "any": 0.3}
 * ```
 * 200 응답이 없으면 JSON 문자열 `"없음"`이다.
 *
 * **종료 코드:** 0 계산함("없음" 포함), 2 계산하지 못함(파일이 없거나 JSON이 아님). 2일 때는 표준 출력에 아무것도 쓰지 않는다.
 */
@Tags("Integration")
class ExperimentDegradeRatioScenarioTest :
    FunSpec({
        val mapper = jacksonObjectMapper()

        fun degrade(summary: String): Pair<ScriptResult, Any?> {
            val result = ExperimentScripts.run("lib/degrade.sh", ExperimentScripts.fixture(summary).toString())
            val output = withClue(result) { mapper.readValue<Any?>(result.stdout) }
            return result to output
        }

        test(
            "F-002 비율을 계산한다: 200 응답 200개 중 recommendation 빠짐 50개, post stale 20개, 겹침 10개면 recommendation 0.25, post 0.1, 나머지 0, 하나라도 0.3이다",
        ) {
            val (result, output) = degrade("summary-degrade-ratio.json")
            withClue(result) {
                result.exitCode shouldBe 0
                output shouldBe
                    mapOf("post" to 0.1, "user" to 0, "like" to 0, "recommendation" to 0.25, "ad" to 0, "any" to 0.3)
            }
        }

        test("F-002 200 응답이 없으면 '없음'이다") {
            val (result, output) = degrade("summary-no-200.json")
            withClue(result) {
                result.exitCode shouldBe 0
                output shouldBe "없음"
            }
        }

        test("F-002 계산하지 못함: k6 요약 파일이 없으면 2로 끝나고 이유를 표준 오류에 쓴다") {
            val missing = ExperimentScripts.fixture("summary-degrade-ratio.json").resolveSibling("no-such-summary.json").toString()
            val result = ExperimentScripts.run("lib/degrade.sh", missing)
            withClue(result) {
                result.exitCode shouldBe 2
                result.stdout.trim() shouldBe ""
                result.stderr.shouldNotBeBlank()
            }
        }
    })
