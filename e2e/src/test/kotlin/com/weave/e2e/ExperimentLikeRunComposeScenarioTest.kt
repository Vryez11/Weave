package com.weave.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes

/**
 * `fault-experiments` 델타의 S-10, S-11 시나리오 (F-003, F-005). `:e2e:composeTest`가 띄운 환경에서 `run-like.sh`를 프로세스로 돌린다.
 *
 * **명령:** `experiments/run-like.sh S-10`, `experiments/run-like.sh S-11 [--strategy read-modify-write|atomic|sharded] [--rps N] [--warmup 5s] [--duration 20s] [--shards 4,8,16]`
 * - 옵션 기본값은 `experiments/like-config.json`이다. `--shards`는 `sharded`로 돌릴 분할 수 목록이다.
 * - 표준 출력은 마지막 두 줄(`<ID>: <판정>`, `리포트: <레포 루트 기준 경로>/report.md`)이고 진행 상황은 표준 오류로 간다.
 * - 종료 코드: S-10은 0 모든 경우가 기대와 같음, 1 다름. S-11은 `lib/like-verdict.sh`와 같다. 2는 실행하지 못함(없는 시나리오, 환경 없음, k6 실패).
 * - 끝나면(실패·중단해도) 장애를 모두 되돌리고, S-11은 like-service를 설정 덮어쓰기 없이 다시 띄운다.
 * - 결과 폴더는 `experiments/results/<UTC yyyyMMdd-HHmmss>-<S-10|S-11>/`이다.
 *
 * **S-10 `report.json`**
 * ```
 * {
 *   "scenario": "S-10", "commit": "...", "startedAt": "...", "verdict": "모두 기대와 같음",   // 또는 "기대와 다름"
 *   "cases": [
 *     { "name": "duplicate" | "concurrent" | "reorder" | "timeout-resend", "passed": true,
 *       "expected": {"liked": false, "likeCount": 0}, "actual": {"liked": false, "likeCount": 0}, "mismatches": 0,
 *       // timeout-resend만: 첫 응답 코드, 그때 like-service에 이미 반영돼 있었는지
 *       "firstStatus": 500, "appliedBeforeResend": true }
 *   ]
 * }
 * ```
 *
 * **S-11 `report.json`**
 * ```
 * {
 *   "scenario": "S-11", "commit": "...", "startedAt": "...", "rps": 5, "warmup": "3s", "duration": "10s",
 *   "verdict": "정합성 유지", "brokenRuns": [], "notReproduced": [],
 *   "runs": [
 *     { "strategy": "atomic", "shards": 1, "requests": 50, "failed": 0, "throughputRps": 5.0, "p50Ms": 3.1, "p99Ms": 9.8,
 *       "rowLockWaits": 2, "rowLockTimeMs": 11, "deadlocks": 0, "likeCount": 50, "likedRows": 50, "mismatch": 0,
 *       "hikariPendingMax": 0, "likeCpuMax": 0.2, "mysqlCpuMax": 0.1 }
 *   ]
 * }
 * ```
 * `mismatch`는 측정 게시글의 |`likeCount` - `likedRows`|다. `report.md`는 같은 값을 표로 쓴다.
 */
