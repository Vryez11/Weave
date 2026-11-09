package com.weave.like

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/** `post-like` 델타의 "동시 요청의 정합성" 시나리오 (F-003). 잠금 충돌이 5xx로 보이지 않는지도 함께 본다. */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.endpoints.web.exposure.include=health,likeconsistency"],
)
class PostLikeConcurrencyScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        suspend fun context(): ApplicationContext = testContextManager().testContext.applicationContext

        beforeEach { context().clearLikes() }

        test("F-003 같은 사용자의 같은 요청이 동시에 온다: 20개 동시에 보내면 모두 200이고 true·1이며 liked 행은 하나다") {
            val context = context()
            val http = context.likeHttp()

            val responses = concurrently(20) { http.putLike(5, userId = 1, liked = true, version = 1) }

            withClue("상태 코드 ${responses.map { it.status }}") {
                responses.forAll {
                    it.status shouldBe 200
                    it.json() shouldBe mapOf("postId" to 5, "liked" to true, "likeCount" to 1)
                }
            }
            http.like(5) shouldBe mapOf("postId" to 5, "likeCount" to 1, "liked" to true)
            context.likedRows(5) shouldBe 1L
        }

        test("F-003 서로 다른 사용자가 동시에 누른다: 50명이 동시에 누르면 모두 200이고 likeCount 50이며 불일치 검사에 없다") {
            val context = context()
            val http = context.likeHttp()

            val responses = concurrently(50) { http.putLike(5, userId = it + 1, liked = true, version = 1) }

            withClue("상태 코드 ${responses.map { it.status }}") { responses.forAll { it.status shouldBe 200 } }
            http.like(5)["likeCount"] shouldBe 50
            http.mismatches(5).shouldBeEmpty()
        }
    })
