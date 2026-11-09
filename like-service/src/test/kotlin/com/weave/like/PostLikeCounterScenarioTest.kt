package com.weave.like

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * `post-like` 델타의 "좋아요 수 갱신 방식" 시나리오 (F-003).
 *
 * 방식마다 애플리케이션을 직접 띄운다. 테이블은 `MySqlTestConfiguration`의 같은 컨테이너에 남으므로 시나리오마다 먼저 비운다.
 */
@Tags("Integration")
class PostLikeCounterScenarioTest :
    FunSpec({
        test("F-003 원자적 UPDATE로 몰린다: 50명이 게시글 5에 동시에 누르면 likeCount는 50이다") {
            start("atomic").use { application ->
                application.clearLikes()
                val http = application.likeHttp()

                val responses = concurrently(50) { http.putLike(5, userId = it + 1) }

                responses.forAll { it.status shouldBe 200 }
                http.like(5)["likeCount"] shouldBe 50
            }
        }

        test("F-003 카운터 분할로 몰린다: 50명이 누르고 10명이 취소하면 likeCount는 40이고 불일치 검사에 없다") {
            start("sharded", "--weave.like.counter.shards=4").use { application ->
                application.clearLikes()
                val http = application.likeHttp()

                concurrently(50) { http.putLike(5, userId = it + 1, version = 1) }.forAll { it.status shouldBe 200 }
                concurrently(10) { http.putLike(5, userId = it + 1, liked = false, version = 2) }.forAll { it.status shouldBe 200 }

                http.like(5)["likeCount"] shouldBe 40
                http.mismatches(5).shouldBeEmpty()
            }
        }

        test("F-003 방식을 바꿔도 수가 이어진다: 원자적 UPDATE로 3개가 쌓인 뒤 카운터 분할로 다른 사용자가 누르면 likeCount는 4다") {
            start("atomic").use { application ->
                application.clearLikes()
                val http = application.likeHttp()
                (1..3).forEach { http.putLike(5, userId = it).status shouldBe 200 }
            }

            start("sharded", "--weave.like.counter.shards=4").use { application ->
                val http = application.likeHttp()

                http.putLike(5, userId = 4).status shouldBe 200

                http.like(5, userId = 4)["likeCount"] shouldBe 4
            }
        }
    })

/** 포트는 명령줄 인자로 넘긴다. `properties(...)`는 `application.yml`에 덮인다(like-service `CLAUDE.md`). */
private fun start(
    strategy: String,
    vararg args: String,
): ConfigurableApplicationContext =
    SpringApplicationBuilder(LikeServiceApplication::class.java)
        .run(
            "--server.port=0",
            "--weave.like.counter.strategy=$strategy",
            "--management.endpoints.web.exposure.include=health,likeconsistency",
            *args,
        )