@Tags("Compose")
class ExperimentLikeRunComposeScenarioTest :
    FunSpec({
        val short = arrayOf("--rps", "5", "--warmup", "3s", "--duration", "10s")

        fun overrideEnv(): String? {
            val result = ComposeEnvironment.dockerCompose("exec", "-T", "like-service", "printenv", "SPRING_APPLICATION_JSON")
            return if (result.exitCode == 0) result.stdout.trim() else null
        }

        fun shouldBeRestored() {
            withClue("like-service의 SPRING_APPLICATION_JSON") { overrideEnv() shouldBe "{}" }
            ExperimentRuns.shouldBeClean()
        }

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.list(key: String) = this[key] as List<Map<String, Any?>>

        fun runLike(
            scenario: String,
            vararg args: String,
        ): Map<String, Any?> {
            val before = ExperimentRuns.resultFolders()
            val result = ComposeEnvironment.experiment("run-like.sh", scenario, *args, timeout = 10.minutes)
            withClue("run-like.sh $scenario: $result") { result.exitCode shouldBe 0 }
            result.stdout.trim().lines().let { lines ->
                lines[lines.size - 2] shouldContain "$scenario:"
                lines.last() shouldContain "report.md"
            }
            return ExperimentRuns.report(ExperimentRuns.newResultFolder(before, scenario))
        }

        beforeSpec { ComposeEnvironment.warmUpFeed() }

        context("S-10") {
            test("F-003 F-005 모든 경우가 마지막 의도로 끝난다: 0으로 끝나고 네 경우마다 기대 상태, 실제 상태, 불일치 검사 결과가 있다") {
                val report = runLike("S-10")

                report["verdict"] shouldBe "모두 기대와 같음"
                val cases = report.list("cases")
                cases.map { it["name"] } shouldBe listOf("duplicate", "concurrent", "reorder", "timeout-resend")
                cases.forAll {
                    withClue("경우 $it") {
                        it["passed"] shouldBe true
                        it["actual"] shouldBe it["expected"]
                        it["mismatches"] shouldBe 0
                    }
                }
            }

            test("F-003 F-005 타임아웃 뒤의 재전송: 첫 응답은 실패이고 그때 이미 반영돼 있었으며 재전송 뒤 수는 1이다") {
                val case = runLike("S-10").list("cases").single { it["name"] == "timeout-resend" }

                withClue("timeout-resend: $case") {
                    (case["firstStatus"] as Number).toInt() shouldBe 500
                    case["appliedBeforeResend"] shouldBe true
                    case["actual"] shouldBe mapOf("liked" to true, "likeCount" to 1)
                }
            }

            test("F-003 F-005 S-10의 장애를 반드시 거둔다: 정상 종료 뒤와 like 프록시에 지연이 있는 중에 멈춘 뒤 장애가 없다") {
                runLike("S-10")
                ExperimentRuns.shouldBeClean()

                val started = ExperimentRuns.startScript("run-like.sh", "S-10")
                try {
                    eventually(3.minutes) {
                        withClue("run-like.sh 가 아직 돌고 있어야 한다: $started") { started.process.isAlive shouldBe true }

                        @Suppress("UNCHECKED_CAST")
                        val like = ExperimentRuns.proxies()["like"] as Map<String, Any?>
                        (like["toxics"] as List<*>).shouldNotBeEmpty()
                    }
                    started.process.destroy()
                    started.await(3.minutes)
                } finally {
                    if (started.process.isAlive) started.process.destroyForcibly().waitFor()
                }
                ExperimentRuns.shouldBeClean()
            }
        }

        context("S-11") {
            test("F-003 방식별 비교 리포트: 방식과 분할 수마다 행이 있고 처리량, p99, 실패, 잠금 대기, 교착, 수 불일치가 있다") {
                val before = ExperimentRuns.resultFolders()
                val result = ComposeEnvironment.experiment("run-like.sh", "S-11", *short, "--shards", "2,4", timeout = 10.minutes)
                withClue("run-like.sh S-11: $result") { result.exitCode shouldBe 0 }
                val folder = ExperimentRuns.newResultFolder(before, "S-11")
                val report = ExperimentRuns.report(folder)

                report["verdict"] shouldBe "정합성 유지"
                val runs = report.list("runs")
                runs.map { it["strategy"] to it["shards"] } shouldBe
                    listOf("read-modify-write" to 1, "atomic" to 1, "sharded" to 2, "sharded" to 4)
                runs.forAll { run ->
                    listOf(
                        "requests",
                        "failed",
                        "throughputRps",
                        "p50Ms",
                        "p99Ms",
                        "rowLockWaits",
                        "rowLockTimeMs",
                        "deadlocks",
                        "likeCount",
                        "likedRows",
                        "mismatch",
                    ).forEach { key -> withClue("$key: $run") { run[key].shouldNotBeNull() } }
                }
                folder.resolve("report.md").readText() shouldContain "read-modify-write"
                shouldBeRestored()
            }

            test("F-003 방식 하나만 돌린다: --strategy atomic이면 리포트에 원자적 UPDATE 행만 있고 불일치가 0이다") {
                val report = runLike("S-11", *short, "--strategy", "atomic")

                val runs = report.list("runs")
                runs.map { it["strategy"] } shouldBe listOf("atomic")
                (runs.single()["mismatch"] as Number).toInt() shouldBe 0
                shouldBeRestored()
            }

            test("F-003 like-service 설정을 되돌린다: 방식을 바꿔 띄운 중에 멈춰도 설정 덮어쓰기가 없고 장애 설정은 기본값이다") {
                val started =
                    ExperimentRuns.startScript(
                        "run-like.sh",
                        "S-11",
                        "--rps",
                        "5",
                        "--warmup",
                        "3s",
                        "--duration",
                        "60s",
                        "--strategy",
                        "atomic",
                    )
                try {
                    eventually(3.minutes) {
                        withClue("run-like.sh 가 아직 돌고 있어야 한다: $started") { started.process.isAlive shouldBe true }
                        withClue("덮어쓴 설정") { overrideEnv().orEmpty() shouldContain "counter" }
                    }
                    started.process.destroy()
                    started.await(3.minutes)
                } finally {
                    if (started.process.isAlive) started.process.destroyForcibly().waitFor()
                }
                shouldBeRestored()
            }
        }
    })
