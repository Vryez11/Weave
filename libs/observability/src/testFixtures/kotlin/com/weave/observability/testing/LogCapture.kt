package com.weave.observability.testing

import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

// 표준 출력을 가로채 로그를 줄 단위로 돌려준다. logback 콘솔 appender는 쓸 때마다 System.out을 다시 찾으므로
// System.out을 바꿔 끼우면 로그가 잡힌다. 원래 출력에도 그대로 써서 테스트 리포트에서 로그를 볼 수 있다.
// 비동기 appender라 로그가 늦게 올 수 있으니 검사는 eventually로 기다린다.
class LogCapture : AutoCloseable {
    private val original: PrintStream = System.out
    private val captured = ByteArrayOutputStream()

    init {
        System.setOut(PrintStream(Tee(original, captured), true, Charsets.UTF_8))
    }

    // 지금까지 잡힌 모든 줄. JSON이 아닌 줄은 json이 null이다
    fun lines(): List<LogLine> =
        captured
            .toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
            .map { LogLine(it, parse(it)) }

    // JSON 객체로 파싱되는 줄만
    fun jsonLines(): List<Map<String, Any?>> = lines().mapNotNull { it.json }

    override fun close() {
        System.out.flush()
        System.setOut(original)
    }

    private fun parse(line: String): Map<String, Any?>? =
        try {
            mapper.readValue(line, mapType)
        } catch (_: JacksonException) {
            null
        }

    private class Tee(
        private val first: OutputStream,
        private val second: OutputStream,
    ) : OutputStream() {
        override fun write(b: Int) {
            first.write(b)
            second.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            first.write(b, off, len)
            second.write(b, off, len)
        }

        override fun flush() {
            first.flush()
            second.flush()
        }
    }

    companion object {
        private val mapper = JsonMapper.builder().build()
        private val mapType = object : TypeReference<Map<String, Any?>>() {}
    }
}

data class LogLine(
    val raw: String,
    val json: Map<String, Any?>?,
)
