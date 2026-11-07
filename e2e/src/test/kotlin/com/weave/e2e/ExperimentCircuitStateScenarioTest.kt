package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `fault-experiments` 델타의 "서킷이 열린 시나리오" (F-001).
 *
 * 미리 만든 Prometheus `query_range` 결과로 서킷 상태를 이어 적는 스크립트만 돌린다. Docker와 Compose 환경은 쓰지 않는다
 * (add-call-isolation design 결정 10).
 *
 * **명령:** `experiments/lib/circuits.sh <query_range 결과 파일>`
 * - 결과 파일: Prometheus `/api/v1/query_range`에 `resilience4j_circuitbreaker_state`를 측정 구간·5초 간격으로 물은 응답 본문 그대로
 *   (`{"status": "success", "data": {"resultType": "matrix", "result": [...]}}`). 시계열은 (`name`, `state`)마다 하나이고,
 *   값은 그 시각에 그 상태면 `"1"`, 아니면 `"0"`이다. `state`는 Resilience4j의 소문자 이름(`closed`, `open`, `half_open` 등)이다.
 * - 의존성은 `post`, `user`, `like`, `recommendation`, `ad` 다섯이다(`name` 라벨). 다른 이름의 시계열은 건너뛴다.
 *
 * **출력:** 표준 출력에 JSON 하나, 종료 코드 0. 결과 파일이 없거나 형식이 아니면 2로 끝나고 이유를 표준 오류에 쓴다.
 * ```
 * { "post": "CLOSED", "user": "CLOSED", "like": "CLOSED", "recommendation": "CLOSED → OPEN → HALF_OPEN → OPEN", "ad": "CLOSED" }
 * ```
 * - 의존성마다 값이 `1`인 상태를 시각 순서로 읽어 대문자로 바꾸고, 바로 앞과 같은 상태는 한 번만 적은 뒤 ` → `로 잇는다.
 * - 그 의존성의 시계열이 없으면 `"없음"`이다(MVP처럼 서킷이 없는 구성).
 * - 5초 간격 표본이라 그보다 짧게 머문 상태는 빠질 수 있다. 리포트가 그렇게 적는다.
 */
@Tags("Integration")
class ExperimentCircuitStateScenarioTest :
    FunSpec({
        val mapper = jacksonObjectMapper()

        fun circuits(fixture: String): Pair<ScriptResult, Map<String, Any?>> {
            val result = ExperimentScripts.run("lib/circuits.sh", ExperimentScripts.fixture(fixture).toString())
            val output = withClue(result) { mapper.readValue<Map<String, Any?>>(result.stdout) }
            return result to output
        }

        test("F-001 서킷이 열린 시나리오: recommendation이 닫힘·열림·반열림을 거쳐 다시 열렸으면 CLOSED → OPEN → HALF_OPEN → OPEN이고 나머지는 CLOSED다") {
            val (result, output) = circuits("query-range-circuit-state.json")
            withClue(result) {
                output shouldBe
                    mapOf(
                        "post" to "CLOSED",
                        "user" to "CLOSED",
                        "like" to "CLOSED",
                        "recommendation" to "CLOSED → OPEN → HALF_OPEN → OPEN",
                        "ad" to "CLOSED",
                    )
                result.exitCode shouldBe 0
            }
        }

        test("F-001 서킷 지표가 없다: 시계열이 하나도 없으면 다섯 의존성 모두 '없음'이다") {
            val (result, output) = circuits("query-range-circuit-state-empty.json")
            withClue(result) {
                output shouldBe DependencyNames.all.associateWith { "없음" }
                result.exitCode shouldBe 0
            }
        }

        test("F-001 결과 파일이 없다: 2로 끝나고 이유를 표준 오류에 쓴다") {
            val missing = ExperimentScripts.fixture("query-range-circuit-state.json").resolveSibling("no-such-range.json").toString()
            val result = ExperimentScripts.run("lib/circuits.sh", missing)
            withClue(result) {
                result.exitCode shouldBe 2
                result.stdout.trim() shouldBe ""
                result.stderr.shouldNotBeBlank()
            }
        }
    })

private object DependencyNames {
    val all = listOf("post", "user", "like", "recommendation", "ad")
}
