package com.weave.e2e

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

/**
 * `fault-experiments` 델타의 S-11 판정 시나리오 (F-003). 미리 만든 결과 파일로 판정 스크립트만 돌린다.
 *
 * **명령:** `experiments/lib/like-verdict.sh <방식별 결과 파일>`
 * - 결과 파일: `{"runs": [{"strategy": "atomic", "shards": 1, "requests": 1000, "failed": 0, "mismatch": 0, ...}]}`.
 *   `strategy`는 `read-modify-write`, `atomic`, `sharded`다. 판정은 `strategy`, `shards`, `failed`, `mismatch`만 본다.
 * - 정합성을 보장하는 방식(`atomic`, `sharded`)은 `failed`가 0이고 `mismatch`가 0이어야 한다. `mismatch`가 null이면 깨진 것으로 본다.
 * - `read-modify-write`는 종료 코드에 영향을 주지 않는다. `mismatch`가 0이면 `notReproduced`에 넣는다.
 *
 * **출력:** 표준 출력에 JSON 하나. 안내와 오류는 표준 오류에 쓴다.
 * ```
 * {
 *   "verdict": "정합성 유지",   // 또는 "정합성 깨짐"
 *   "brokenRuns": [{"strategy": "atomic", "shards": 1, "reasons": ["mismatch 3"]}],   // reasons는 "mismatch <값>", "mismatch 값 없음", "failed <값>"
 *   "notReproduced": ["read-modify-write"]
 * }
 * ```
 *
 * **종료 코드:** 0 정합성 유지, 1 정합성 깨짐, 2 판정하지 못함(파일이 없거나 JSON이 아니거나 `runs`가 비어 있음). 2일 때는 표준 출력에 아무것도 쓰지 않는다.
 */
@Tags("Integration")
class ExperimentLikeVerdictScenarioTest :
    FunSpec({
        val mapper = jacksonObjectMapper()

        fun verdict(path: String): Pair<ScriptResult, Map<String, Any?>> {
            val result = ExperimentScripts.run("lib/like-verdict.sh", path)
            val output = runCatching { mapper.readValue<Map<String, Any?>>(result.stdout) }.getOrDefault(emptyMap())
            return result to output
        }

        fun fixture(name: String) = verdict(ExperimentScripts.fixture(name).toString())

        test("F-003 정합성을 지킨다: atomic과 sharded의 불일치와 실패가 0이면 0으로 끝나고 기준선 불일치는 실패로 세지 않는다") {
            val (result, output) = fixture("like-verdict-ok.json")

            result.exitCode shouldBe 0
            output["verdict"] shouldBe "정합성 유지"
            (output["brokenRuns"] as List<*>).shouldBeEmpty()
            (output["notReproduced"] as List<*>).shouldBeEmpty()
        }

        test("F-003 정합성을 보장하는 방식이 어긋난다: atomic의 mismatch가 3이면 1로 끝나고 어긋난 방식이 있다") {
            val (result, output) = fixture("like-verdict-atomic-mismatch.json")

            result.exitCode shouldBe 1
            output["verdict"] shouldBe "정합성 깨짐"
            output["brokenRuns"] shouldBe listOf(mapOf("strategy" to "atomic", "shards" to 1, "reasons" to listOf("mismatch 3")))
        }

        test("F-003 정합성을 보장하는 방식에 실패가 있다: sharded의 failed가 2면 1로 끝난다") {
            val (result, output) = fixture("like-verdict-sharded-failed.json")

            result.exitCode shouldBe 1
            output["brokenRuns"] shouldBe listOf(mapOf("strategy" to "sharded", "shards" to 8, "reasons" to listOf("failed 2")))
        }

        test("F-003 불일치 값을 모른다: atomic의 mismatch가 null이면 1로 끝난다") {
            val (result, output) = fixture("like-verdict-mismatch-unknown.json")

            result.exitCode shouldBe 1
            output["brokenRuns"] shouldBe listOf(mapOf("strategy" to "atomic", "shards" to 1, "reasons" to listOf("mismatch 값 없음")))
        }

        test("F-003 기준선이 깨지지 않았다: read-modify-write의 mismatch가 0이면 종료 코드에 영향이 없고 notReproduced에 있다") {
            val (result, output) = fixture("like-verdict-baseline-clean.json")

            result.exitCode shouldBe 0
            output["notReproduced"] shouldBe listOf("read-modify-write")
        }

        test("F-003 판정하지 못한다: JSON이 아니면 2로 끝나고 표준 출력은 비어 있다") {
            val file = createTempFile("like-verdict", ".json").apply { writeText("not json") }

            val (result, _) = verdict(file.toString())

            result.exitCode shouldBe 2
            result.stdout shouldBe ""
        }
    })
