package com.weave.e2e

import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.mysql.MySQLContainer
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 종단 간 테스트가 띄우는 서비스 6개와 like-service의 MySQL (design 결정 10).
 *
 * - 서비스는 각 모듈의 bootJar를 `java -jar`로 띄운다. jar 경로는 `e2e/build.gradle.kts`가
 *   `weave.e2e.jar.<서비스 모듈 이름>` 시스템 속성으로 넘긴다.
 * - 포트는 빈 포트를 골라 쓰고, 주소·DB 접속 정보·포트는 명령줄 인자로 넘긴다.
 * - 로그는 `e2e/build/e2e-logs/<서비스>.log`에 남는다.
 * - [stop]은 여러 번 불러도 된다. 테스트가 중간에 죽어도 JVM 종료 훅이 프로세스를 내린다.
 */
class ServiceProcesses {
    /** 띄울 하위 서비스와 feed-service가 그 주소를 받는 HTTP 서비스 그룹 이름. */
    private val downstreams =
        mapOf(
            "post-service" to "post",
            "user-service" to "user",
            "like-service" to "like",
            "recommendation-service" to "recommendation",
            "ad-service" to "ad",
        )

    val services = listOf("feed-service") + downstreams.keys

    private val mysql = DriverlessMySqlContainer()
    private val ports = mutableMapOf<String, Int>()
    private val processes = mutableMapOf<String, Process>()
    private val logDir = File("build/e2e-logs")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val shutdownHook = Thread { destroyAll() }

    /** 띄운 프로세스. 정리 검사에서 [Process.isAlive]를 본다. */
    val started: Map<String, Process> get() = processes.toMap()

    fun port(service: String): Int = ports.getValue(service)

    /**
     * MySQL을 띄우고, 서비스 6개를 한꺼번에 띄운 뒤 서비스마다 `/actuator/health`가 UP이 될 때까지 기다린다.
     * 하나라도 뜨지 않으면 띄운 것을 모두 내리고 예외를 던진다.
     */
    fun start(healthTimeout: Duration = Duration.ofSeconds(60)) {
        try {
            mysql.start()
            logDir.mkdirs()
            services.forEach { ports[it] = freePort() }
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            services.forEach { processes[it] = launch(it) }
            services.forEach { awaitHealthy(it, healthTimeout) }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /** 서비스를 모두 내리고 MySQL을 멈춘다. 정상 종료를 기다리다 안 되면 강제로 내린다. */
    fun stop() {
        destroyAll()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        mysql.stop()
    }

    private fun launch(service: String): Process {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java")
        val command = listOf(java, "-jar", jarOf(service).absolutePath) + argumentsOf(service)
        val log = File(logDir, "$service.log")
        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
    }

    private fun argumentsOf(service: String): List<String> {
        val common = listOf("--server.port=${port(service)}")
        return when (service) {
            "feed-service" ->
                common +
                    downstreams.flatMap { (downstream, group) ->
                        listOf("--spring.http.serviceclient.$group.base-url=http://localhost:${port(downstream)}") +
                            relaxedIsolation(group)
                    } +
                    "--weave.feed.time-budget=5s" +
                    // post stale 캐시 저장소(Redis)는 띄우지 않는다. 아무것도 듣지 않는 포트를 줘 캐시 쓰기가 조용히 실패하는 상태로 돈다
                    // (add-graceful-degradation design 결정 15). 종단 간 테스트는 하위 서비스가 모두 정상인 흐름만 본다
                    listOf("--spring.data.redis.host=localhost", "--spring.data.redis.port=1")
            "like-service" ->
                common +
                    listOf(
                        "--spring.datasource.url=${mysql.jdbcUrl}",
                        "--spring.datasource.username=${mysql.username}",
                        "--spring.datasource.password=${mysql.password}",
                    )
            else -> common
        }
    }

    /**
     * 막 뜬 JVM의 첫 호출(클래스 로딩, 연결 수립)이 하위 서비스 호출 시간 상한(100ms)에 걸리지 않게 넉넉한 값을 준다
     * (add-call-isolation design 결정 13). 종단 간 테스트는 흐름을 보고, 실제 값은 장애 실험 세트가 확인한다.
     */
    private fun relaxedIsolation(group: String): List<String> =
        listOf(
            "--resilience4j.timelimiter.instances.$group.timeout-duration=2s",
            "--spring.http.serviceclient.$group.connect-timeout=2s",
            "--spring.http.serviceclient.$group.read-timeout=2s",
        )

    private fun jarOf(service: String): File {
        val property = "weave.e2e.jar.$service"
        val path =
            checkNotNull(System.getProperty(property)) {
                "$property 시스템 속성이 없다. ./gradlew :e2e:integrationTest로 돌렸는지, e2e/build.gradle.kts의 jvmArgumentProviders를 확인한다"
            }
        return File(path).also { check(it.isFile) { "${service}의 bootJar가 없다: $path" } }
    }

    private fun awaitHealthy(
        service: String,
        timeout: Duration,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${port(service)}/actuator/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
        while (System.nanoTime() < deadline) {
            val process = processes.getValue(service)
            check(process.isAlive) { "$service 프로세스가 뜨다가 끝났다(종료 코드 ${process.exitValue()}). 로그: ${logOf(service)}" }
            val healthy =
                runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
                    .getOrNull()
                    ?.let { it.statusCode() == 200 && it.body().contains("\"UP\"") } == true
            if (healthy) return
            Thread.sleep(500)
        }
        error("${service}가 ${timeout.seconds}초 안에 /actuator/health UP이 되지 않았다. 로그: ${logOf(service)}")
    }

    private fun destroyAll() {
        processes.values.forEach { it.destroy() }
        processes.values.forEach { process ->
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun logOf(service: String) = File(logDir, "$service.log").absolutePath

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

/**
 * JDBC 드라이버 없이 준비를 기다리는 MySQL 컨테이너.
 *
 * `MySQLContainer`는 JDBC로 접속해 보며 기다리는데, e2e는 서비스 코드에 기대지 않아 드라이버가 없다.
 * 대신 본 서버(초기화용 임시 서버는 `port: 0`)가 3306에서 접속을 받는다는 로그를 기다린다.
 */
private class DriverlessMySqlContainer : MySQLContainer("mysql:9.7") {
    init {
        waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n", 1).withStartupTimeout(Duration.ofMinutes(2)))
    }

    override fun waitUntilContainerStarted() {
        waitStrategy.waitUntilReady(this)
    }
}
