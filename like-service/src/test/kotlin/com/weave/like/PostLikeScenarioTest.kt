package com.weave.like

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource

/**
 * `post-like` 스펙의 조회·식별·영속 시나리오 (F-006). 상태 지정은 `PostLikeStateScenarioTest`(F-003)에 있다.
 *
 * - GIVEN의 좋아요는 `PUT /likes/{postId}`로 만든다. 구현 코드를 참조하지 않는다.
 * - 테스트마다 좋아요 테이블을 비운다. 구현 전에는 테이블이 없으므로 있는 테이블만 비운다.
 * - 영속 시나리오는 같은 MySQL 컨테이너(`MySqlTestConfiguration`)로 애플리케이션을 두 번 띄운다.
 * - 배치 조회 응답의 순서는 스펙이 정하지 않으므로 보지 않는다.
 */
@Tags("Integration")
@SpringBootTest
@AutoConfigureMockMvc
class PostLikeScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val mapper = jacksonObjectMapper()

        suspend fun mockMvc(): MockMvc = testContextManager().testContext.applicationContext.getBean(MockMvc::class.java)

        suspend fun getLikes(
            query: String,
            userId: String? = "1",
        ): Pair<Int, String> {
            val response =
                mockMvc()
                    .get("/likes$query") { userId?.let { header("X-User-Id", it) } }
                    .andReturn()
                    .response
            return response.status to response.contentAsString
        }

        suspend fun like(
            postId: String,
            userId: String? = "1",
        ): Pair<Int, String> {
            val response =
                mockMvc()
                    .put("/likes/$postId") {
                        userId?.let { header("X-User-Id", it) }
                        contentType = MediaType.APPLICATION_JSON
                        content = LIKE_BODY
                    }.andReturn()
                    .response
            return response.status to response.contentAsString
        }

        fun likesOf(body: String): List<Map<String, Any?>> = mapper.readValue<Map<String, List<Map<String, Any?>>>>(body).getValue("likes")

        beforeEach {
            val dataSource = testContextManager().testContext.applicationContext.getBean(DataSource::class.java)
            val jdbc = JdbcTemplate(dataSource)
            listOf("post_like", "post_like_count").forEach { table ->
                val exists =
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                        Int::class.java,
                        table,
                    )
                if (exists == 1) jdbc.update("DELETE FROM $table")
            }
        }

        // 사용자 식별

        test("F-006 헤더가 없다: X-User-Id 없이 GET /likes?postIds=1을 부르면 400이 온다") {
            val (status, _) = getLikes("?postIds=1", userId = null)

            status shouldBe 400
        }

        test("F-006 헤더가 없다: X-User-Id 없이 PUT /likes/1을 부르면 400이 온다") {
            val (status, _) = like("1", userId = null)

            status shouldBe 400
        }

        test("F-006 헤더 값이 잘못됐다: X-User-Id: abc로 PUT /likes/1을 부르면 400이 온다") {
            val (status, _) = like("1", userId = "abc")

            status shouldBe 400
        }

        test("F-006 헤더 값이 잘못됐다: X-User-Id: 0으로 PUT /likes/1을 부르면 400이 온다") {
            val (status, _) = like("1", userId = "0")

            status shouldBe 400
        }

        // 좋아요 배치 조회

        test("F-006 좋아요가 없는 게시글: GET /likes?postIds=7이면 postId 7, likeCount 0, liked false가 온다") {
            val (status, body) = getLikes("?postIds=7")

            status shouldBe 200
            val likes = likesOf(body)
            likes shouldBe listOf(likeOf(postId = 7, likeCount = 0, liked = false))
            likes.forAll { it.keys shouldBe setOf("postId", "likeCount", "liked") }
        }

        test("F-006 내 좋아요 여부와 전체 수: 사용자 1과 2가 게시글 3을 누르면 사용자 1에게 3은 2·true, 4는 0·false다") {
            like("3", userId = "1").first shouldBe 200
            like("3", userId = "2").first shouldBe 200

            val (status, body) = getLikes("?postIds=3,4", userId = "1")

            status shouldBe 200
            likesOf(body) shouldContainExactlyInAnyOrder
                listOf(
                    likeOf(postId = 3, likeCount = 2, liked = true),
                    likeOf(postId = 4, likeCount = 0, liked = false),
                )
        }

        test("F-006 사용자마다 다른 상태: 사용자 1만 게시글 3을 누르면 사용자 2에게는 likeCount 1, liked false다") {
            like("3", userId = "1").first shouldBe 200

            val (status, body) = getLikes("?postIds=3", userId = "2")

            status shouldBe 200
            likesOf(body) shouldBe listOf(likeOf(postId = 3, likeCount = 1, liked = false))
        }

        test("F-006 같은 ID를 여러 번 보내면 항목은 한 번만 온다") {
            val (status, body) = getLikes("?postIds=7,7,7")

            status shouldBe 200
            likesOf(body) shouldBe listOf(likeOf(postId = 7, likeCount = 0, liked = false))
        }

        test("F-006 배치 조회는 같은 ID를 한 번만, 요청 순서대로 준다") {
            val (status, body) = getLikes("?postIds=5,2,5,2")

            status shouldBe 200
            likesOf(body).map { it["postId"] } shouldBe listOf(5, 2)
        }

        test("F-006 ID가 없거나 너무 많다: postIds 없이 GET /likes를 부르면 400이 온다") {
            val (status, _) = getLikes("")

            status shouldBe 400
        }

        test("F-006 ID가 없거나 너무 많다: ID 101개로 GET /likes를 부르면 400이 온다") {
            val (status, _) = getLikes("?postIds=" + (1..101).joinToString(","))

            status shouldBe 400
        }

        // 좋아요 영속

        test("F-006 다시 띄운 뒤: 사용자 1이 게시글 5를 누르고 같은 DB로 다시 띄우면 likeCount 1, liked true가 온다") {
            startApplication().use { first ->
                val (status, _) = send(first, "PUT", "/likes/5", userId = "1", body = LIKE_BODY)
                status shouldBe 200
            }

            startApplication().use { second ->
                val (status, body) = send(second, "GET", "/likes?postIds=5", userId = "1")
                status shouldBe 200
                likesOf(body) shouldBe listOf(likeOf(postId = 5, likeCount = 1, liked = true))
            }
        }
    })

private const val LIKE_BODY = """{"liked":true,"version":1}"""

private fun likeOf(
    postId: Int,
    likeCount: Int,
    liked: Boolean,
): Map<String, Any?> = mapOf("postId" to postId, "likeCount" to likeCount, "liked" to liked)

/**
 * 테스트 컨텍스트와 별개로 애플리케이션을 빈 포트에 띄운다.
 * `MySqlTestConfiguration`이 컴포넌트 스캔에 잡혀 테스트와 같은 컨테이너를 쓴다.
 * 포트는 명령줄 인자로 넘긴다. `properties(...)`는 기본 속성이라 `application.yml`의 `server.port`에 덮인다.
 */
private fun startApplication(): ConfigurableApplicationContext =
    SpringApplicationBuilder(LikeServiceApplication::class.java)
        .run("--server.port=0")

private val httpClient: HttpClient = HttpClient.newHttpClient()

private fun send(
    application: ConfigurableApplicationContext,
    method: String,
    path: String,
    userId: String,
    body: String? = null,
): Pair<Int, String> {
    val port = application.environment.getRequiredProperty("local.server.port")
    val request =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port$path"))
            .header("X-User-Id", userId)
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    return response.statusCode() to response.body()
}
