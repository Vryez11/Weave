package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `run.sh`·`run-all.sh`를 Compose 환경에서 돌리는 `Compose` 태그 테스트의 공통 도구 (F-007).
 *
 * **결과 폴더** (design 결정 6): `experiments/results/<UTC yyyyMMdd-HHmmss>-<S-xx>/`. 실행마다 새로 생긴다.
 * - `report.md`: 사람이 읽는 리포트. `report.json`: 같은 값(형식은 [ExperimentRunComposeScenarioTest] KDoc)
 * - `summary.json`: k6 `--summary-export`
 * - `raw.json`: k6 `--out json`. k6가 도는 동안 이 이름으로 쌓이고, 끝나면 gzip해 `raw.json.gz`가 된다
 *
 * **장애 상태:** 테스트가 직접 확인하고 넣는다.
 * - 프록시: Toxiproxy `GET /proxies`(이름 → `{"enabled":..,"toxics":[..]}`)
 * - 하위 서비스 장애 설정: 호스트에 포트가 없어 `docker compose exec -T <서비스> curl ... localhost:<포트>/actuator/faults`로 부른다.
 *   기본값은 `{"latencyMedianMs":20,"latencyP99Ms":50,"errorRate":0}`(fault-injection 스펙)
 * - ad 요청 한도: 같은 방법으로 `GET /actuator/quota`. 꺼져 있으면 본문의 `enabled`가 `false`다
 *   (켜져 있으면 `{"enabled":true,"limit":n,"windowSeconds":w}`. 다른 필드가 더 있어도 된다)
 */
object ExperimentRuns {
    /** 의존성 이름과 Compose 서비스 이름, 컨테이너 안 포트. */
    val dependencies =
        linkedMapOf(
            "post" to ("post-service" to 8081),
            "user" to ("user-service" to 8082),
            "like" to ("like-service" to 8083),
            "recommendation" to ("recommendation-service" to 8084),
            "ad" to ("ad-service" to 8085),
        )

    val scenarioIds = (1..9).map { "S-%02d".format(it) }

    val resultsDir: Path get() = ExperimentScripts.dir.resolve("results")

    /** 짧은 실행 옵션. */
    fun shortOptions(
        warmup: String = "5s",
        duration: String = "20s",
    ): Array<String> = arrayOf("--rps", "5", "--warmup", warmup, "--duration", duration)

    fun resultFolders(): Set<Path> =
        if (resultsDir.isDirectory()) resultsDir.listDirectoryEntries().filter { it.isDirectory() }.toSet() else emptySet()

    /** [before] 뒤에 새로 생긴 [scenario]의 결과 폴더 하나. */
    fun newResultFolder(
        before: Set<Path>,
        scenario: String,
    ): Path {
        val created = resultFolders() - before
        val folders = created.filter { Regex("""\d{8}-\d{6}-${Regex.escape(scenario)}""").matches(it.name) }
        return withClue("$resultsDir 에 새로 생긴 $scenario 결과 폴더 (새 폴더: ${created.map { it.name }})") {
            folders shouldHaveSize 1
            folders.single()
        }
    }

    /** 결과 폴더의 JSON 파일 하나(예: `verdict-input.json`)를 읽는다. 없으면 실패한다. */
    fun json(file: Path): Map<String, Any?> =
        withClue("$file 이 있어야 한다") {
            file.exists() shouldBe true
            ComposeEnvironment.readJson(file.readText())
        }

    fun report(folder: Path): Map<String, Any?> {
        val file = folder.resolve("report.json")
        return withClue("$file 이 있어야 한다 (폴더: ${folder.listDirectoryEntries().map { it.name }})") {
            file.exists() shouldBe true
            ComposeEnvironment.readJson(file.readText())
        }
    }

    /** 레포 루트에서 `docker <args>`를 돌린다. */
    fun docker(
        vararg args: String,
        timeout: Duration = 60.seconds,
    ): ScriptResult = execute(listOf("docker") + args, emptyMap(), timeout)

    /** 같은 프로젝트의 `redis` 컨테이너 안에서 `redis-cli <args>`를 돌린다(post 캐시, add-graceful-degradation design 결정 12). */
    fun redis(vararg args: String): ScriptResult = ComposeEnvironment.dockerCompose("exec", "-T", "redis", "redis-cli", *args)

