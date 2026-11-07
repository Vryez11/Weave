package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * `:e2e:composeTest`가 띄운 Compose 환경을 `Compose` 태그 테스트에서 쓴다 (F-007).
 *
 * 환경을 띄우고 내리는 것은 태스크가 맡고, 테스트는 아래 시스템 속성으로 주소와 Compose 프로젝트만 받는다.
 * - `weave.compose.feed-url`: feed-service. 예) `http://localhost:8080`
 * - `weave.compose.toxiproxy-url`: Toxiproxy 관리 API. 예) `http://localhost:8474`
 * - `weave.compose.grafana-url`: Grafana. 예) `http://localhost:3000`
 * - `weave.compose.prometheus-url`: Prometheus. 예) `http://localhost:9090`
 * - `weave.compose.project`: Compose 프로젝트 이름. 예) `weave-compose-test`
 * - `weave.compose.file`: 루트 `compose.yaml`의 절대 경로. compose 명령은 그 폴더(레포 루트)에서 돌린다
 * - `weave.compose.env-file`: `docker compose --env-file`에 넘길 파일. 예) `e2e/build/compose-test.env`
 */
object ComposeEnvironment {
    private val mapper = jacksonObjectMapper()
    private val http = HttpClient.newHttpClient()

    fun address(property: String): String =
        withClue("시스템 속성 $property (:e2e:composeTest가 넘긴다)") {
            System.getProperty(property).shouldNotBeNull().trimEnd('/')
        }

    val feedUrl: String get() = address("weave.compose.feed-url")
    val toxiproxyUrl: String get() = address("weave.compose.toxiproxy-url")
    val grafanaUrl: String get() = address("weave.compose.grafana-url")
    val prometheusUrl: String get() = address("weave.compose.prometheus-url")
    val project: String get() = address("weave.compose.project")
    val composeFile: Path get() = Paths.get(address("weave.compose.file"))
    val envFile: Path get() = Paths.get(address("weave.compose.env-file"))

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = 30.seconds,
    ): HttpResponse<String> = send("GET", url, null, headers, timeout)

    /** 본문은 JSON으로 보낸다. 본문이 없으면 빈 본문이다. */
    fun send(
        method: String,
        url: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = 30.seconds,
    ): HttpResponse<String> {
        val publisher =
            if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
            }
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(timeout.toJavaDuration())
                .method(method, publisher)
        if (body != null) request.header("Content-Type", "application/json")
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun readJson(body: String): Map<String, Any?> = mapper.readValue(body)

    fun readJsonList(body: String): List<Any?> = mapper.readValue(body)

    /**
     * `docker compose -p <프로젝트> -f <compose.yaml> --env-file <파일> <args>`를 레포 루트에서 돌린다.
     * [environment]는 compose의 변수 치환에 쓰인다(셸 환경이 env 파일보다 우선한다).
     */
    fun dockerCompose(
        vararg args: String,
        timeout: Duration = 60.seconds,
        environment: Map<String, String> = emptyMap(),
    ): ScriptResult =
        execute(
            listOf(
                "docker",
                "compose",
                "-p",
                project,
                "-f",
                composeFile.absolutePathString(),
                "--env-file",
                envFile.absolutePathString(),
            ) + args,
            environment,
            timeout,
        )

    /**
     * feed-service 컨테이너를 새로 만들어 healthy가 될 때까지 기다린다(add-call-isolation design 결정 10).
     *
     * [springApplicationJson]을 주면 compose.yaml이 feed-service에 넘기는 `SPRING_APPLICATION_JSON`(변수 `FEED_SPRING_APPLICATION_JSON`)이
     * 그 값이 되고, 주지 않으면 기본값 `{}`이다. `run.sh`는 feed-service를 `docker compose restart`로 다시 띄워 컨테이너의 환경을 그대로
     * 두므로, 설정을 바꿔 `run.sh`를 돌리려면 이것으로 먼저 만든다. 끝나면 인자 없이 다시 불러 기본 설정으로 되돌린다.
     */
    fun recreateFeedService(springApplicationJson: String? = null) {
        val environment = springApplicationJson?.let { mapOf("FEED_SPRING_APPLICATION_JSON" to it) } ?: emptyMap()
        val result =
            dockerCompose(
                "up",
                "-d",
                "--force-recreate",
                "--no-deps",
                "--wait",
                "feed-service",
                timeout = 3.minutes,
                environment = environment,
            )
        withClue("feed-service 다시 만들기: $result") { result.exitCode shouldBe 0 }
    }

    /**
     * 막 뜬 feed-service에 `GET /feed`를 [requests]번 보내고 결과는 보지 않는다(add-call-isolation design 결정 10).
     * 첫 호출은 연결 수립과 클래스 로딩으로 하위 서비스 호출 시간 상한(100ms)에 걸릴 수 있어, 첫 응답을 단언하는 시나리오 전에 부른다.
     */
    fun warmUpFeed(requests: Int = 20) {
        repeat(requests) {
            runCatching { get("$feedUrl/feed", headers = mapOf("X-User-Id" to "1"), timeout = 10.seconds) }
        }
    }

    /**
     * `experiments/<script>`를 이 Compose 환경을 대상으로 돌린다.
     * 프로젝트 이름과 env 파일은 `COMPOSE_PROJECT_NAME`, `COMPOSE_ENV_FILE` 환경 변수로 넘긴다(design 결정 6).
     */
    fun experiment(
        script: String,
        vararg args: String,
        timeout: Duration,
        environment: Map<String, String> = emptyMap(),
    ): ScriptResult {
        val path = ExperimentScripts.dir.resolve(script)
        return execute(
            listOf(path.absolutePathString()) + args,
            mapOf(
                "COMPOSE_PROJECT_NAME" to project,
                "COMPOSE_ENV_FILE" to envFile.absolutePathString(),
            ) + environment,
            timeout,
        )
    }

    private fun execute(
        command: List<String>,
        environment: Map<String, String>,
        timeout: Duration,
    ): ScriptResult {
        val outputs = createTempDirectory("weave-compose-run")
        val stdout = outputs.resolve("stdout")
        val stderr = outputs.resolve("stderr")
        val builder =
            ProcessBuilder(command)
                .directory(composeFile.parent.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
        builder.environment().putAll(environment)
        val process = builder.start()
        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        withClue("${command.joinToString(" ")} 가 $timeout 안에 끝나야 한다. 오류 출력: ${stderr.readText()}") {
            finished shouldBe true
        }
        return ScriptResult(process.exitValue(), stdout.readText(), stderr.readText())
    }
}
