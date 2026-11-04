package com.weave.post

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.inspectors.forAll
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.Instant

/**
 * `post-catalog` 스펙의 시나리오 (F-006).
 *
 * 기댓값은 design 결정 1의 식으로 계산한다. 같은 식으로 만든 값이 매번 오는지 보므로
 * "다시 띄워도 같은 데이터"를 대신한다(design 결정 9).
 */
@Tags("Integration")
@SpringBootTest
@AutoConfigureMockMvc
class PostCatalogScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val mapper = jacksonObjectMapper()

        suspend fun getPosts(query: String = ""): Pair<Int, String> {
            val mockMvc = testContextManager().testContext.applicationContext.getBean(MockMvc::class.java)
            val response =
                mockMvc
                    .get("/posts$query")
                    .andReturn()
                    .response
            return response.status to response.contentAsString
        }

        fun postsOf(body: String): List<Map<String, Any?>> = mapper.readValue<Map<String, List<Map<String, Any?>>>>(body).getValue("posts")

        test("F-006 기본 개수로 조회: size 없이 부르면 최신 게시글 20개가 createdAt 내림차순으로 온다") {
            val (status, body) = getPosts()

            status shouldBe 200
            val posts = postsOf(body)
            posts shouldBe expectedLatestPosts(20)
            posts.forAll { post ->
                post.keys shouldBe setOf("id", "authorId", "title", "content", "createdAt")
            }
            posts.map { Instant.parse(it["createdAt"] as String) } shouldBe
                posts.map { Instant.parse(it["createdAt"] as String) }.sortedDescending()
        }

        test("F-006 개수를 정해 조회: size=5면 최신 게시글 5개가 온다") {
            val (status, body) = getPosts("?size=5")

            status shouldBe 200
            postsOf(body) shouldBe expectedLatestPosts(5)
        }

        listOf("0", "51", "abc").forEach { size ->
            test("F-006 범위를 벗어난 개수: size=$size 이면 400이 온다") {
                val (status, _) = getPosts("?size=$size")

                status shouldBe 400
            }
        }

        test("F-006 다시 띄워도 같은 데이터: size=50 응답이 고정 데이터의 식과 같다") {
            val (status, body) = getPosts("?size=50")

            status shouldBe 200
            postsOf(body) shouldBe expectedLatestPosts(50)
        }

        test("F-006 작성자 ID 범위: size=50 응답의 모든 authorId가 1 이상 50 이하다") {
            val (status, body) = getPosts("?size=50")

            status shouldBe 200
            val posts = postsOf(body)
            posts.size shouldBe 50
            posts.forAll { post ->
                (post["authorId"] as Int) shouldBeInRange 1..50
            }
        }
    })

private const val POST_COUNT = 200
private val BASE_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** design 결정 1의 식으로 만든 최신 게시글 [size]개. 최신은 id 200이고 id가 클수록 늦게 쓰였다. */
private fun expectedLatestPosts(size: Int): List<Map<String, Any?>> =
    (POST_COUNT downTo POST_COUNT - size + 1).map { id ->
        mapOf(
            "id" to id,
            "authorId" to (id - 1) % 50 + 1,
            "title" to "게시글 $id",
            "content" to "게시글 ${id}의 본문",
            "createdAt" to BASE_TIME.plus(Duration.ofMinutes(id.toLong())).toString(),
        )
    }
