package com.weave.like

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** `post-like` 델타의 "좋아요 상태 지정", "같은 요청의 반복", "늦게 도착한 요청" 시나리오 (F-003). */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostLikeStateScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        suspend fun context(): ApplicationContext = testContextManager().testContext.applicationContext

        suspend fun http(): LikeHttp = context().likeHttp()

        fun LikeHttp.Response.shouldBeState(
            postId: Int,
            liked: Boolean,
            likeCount: Int,
        ) {
            withClue("응답 $status: $body") { status shouldBe 200 }
            json() shouldBe mapOf("postId" to postId, "liked" to liked, "likeCount" to likeCount)
        }

        beforeEach { context().clearLikes() }

        context("좋아요 상태 지정") {
            test("F-003 처음 누른다: 아무도 누르지 않은 게시글 5에 liked true, version 1이면 200과 liked true, likeCount 1이 온다") {
                http().putLike(5, userId = 1, liked = true, version = 1).shouldBeState(5, liked = true, likeCount = 1)
            }

            test("F-003 취소한다: version 1로 누른 뒤 liked false, version 2면 liked false, likeCount 0이 온다") {
                val http = http()
                http.putLike(5, liked = true, version = 1).status shouldBe 200

                http.putLike(5, liked = false, version = 2).shouldBeState(5, liked = false, likeCount = 0)
            }

            test("F-003 다른 사용자의 좋아요는 그대로다: 사용자 1과 2가 누른 뒤 사용자 1이 취소하면 false·1이고 사용자 2는 true다") {
                val http = http()
                http.putLike(5, userId = 1, version = 1).status shouldBe 200
                http.putLike(5, userId = 2, version = 1).status shouldBe 200

                http.putLike(5, userId = 1, liked = false, version = 2).shouldBeState(5, liked = false, likeCount = 1)

                http.like(5, userId = 2) shouldBe mapOf("postId" to 5, "likeCount" to 1, "liked" to true)
            }

            test("F-003 누른 적 없는 게시글을 취소한다: liked false, version 1이면 liked false, likeCount 0이 온다") {
                http().putLike(5, liked = false, version = 1).shouldBeState(5, liked = false, likeCount = 0)
            }

            listOf(
                "본문 없음" to null,
                "liked 없음" to """{"version":1}""",
                "version 없음" to """{"liked":true}""",
                "version 0" to """{"liked":true,"version":0}""",
                "version이 숫자가 아님" to """{"liked":true,"version":"abc"}""",
            ).forEach { (name, body) ->
                test("F-003 본문이 잘못됐다: $name 이면 400이 오고 좋아요 수는 그대로다") {
                    val http = http()

                    http.putBody(5, body).status shouldBe 400

                    http.like(5)["likeCount"] shouldBe 0
                }
            }

            listOf("0", "abc").forEach { postId ->
                test("F-003 잘못된 게시글 ID: postId $postId 면 400이 온다") {
                    http().putLike(postId).status shouldBe 400
                }
            }

            test("F-003 토글 API는 없다: POST /likes/5/toggle은 2xx가 오지 않고 좋아요 수는 그대로다") {
                val http = http()

                http.postToggle(5).status shouldBeGreaterThanOrEqual 300

                http.like(5)["likeCount"] shouldBe 0
            }
        }

        context("같은 요청의 반복") {
            test("F-003 같은 요청을 다시 보낸다: liked true, version 1을 네 번 더 보내도 모두 200과 true·1이 온다") {
                val http = http()
                http.putLike(5, liked = true, version = 1).status shouldBe 200

                repeat(4) { http.putLike(5, liked = true, version = 1).shouldBeState(5, liked = true, likeCount = 1) }
            }

            test("F-003 이미 그 상태인데 더 큰 version이 온다: liked true, version 2면 true·1이 온다") {
                val http = http()
                http.putLike(5, liked = true, version = 1).status shouldBe 200

                http.putLike(5, liked = true, version = 2).shouldBeState(5, liked = true, likeCount = 1)
            }
        }

        context("늦게 도착한 요청") {
            test("F-003 취소 뒤에 첫 요청의 재시도가 도착한다: true 1, false 2 뒤에 true 1이 오면 200과 false·0이 온다") {
                val http = http()
                http.putLike(5, liked = true, version = 1).status shouldBe 200
                http.putLike(5, liked = false, version = 2).status shouldBe 200

                http.putLike(5, liked = true, version = 1).shouldBeState(5, liked = false, likeCount = 0)
            }

            test("F-003 먼저 보낸 요청이 나중에 도착한다: false 2가 먼저, true 1이 나중이면 최종 상태는 false·0이다") {
                val http = http()
                http.putLike(5, liked = false, version = 2).status shouldBe 200

                http.putLike(5, liked = true, version = 1).status shouldBe 200

                http.like(5) shouldBe mapOf("postId" to 5, "likeCount" to 0, "liked" to false)
            }

            test("F-003 어떤 순서로 몇 번이 와도 마지막 의도로 끝난다: 임의 순서와 중복의 최종 상태는 가장 큰 version의 liked이고 수는 행 수와 같다") {
                val http = http()
                val context = context()
                val nextPostId = AtomicLong(1_000)

                checkAll(ITERATIONS, Arb.list(Arb.boolean(), 1..8), Arb.long()) { intents, seed ->
                    val postId = nextPostId.incrementAndGet()
                    val random = Random(seed)
                    val requests = intents.mapIndexed { index, liked -> liked to index + 1L }
                    val sent = (requests + requests.filter { random.nextBoolean() }).shuffled(random)

                    sent.forEach { (liked, version) -> http.putLike(postId, liked = liked, version = version).status shouldBe 200 }

                    val expected = requests.last().first
                    withClue("보낸 순서 $sent") {
                        http.like(postId)["liked"] shouldBe expected
                        http.like(postId)["likeCount"] shouldBe (if (expected) 1 else 0)
                        context.likedRows(postId) shouldBe (if (expected) 1L else 0L)
                    }
                }
            }

            test("F-003 같은 version에 다른 값이 온다: true 3 뒤에 false 3이 오면 200과 true·1이 온다") {
                val http = http()
                http.putLike(5, liked = true, version = 3).status shouldBe 200

                http.putLike(5, liked = false, version = 3).shouldBeState(5, liked = true, likeCount = 1)
            }
        }
    })

/** 임의 순서 시나리오를 돌리는 횟수. 시도마다 HTTP 요청이 최대 16개다. */
private const val ITERATIONS = 30
