package com.weave.observability.testing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class LogCaptureTest :
    FunSpec({
        test("F-004 println으로 쓴 JSON 한 줄을 파싱한다") {
            val lines =
                LogCapture().use { capture ->
                    println("""{"level":"INFO","message":"hello","status":200}""")
                    capture.jsonLines()
                }

            lines shouldContainExactly listOf(mapOf("level" to "INFO", "message" to "hello", "status" to 200))
        }

        test("F-004 JSON이 아닌 줄은 원문으로만 남긴다") {
            val lines =
                LogCapture().use { capture ->
                    println("plain text")
                    println("""{"message":"json"}""")
                    capture.lines()
                }

            lines.map { it.raw } shouldContainExactly listOf("plain text", """{"message":"json"}""")
            lines[0].json.shouldBeNull()
            lines[1].json shouldBe mapOf("message" to "json")
        }

        test("F-004 닫으면 원래 표준 출력으로 돌아간다") {
            val original = System.out
            LogCapture().close()

            System.out shouldBe original
        }
    })
