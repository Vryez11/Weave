package com.weave.feed

import com.weave.feed.client.ad.AdResponse
import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.recommendation.RecommendationResponse
import com.weave.feed.client.user.UserResponse
import com.weave.feed.gateway.AdGateway
import com.weave.feed.gateway.LikeGateway
import com.weave.feed.gateway.PostGateway
import com.weave.feed.gateway.RecommendationGateway
import com.weave.feed.gateway.UserGateway
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.concurrent.CompletableFuture.completedFuture

class FeedAssemblerTest :
    FunSpec({
        val postGateway = mockk<PostGateway>()
        val userGateway = mockk<UserGateway>()
        val likeGateway = mockk<LikeGateway>()
        val recommendationGateway = mockk<RecommendationGateway>()
        val adGateway = mockk<AdGateway>()
        val assembler = FeedAssembler(postGateway, userGateway, likeGateway, recommendationGateway, adGateway)

        val createdAt = Instant.parse("2026-01-01T00:12:00Z")

        fun post(
            id: Long,
            authorId: Long,
        ) = PostResponse(id = id, authorId = authorId, title = "게시글 $id", content = "게시글 ${id}의 본문", createdAt = createdAt)

        fun user(id: Long) = UserResponse(id = id, nickname = "사용자$id", profileImageUrl = "https://cdn.example.com/users/$id.png")

        fun author(id: Long) = FeedAuthor(id = id, nickname = "사용자$id", profileImageUrl = "https://cdn.example.com/users/$id.png")

        beforeEach {
            clearAllMocks()
            every { recommendationGateway.getRecommendations() } returns
                completedFuture(listOf(RecommendationResponse(postId = 10, title = "게시글 10", reason = "많이 본 게시글")))
            every { adGateway.getAds() } returns
                completedFuture(listOf(AdResponse(id = 1, title = "광고 1", imageUrl = "https://a/1.png", landingUrl = "https://l/1")))
        }

        test("F-006 게시글 순서를 지키며 작성자와 좋아요를 채우고, 추천과 광고를 그대로 담는다") {
            every { postGateway.getLatestPosts(20) } returns completedFuture(listOf(post(12, 4), post(10, 3), post(11, 3)))
            every { userGateway.getUsers(any()) } returns completedFuture(listOf(user(3), user(4)))
            every { likeGateway.getLikes(1, any()) } returns
                completedFuture(
                    listOf(
                        LikeResponse(postId = 11, likeCount = 0, liked = false),
                        LikeResponse(postId = 12, likeCount = 5, liked = false),
                        LikeResponse(postId = 10, likeCount = 2, liked = true),
                    ),
                )

            val feed = assembler.assemble(userId = 1, size = 20)

            feed.posts.map { it.id } shouldBe listOf(12L, 10L, 11L)
            feed.posts.map { it.author } shouldBe listOf(author(4), author(3), author(3))
            feed.posts.map { it.like } shouldBe
                listOf(FeedLike(5, MyLike.NOT_LIKED), FeedLike(2, MyLike.LIKED), FeedLike(0, MyLike.NOT_LIKED))
            feed.posts.first() shouldBe
                FeedPost(
                    id = 12,
                    title = "게시글 12",
                    content = "게시글 12의 본문",
                    createdAt = createdAt,
                    author = author(4),
                    like = FeedLike(5, MyLike.NOT_LIKED),
                )
            feed.recommendations shouldBe listOf(FeedRecommendation(postId = 10, title = "게시글 10", reason = "많이 본 게시글"))
            feed.ads shouldBe listOf(FeedAd(id = 1, title = "광고 1", imageUrl = "https://a/1.png", landingUrl = "https://l/1"))
        }

        test("F-006 작성자 프로필이 없으면 그 게시글의 author는 null이다") {
            every { postGateway.getLatestPosts(20) } returns completedFuture(listOf(post(12, 4), post(10, 3)))
            every { userGateway.getUsers(any()) } returns completedFuture(listOf(user(3)))
            every { likeGateway.getLikes(1, any()) } returns completedFuture(emptyList())

            val feed = assembler.assemble(userId = 1, size = 20)

            feed.posts.map { it.author } shouldBe listOf(null, author(3))
        }

        test("F-006 like-service가 주지 않은 게시글의 좋아요는 0과 NOT_LIKED다") {
            every { postGateway.getLatestPosts(20) } returns completedFuture(listOf(post(12, 4)))
            every { userGateway.getUsers(any()) } returns completedFuture(listOf(user(4)))
            every { likeGateway.getLikes(1, any()) } returns completedFuture(emptyList())

            assembler
                .assemble(userId = 1, size = 20)
                .posts
                .single()
                .like shouldBe FeedLike(0, MyLike.NOT_LIKED)
        }

        test("F-006 게시글이 없으면 user와 like를 부르지 않고 빈 목록을 준다") {
            every { postGateway.getLatestPosts(20) } returns completedFuture(emptyList())

            val feed = assembler.assemble(userId = 1, size = 20)

            feed.posts shouldBe emptyList()
            verify(exactly = 0) { userGateway.getUsers(any()) }
            verify(exactly = 0) { likeGateway.getLikes(any(), any()) }
        }

        test("F-006 user에는 작성자 ID를 중복 없이, like에는 게시글 ID와 요청 사용자를 넘긴다") {
            every { postGateway.getLatestPosts(5) } returns completedFuture(listOf(post(10, 3), post(11, 3), post(12, 4)))
            every { userGateway.getUsers(any()) } returns completedFuture(emptyList())
            every { likeGateway.getLikes(any(), any()) } returns completedFuture(emptyList())

            assembler.assemble(userId = 7, size = 5)

            verify(exactly = 1) { userGateway.getUsers(listOf(3L, 4L)) }
            verify(exactly = 1) { likeGateway.getLikes(7, listOf(10L, 11L, 12L)) }
        }
    })
