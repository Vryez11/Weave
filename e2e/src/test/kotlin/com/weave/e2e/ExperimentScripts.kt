package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 실험 세트 폴더(`experiments/`)와 그 안의 스크립트를 테스트에서 쓴다 (F-007).
 *
 * 폴더 위치는 e2e `build.gradle.kts`가 넘기는 시스템 속성 `weave.experiments.dir`로 받는다.
 * 픽스처는 테스트 리소스의 `experiments/` 아래에 있다.
 */
object ExperimentScripts {
    val dir: Path
        get() =
            withClue("시스템 속성 weave.experiments.dir (e2e build.gradle.kts가 넘긴다)") {
                Paths.get(System.getProperty("weave.experiments.dir").shouldNotBeNull())
            }

    /** 테스트 리소스 `experiments/<name>`의 경로. */
    fun fixture(name: String): Path =
        withClue("테스트 리소스 experiments/$name") {
            Paths.get(
                ExperimentScripts::class.java
                    .getResource("/experiments/$name")
                    .shouldNotBeNull()
                    .toURI(),
            )
        }

    /** 픽스처를 gzip해 임시 파일로 쓴다. k6 원본은 run.sh가 끝나면 gzip한다(design 결정 6). */
    fun gzippedFixture(name: String): Path {
        val target = createTempDirectory("weave-experiments").resolve("$name.gz")
        GZIPOutputStream(target.outputStream()).use { out -> fixture(name).inputStream().use { it.copyTo(out) } }
        return target
    }

    /**
     * `experiments/<script>`를 실행 파일로 직접 돌린다. 표준 출력과 표준 오류는 따로 받는다.
     * 스크립트는 셔뱅으로 bash를 고르고 실행 권한이 있어야 한다(`experiments/run.sh`처럼 그대로 부른다).
     */
    fun run(
        script: String,
        vararg args: String,
        timeout: Duration = 60.seconds,
    ): ScriptResult {
        val path = dir.resolve(script)
        withClue("$path 가 실행 파일로 있어야 한다") { Files.isExecutable(path) shouldBe true }
        val outputs = createTempDirectory("weave-experiments-run")
        val stdout = outputs.resolve("stdout")
        val stderr = outputs.resolve("stderr")
        val process =
            ProcessBuilder(listOf(path.absolutePathString()) + args)
                .directory(dir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start()
        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        withClue("$script ${args.joinToString(" ")} 가 $timeout 안에 끝나야 한다. 오류 출력: ${stderr.readText()}") {
            finished shouldBe true
        }
        return ScriptResult(process.exitValue(), stdout.readText(), stderr.readText())
    }
}

data class ScriptResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    override fun toString(): String = "종료 코드 $exitCode\n표준 출력:\n$stdout\n표준 오류:\n$stderr"
}