    /** 같은 프로젝트의 [service] 컨테이너 안에서 `curl -sS`로 Actuator를 부른다. 본문을 돌려준다. */
    fun actuator(
        service: String,
        port: Int,
        path: String,
        method: String = "GET",
        body: String? = null,
    ): String {
        val curl = mutableListOf("curl", "-fsS", "-X", method)
        if (body != null) curl += listOf("-H", "Content-Type: application/json", "-d", body)
        curl += "http://localhost:$port/actuator/$path"
        val result = ComposeEnvironment.dockerCompose("exec", "-T", service, *curl.toTypedArray())
        withClue("$service $method /actuator/$path: $result") { result.exitCode shouldBe 0 }
        return result.stdout
    }

    fun proxies(): Map<String, Any?> {
        val response = ComposeEnvironment.get("${ComposeEnvironment.toxiproxyUrl}/proxies")
        withClue("Toxiproxy GET /proxies: ${response.body()}") { response.statusCode() shouldBe 200 }
        return ComposeEnvironment.readJson(response.body())
    }

    fun faults(dependency: String): Map<String, Any?> {
        val (service, port) = dependencies.getValue(dependency)
        return ComposeEnvironment.readJson(actuator(service, port, "faults"))
    }

    fun quota(): Map<String, Any?> = ComposeEnvironment.readJson(actuator("ad-service", 8085, "quota"))

    /** 프록시 전체, 하위 서비스 5개의 장애 설정, ad 요청 한도를 한 번에 읽는다. */
    fun faultState(): Map<String, Any?> =
        mapOf(
            "proxies" to proxies(),
            "faults" to dependencies.keys.associateWith { faults(it) },
            "quota" to quota(),
        )

    /** "정상 종료 뒤"의 THEN: 프록시 5개가 켜져 있고 장애가 없으며, 장애 설정이 기본값이고 ad 한도가 꺼져 있다. */
    fun shouldBeClean() {
        val proxies = proxies()
        dependencies.keys.forEach { name ->
            @Suppress("UNCHECKED_CAST")
            val proxy = proxies[name] as Map<String, Any?>?
            withClue("Toxiproxy 프록시 $name: $proxy") {
                proxy.shouldNotBeNull()
                proxy["enabled"] shouldBe true
                (proxy["toxics"] as List<*>).shouldBeEmpty()
            }
        }
        dependencies.keys.forEach { name ->
            val faults = faults(name)
            withClue("$name 의 GET /actuator/faults: $faults") {
                (faults["latencyMedianMs"] as Number).toDouble() shouldBe (20.0 plusOrMinus 1e-9)
                (faults["latencyP99Ms"] as Number).toDouble() shouldBe (50.0 plusOrMinus 1e-9)
                (faults["errorRate"] as Number).toDouble() shouldBe (0.0 plusOrMinus 1e-9)
            }
        }
        val quota = quota()
        withClue("ad 의 GET /actuator/quota: $quota") { quota["enabled"] shouldBe false }
    }

    /** post 프록시 지연 1,000ms와 like 에러율 1을 손으로 넣는다("앞에 남은 장애"의 GIVEN). */
    fun injectLeftoverFaults() {
        val toxic =
            ComposeEnvironment.send(
                "POST",
                "${ComposeEnvironment.toxiproxyUrl}/proxies/post/toxics",
                mapOf(
                    "name" to "weave_test_leftover",
                    "type" to "latency",
                    "stream" to "downstream",
                    "attributes" to mapOf("latency" to 1000),
                ),
            )
        withClue("post 프록시에 지연 톡식 넣기: ${toxic.body()}") { (toxic.statusCode() in 200..299) shouldBe true }
        actuator("like-service", 8083, "faults", "POST", """{"errorRate":1}""")
    }

    /** 테스트가 남긴 장애를 지운다. 엔드포인트가 아직 없거나 실패해도 넘어간다(뒷정리용). */
    fun resetQuietly() {
        runCatching { ComposeEnvironment.send("POST", "${ComposeEnvironment.toxiproxyUrl}/reset") }
        dependencies.values.forEach { (service, port) ->
            runCatching {
                ComposeEnvironment.dockerCompose(
                    "exec",
                    "-T",
                    service,
                    "curl",
                    "-sS",
                    "-X",
                    "DELETE",
                    "http://localhost:$port/actuator/faults",
                )
            }
        }
        runCatching {
            ComposeEnvironment.dockerCompose(
                "exec",
                "-T",
                "ad-service",
                "curl",
                "-sS",
                "-X",
                "DELETE",
                "http://localhost:8085/actuator/quota",
            )
        }
    }

