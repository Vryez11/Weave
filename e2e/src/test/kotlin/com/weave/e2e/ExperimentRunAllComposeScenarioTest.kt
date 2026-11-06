package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.io.path.isDirectory
import kotlin.time.Duration.Companion.minutes

/**
 * `fault-experiments` 스펙의 "판정 표" 시나리오 (F-007).
 *
 * `:e2e:composeTest`가 띄운 환경에서 `run-all.sh`를 프로세스로 돌린다(design 결정 8, 13). "기본 목록"은
 * `ExperimentScenarioDefinitionTest`가 본다.
 *
 * **명령:** `experiments/run-all.sh [--rps N] [--warmup 1m] [--duration 5m] [S-xx ...]`
 * - 옵션은 시나리오마다 `run.sh`에 그대로 넘긴다. 시나리오 ID가 없으면 `--list`의 목록(S-01~S-09)을 돌린다.
 * - 종료 코드: 모두 기대와 같으면 0, 하나라도 다르거나(1) 실행하지 못했으면(2) 0이 아니다. 기대와 달라도 끝까지 돈다.
 *
 * **판정 표:** 모든 시나리오를 돈 뒤 표준 출력에 마크다운 표로 쓴다. 시나리오마다 한 줄이고 돌린 순서다.
 * ```
 * | 시나리오 | SLO | 기대 | 판정 | 리포트 |
 * |---|---|---|---|---|
 * | S-01 | 충족 | meet | 기대와 같음 | experiments/results/20261106-010000-S-01 |
 * | S-02 | 미충족 | 가용성 miss · p99 meet | 기대와 같음 | experiments/results/20261106-010130-S-02 |
 * ```
 * - SLO: `충족`, `미충족`, 실행하지 못했으면 `-`. 기대: `expectations.json`의 값. 기준별 기대(객체)면 `가용성 <값> · p99 <값>`. 판정: `기대와 같음`, `기대와 다름`, `실행하지 못함`
 * - 리포트: 그 실행의 결과 폴더. 레포 루트 기준 상대 경로이거나 절대 경로다. 실행하지 못했으면 `-`
 * - 표의 줄은 `| S-`로 시작한다. 표준 출력의 다른 줄(`run.sh`의 진행 안내 등)은 `| S-`로 시작하지 않는다.
 */
@Tags("Compose")
class ExperimentRunAllComposeScenarioTest :
    FunSpec({
        afterSpec { ExperimentRuns.resetQuietly() }

        test("F-007 판정 표: run-all.sh를 짧은 옵션으로 S-01 S-02에 돌리면 두 줄의 판정 표를 출력하고 0으로 끝난다") {
            val result =
                ComposeEnvironment.experiment(
                    "run-all.sh",
                    *ExperimentRuns.shortOptions(),
                    "S-01",
                    "S-02",
                    timeout = 8.minutes,
                )
            withClue(result) {
                val header =
                    result.stdout
                        .lines()
                        .map { it.trim() }
                        .filter { it.startsWith("| 시나리오") }
                header shouldHaveSize 1
                val rows =
                    result.stdout
                        .lines()
                        .map { it.trim() }
                        .filter { it.startsWith("| S-") }
                        .map { line -> line.trim('|').split("|").map { it.trim() } }
                rows shouldHaveSize 2
                rows.forEach { it shouldHaveSize 5 }
                rows.map { it[0] } shouldContainExactly listOf("S-01", "S-02")
                // 기대 열은 expectations.json의 값. 기준별 기대(객체)는 "가용성 miss · p99 meet"처럼 적는다(add-call-isolation design 결정 10)
                rows.map { it[2] } shouldContainExactly listOf("meet", "가용성 miss · p99 meet")
                rows.forEach { row ->
                    withClue("판정 표 줄 $row") {
                        row[1] shouldBeIn listOf("충족", "미충족")
                        row[3] shouldBe "기대와 같음"
                        val folder = ComposeEnvironment.composeFile.parent.resolve(row[4])
                        folder.isDirectory() shouldBe true
                        ExperimentRuns.report(folder)["scenario"] shouldBe row[0]
                    }
                }
                result.exitCode shouldBe 0
            }
        }
    })
