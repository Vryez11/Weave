package com.weave.like

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/** `post-like` 델타의 "좋아요 수 불일치 검사" 시나리오 (F-003). 어긋남은 SQL로 직접 만든다. */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.endpoints.web.exposure.include=health,likeconsistency"],
)
class PostLikeConsistencyScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        suspend fun context(): ApplicationContext = testContextManager().testContext.applicationContext

        beforeEach { context().clearLikes() }

        test("F-003 어긋남이 없다: 사용자 1과 2가 누른 뒤 검사하면 mismatches는 비어 있다") {
            val http = context().likeHttp()
            http.putLike(5, userId = 1).status shouldBe 200
            http.putLike(5, userId = 2).status shouldBe 200

            http.mismatches().shouldBeEmpty()
        }

        test("F-003 어긋남이 있다: 저장된 수 3, liked 행 2개면 postId 5, likeCount 3, likedRows 2가 있다") {
            val context = context()
            val http = context.likeHttp()
            http.putLike(5, userId = 1).status shouldBe 200
            http.putLike(5, userId = 2).status shouldBe 200
            context.jdbc().update("UPDATE post_like_count SET like_count = 3 WHERE post_id = 5")

            http.mismatches(5) shouldBe listOf(mapOf("postId" to 5, "likeCount" to 3, "likedRows" to 2))
        }

        test("F-003 행은 있는데 저장된 수가 없다: liked 행 1개에 수가 없으면 postId 5, likeCount 0, likedRows 1이 있다") {
            val context = context()
            val http = context.likeHttp()
            http.putLike(5, userId = 1).status shouldBe 200
            context.jdbc().update("DELETE FROM post_like_count WHERE post_id = 5")

            http.mismatches() shouldContain mapOf("postId" to 5, "likeCount" to 0, "likedRows" to 1)
        }

        test("F-003 취소한 행은 세지 않는다: 눌렀다가 취소하면 mismatches는 비어 있다") {
            val http = context().likeHttp()
            http.putLike(5, liked = true, version = 1).status shouldBe 200
            http.putLike(5, liked = false, version = 2).status shouldBe 200

            http.mismatches(5).shouldBeEmpty()
        }
    })