    /** feed-service 컨테이너의 시작 시각(`docker inspect`의 `State.StartedAt`). */
    fun feedStartedAt(): Instant {
        val id = ComposeEnvironment.dockerCompose("ps", "-q", "feed-service")
        withClue("docker compose ps -q feed-service: $id") { id.exitCode shouldBe 0 }
        val containerId = id.stdout.trim()
        val inspect = docker("inspect", "-f", "{{.State.StartedAt}}", containerId)
        return withClue("docker inspect $containerId: $inspect") {
            inspect.exitCode shouldBe 0
            Instant.parse(inspect.stdout.trim())
        }
    }

    /**
     * `experiments/run.sh`를 띄우고 기다리지 않는다. SIGTERM을 보내는 시나리오에서 쓴다.
     * 환경 변수는 [ComposeEnvironment.experiment]와 같다.
     */
    fun startRun(vararg args: String): StartedRun {
        val outputs = createTempDirectory("weave-run")
        val stdout = outputs.resolve("stdout")
        val stderr = outputs.resolve("stderr")
        val builder =
            ProcessBuilder(listOf(ExperimentScripts.dir.resolve("run.sh").absolutePathString()) + args)
                .directory(ComposeEnvironment.composeFile.parent.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
        builder.environment().putAll(
            mapOf(
                "COMPOSE_PROJECT_NAME" to ComposeEnvironment.project,
                "COMPOSE_ENV_FILE" to ComposeEnvironment.envFile.absolutePathString(),
            ),
        )
        return StartedRun(builder.start(), stdout, stderr)
    }

    class StartedRun(
        val process: Process,
        private val stdout: Path,
        private val stderr: Path,
    ) {
        /** 끝날 때까지 기다린다. 시간 안에 끝나지 않으면 강제로 끝내고 실패한다. */
        fun await(timeout: Duration = 3.minutes): ScriptResult {
            val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            withClue("run.sh 가 $timeout 안에 끝나야 한다. 오류 출력: ${stderr.readText()}") { finished shouldBe true }
            return ScriptResult(process.exitValue(), stdout.readText(), stderr.readText())
        }

        override fun toString(): String = "표준 출력:\n${stdout.readText()}\n표준 오류:\n${stderr.readText()}"
    }

    /** k6 원본(`raw.json`)에 측정 구간 피드 요청(`feed_latency`, `scenario: measure`)의 점이 있다. */
    fun hasMeasureRequest(raw: Path): Boolean {
        if (!raw.exists()) return false
        return Files.newBufferedReader(raw).useLines { lines ->
            lines.any { line ->
                val point = runCatching { ComposeEnvironment.readJson(line) }.getOrNull() ?: return@any false

                @Suppress("UNCHECKED_CAST")
                val tags = (point["data"] as? Map<String, Any?>)?.get("tags") as? Map<String, Any?>
                point["type"] == "Point" && point["metric"] == "feed_latency" && tags?.get("scenario") == "measure"
            }
        }
    }

    private fun execute(
        command: List<String>,
        environment: Map<String, String>,
        timeout: Duration,
    ): ScriptResult {
        val outputs = createTempDirectory("weave-docker")
        val stdout = outputs.resolve("stdout")
        val stderr = outputs.resolve("stderr")
        val builder =
            ProcessBuilder(command)
                .directory(ComposeEnvironment.composeFile.parent.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
        builder.environment().putAll(environment)
        val process = builder.start()
        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        withClue("${command.joinToString(" ")} 가 $timeout 안에 끝나야 한다. 오류 출력: ${stderr.readText()}") { finished shouldBe true }
        return ScriptResult(process.exitValue(), stdout.readText(), stderr.readText())
    }

    /** 다른 Compose 프로젝트 이름으로 `docker compose`를 돌린다("환경이 떠 있지 않다"). */
    fun dockerComposeProject(
        project: String,
        vararg args: String,
    ): ScriptResult =
        execute(
            listOf(
                "docker",
                "compose",
                "-p",
                project,
                "-f",
                ComposeEnvironment.composeFile.absolutePathString(),
                "--env-file",
                ComposeEnvironment.envFile.absolutePathString(),
            ) + args,
            emptyMap(),
            60.seconds,
        )
}
