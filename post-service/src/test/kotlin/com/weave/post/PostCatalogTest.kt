package com.weave.post

import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import java.time.Instant

class PostCatalogTest :
    FunSpec({
        val catalog = PostCatalog()

        test("F-006 고정 게시글은 결정 1의 식으로 만든다") {
            PostCatalog.fixedPost(1) shouldBe
                Post(1, 1, "게시글 1", "게시글 1의 본문", Instant.parse("2026-01-01T00:01:00Z"))
            PostCatalog.fixedPost(51).authorId shouldBe 1
            PostCatalog.fixedPost(200) shouldBe
                Post(200, 50, "게시글 200", "게시글 200의 본문", Instant.parse("2026-01-01T03:20:00Z"))
        }

        test("F-006 최신 게시글은 createdAt 내림차순으로 앞에서부터 자른다") {
            val posts = catalog.latest(5)

            posts.map { it.id } shouldBe listOf(200L, 199L, 198L, 197L, 196L)
        }

        test("F-006 게시글은 200개이고 작성자는 모두 1~50이다") {
            val posts = catalog.latest(1_000)

            posts shouldHaveSize 200
            posts.forAll { it.authorId shouldBeInRange 1L..50L }
            posts.map { it.createdAt } shouldBe posts.map { it.createdAt }.sortedDescending()
        }

        test("F-006 두 번 만들어도 데이터가 같다") {
            PostCatalog().latest(200) shouldBe catalog.latest(200)
        }
    })
