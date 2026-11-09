package com.weave.like

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource

/**
 * `bootTestRun`과 같은 방식(`TestLikeServiceApplication`)으로 띄운 like-service가 좋아요를 저장하는지 본다 (F-006).
 * `bootTestRun`은 끝나지 않는 명령이라 직접 띄워 보는 것은 사람이 한다.
 */
@Tags("Integration")
class TestLikeServiceApplicationTest :
    FunSpec({
        test("F-006 로컬 실행 진입점으로 띄운 like-service가 PUT /likes/1에 liked true를 준다") {
            TestLikeServiceApplication.start("--server.port=0").use { application ->
                // 같은 JVM의 다른 테스트가 남긴 version이 있으면 요청이 반영되지 않으므로 먼저 비운다
                val jdbc = JdbcTemplate(application.getBean(DataSource::class.java))
                listOf("post_like", "post_like_count").forEach { jdbc.update("DELETE FROM $it") }

                val port = application.environment.getRequiredProperty("local.server.port")
                val request =
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/likes/1"))
                        .header("X-User-Id", "1")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""{"liked":true,"version":1}"""))
                        .build()
                val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

                response.statusCode() shouldBe 200
                jacksonObjectMapper().readValue<Map<String, Any?>>(response.body())["liked"] shouldBe true
            }
        }
    })
