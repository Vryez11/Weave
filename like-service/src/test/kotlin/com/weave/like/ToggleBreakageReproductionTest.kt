package com.weave.like

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * MVP 토글이 재전송과 동시 요청에서 깨지는 것을 기록한다 (F-003, add-idempotent-like design 결정 8).
 *
 * 토글을 없애면 이 테스트도 함께 사라지므로 한 커밋에만 남긴다. 수치는 실행마다 달라 "깨진다"만 단언하고 값은 표준 출력에 적는다.
 * 결과는 `docs/experiments/idempotent-like.md`에 있다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToggleBreakageReproductionTest :
    FunSpec({
        extensions(SpringExtension())

        val mapper = jacksonObjectMapper()
        val http = HttpClient.newHttpClient()

        suspend fun port(): Int =
            testContextManager()
                .testContext.applicationContext.environment
                .getRequiredProperty("local.server.port")
                .toInt()

        suspend fun jdbc(): JdbcTemplate = JdbcTemplate(testContextManager().testContext.applicationContext.getBean(DataSource::class.java))

        fun toggle(
            port: Int,
            postId: Long,
            userId: Long,
        ): HttpResponse<String> {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/likes/$postId/toggle"))
                    .header("X-User-Id", userId.toString())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
            return http.send(request, HttpResponse.BodyHandlers.ofString())
        }

        fun concurrently(
            times: Int,
            block: (Int) -> Int,
        ): List<Int> {
            val pool = Executors.newFixedThreadPool(times)
            try {
                val start = CountDownLatch(1)
                val futures =
                    (0 until times).map { index ->
                        pool.submit<Int> {
                            start.await()
                            block(index)
                        }
                    }
                start.countDown()
                return futures.map { it.get() }
            } finally {
                pool.shutdown()
            }
        }

        beforeEach {
            val db = jdbc()
            db.update("DELETE FROM post_like")
            db.update("DELETE FROM post_like_count")
        }

        test("F-003 토글 재현: 반영된 요청을 클라이언트가 다시 보내면 의도와 달리 취소된다") {
            val first = mapper.readValue<Map<String, Any?>>(toggle(port(), 5, 1).body())
            val resent = mapper.readValue<Map<String, Any?>>(toggle(port(), 5, 1).body())

            println("TOGGLE_REPRO resend first=$first resent=$resent")
            first["liked"] shouldBe true
            withClue("같은 요청을 다시 보낸 결과") { resent["liked"] shouldBe false }
        }

        test("F-003 토글 재현: 서로 다른 사용자 50명이 동시에 누르면 수가 행 수보다 작다") {
            val port = port()
            toggle(port, 6, 1000).statusCode() shouldBe 200

            val statuses = concurrently(50) { toggle(port, 6, it + 1L).statusCode() }

            val db = jdbc()
            val rows = db.queryForObject("SELECT COUNT(*) FROM post_like WHERE post_id = 6", Long::class.java)!!
            val count = db.queryForObject("SELECT like_count FROM post_like_count WHERE post_id = 6", Long::class.java)!!
            println("TOGGLE_REPRO lost_update rows=$rows count=$count statuses=${statuses.groupingBy { it }.eachCount()}")
            withClue("저장된 수 $count, 행 수 $rows") { count shouldBeLessThan rows }
        }

        test("F-003 토글 재현: 같은 사용자의 동시 첫 요청 일부가 5xx로 실패한다") {
            val port = port()

            val statuses = concurrently(20) { toggle(port, 7, 1).statusCode() }

            val db = jdbc()
            val rows = db.queryForObject("SELECT COUNT(*) FROM post_like WHERE post_id = 7", Long::class.java)!!
            val count =
                db
                    .queryForList("SELECT like_count FROM post_like_count WHERE post_id = 7", Long::class.java)
                    .singleOrNull()
            println("TOGGLE_REPRO same_user rows=$rows count=$count statuses=${statuses.groupingBy { it }.eachCount()}")
            withClue("상태 코드 $statuses") { statuses.count { it >= 500 } shouldBeGreaterThan 0 }
        }
    })
