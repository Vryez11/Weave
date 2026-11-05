package com.weave.e2e

import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File

/**
 * [ServiceProcesses]가 남기는 `e2e/build/e2e-logs/<서비스>.log`를 읽는다 (design 결정 8).
 *
 * 줄마다 JSON 객체로 파싱하고, JSON이 아닌 줄(뜨기 전 출력, 쓰는 중인 마지막 줄)은 원문으로만 남긴다.
 * 서비스는 비동기 appender로 쓰므로 부르는 쪽이 `eventually`로 기다린다.
 */
class ServiceLogs(
    private val logDir: File = File("build/e2e-logs"),
) {
    private val mapper = jacksonObjectMapper()

    /** 서비스 로그의 원문 줄. 파일이 아직 없으면 빈 목록이다. */
    fun rawLines(service: String): List<String> = File(logDir, "$service.log").takeIf { it.isFile }?.readLines() ?: emptyList()

    /** 서비스 로그에서 JSON 객체인 줄만. */
    fun jsonLines(service: String): List<Map<String, Any?>> =
        rawLines(service).mapNotNull { line ->
            if (!line.trimStart().startsWith("{")) return@mapNotNull null
            runCatching { mapper.readValue<Map<String, Any?>>(line) }.getOrNull()
        }

    /** 서비스 로그에서 `traceId`가 같은 요청 완료 로그(`http_request`) 줄. */
    fun requestLines(
        service: String,
        traceId: String,
    ): List<Map<String, Any?>> = jsonLines(service).filter { it["event"] == "http_request" && it["traceId"] == traceId }
}
