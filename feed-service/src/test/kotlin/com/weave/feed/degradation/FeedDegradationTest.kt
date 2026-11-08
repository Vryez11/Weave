package com.weave.feed.degradation

import com.weave.feed.Degradation
import com.weave.feed.FeedAd
import com.weave.feed.FeedAuthor
import com.weave.feed.FeedLike
import com.weave.feed.FeedPost
import com.weave.feed.FeedRecommendation
import com.weave.feed.MyLike
import com.weave.feed.StaleSection
import com.weave.feed.client.ad.AdResponse
import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.recommendation.RecommendationResponse
import com.weave.feed.client.user.UserResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 응답 구성 함수 [FeedDegradation.compose]의 정상 입력 (add-graceful-degradation design 결정 2, 3, 10, tasks 2.1).
 *
 * 등급표(PRD 3장)대로 실패를 대체하는 단언은 tasks 2.2부터 다른 파일에 둔다. 이 파일은 커밋 뒤 Ralph 루프가 고치지 못한다.
 */
class FeedDegradationTest :
    FunSpec({
        val createdAt = Instant.parse("2026-01-01T00:12:00Z")

        fun post(
            id: Long,
            authorId: Long,
        ) = PostResponse(id = id, authorId = authorId, title = "게시글 $id", content = "게시글 ${id}의 본문", createdAt = createdAt)

        fun user(id: Long) = UserResponse(id = id, nickname = "사용자$id", profileImageUrl = "https://cdn.example.com/users/$id.png")

        fun author(id: Long) =
            FeedAuthor(id = id, nickname = "사용자$id", profileImageUrl = "https://cdn.example.com/users/$id.png", unknown = false)

        val recommendations = listOf(RecommendationResponse(postId = 10, title = "게시글 10", reason = "많이 본 게시글"))
        val ads = listOf(AdResponse(id = 1, title = "광고 1", imageUrl = "https://a/1.png", landingUrl = "https://l/1"))
        val allFresh =
            mapOf(
                "post" to SectionOutcome.FRESH,
                "user" to SectionOutcome.FRESH,
                "like" to SectionOutcome.FRESH,
                "recommendation" to SectionOutcome.FRESH,
                "ad" to SectionOutcome.FRESH,
            )

        test("F-002 모든 결과가 정상: 게시글 순서대로 작성자(unknown false)와 좋아요를 채우고, degradation은 빈 목록 둘, 영역은 모두 fresh다") {
            val composed =
                FeedDegradation.compose(
                    posts = PostSource.Fresh(listOf(post(12, 4), post(10, 3), post(11, 3))),
                    users = Result.success(listOf(user(3), user(4))),
                    likes =
                        Result.success(
                            listOf(
                                LikeResponse(postId = 11, likeCount = 0, liked = false),
                                LikeResponse(postId = 12, likeCount = 5, liked = false),
                                LikeResponse(postId = 10, likeCount = 2, liked = true),
                            ),
                        ),
                    recommendations = Result.success(recommendations),
                    ads = Result.success(ads),
                )

            val response = composed.response
            response.posts.first() shouldBe
                FeedPost(
                    id = 12,
                    title = "게시글 12",
                    content = "게시글 12의 본문",
                    createdAt = createdAt,
                    author = author(4),
                    like = FeedLike(5, MyLike.NOT_LIKED),
                )
            response.posts.map { it.id } shouldBe listOf(12L, 10L, 11L)
            response.posts.map { it.author } shouldBe listOf(author(4), author(3), author(3))
            response.posts.map { it.like } shouldBe
                listOf(FeedLike(5, MyLike.NOT_LIKED), FeedLike(2, MyLike.LIKED), FeedLike(0, MyLike.NOT_LIKED))
            response.recommendations shouldBe listOf(FeedRecommendation(postId = 10, title = "게시글 10", reason = "많이 본 게시글"))
            response.ads shouldBe listOf(FeedAd(id = 1, title = "광고 1", imageUrl = "https://a/1.png", landingUrl = "https://l/1"))
            response.degradation shouldBe Degradation(missing = emptyList(), stale = emptyList())
            composed.sections shouldBe allFresh
            composed.sections.keys.toList() shouldBe listOf("post", "user", "like", "recommendation", "ad")
        }

        test("F-002 user-service가 프로필을 돌려주지 않은 작성자는 author가 null이고, user 영역은 빠진 것이 아니다") {
            val composed =
                FeedDegradation.compose(
                    posts = PostSource.Fresh(listOf(post(12, 4), post(10, 3))),
                    users = Result.success(listOf(user(3))),
                    likes = Result.success(emptyList()),
                    recommendations = Result.success(recommendations),
                    ads = Result.success(ads),
                )

            composed.response.posts.map { it.author } shouldBe listOf(null, author(3))
            composed.response.degradation.missing shouldBe emptyList()
            composed.sections["user"] shouldBe SectionOutcome.FRESH
        }

        test("F-002 like-service가 주지 않은 게시글의 좋아요는 0과 NOT_LIKED다") {
            val composed =
                FeedDegradation.compose(
                    posts = PostSource.Fresh(listOf(post(12, 4))),
                    users = Result.success(listOf(user(4))),
                    likes = Result.success(emptyList()),
                    recommendations = Result.success(recommendations),
                    ads = Result.success(ads),
                )

            composed.response.posts
                .single()
                .like shouldBe FeedLike(0, MyLike.NOT_LIKED)
        }

        test("F-002 게시글이 없으면(user·like는 빈 목록의 성공) posts가 빈 목록이고 영역은 모두 fresh다") {
            val composed =
                FeedDegradation.compose(
                    posts = PostSource.Fresh(emptyList()),
                    users = Result.success(emptyList()),
                    likes = Result.success(emptyList()),
                    recommendations = Result.success(emptyList()),
                    ads = Result.success(emptyList()),
                )

            composed.response.posts shouldBe emptyList()
            composed.response.degradation shouldBe Degradation(missing = emptyList(), stale = emptyList())
            composed.sections shouldBe allFresh
        }

        test("F-002 stale 캐시의 게시글이면 degradation.stale에 post와 cachedAt이 하나 있고 post 영역은 stale이다") {
            val cachedAt = Instant.parse("2026-11-08T01:23:45Z")

            val composed =
                FeedDegradation.compose(
                    posts = PostSource.Stale(listOf(post(10, 3)), cachedAt),
                    users = Result.success(listOf(user(3))),
                    likes = Result.success(emptyList()),
                    recommendations = Result.success(recommendations),
                    ads = Result.success(ads),
                )

            composed.response.posts.map { it.id } shouldBe listOf(10L)
            composed.response.degradation shouldBe
                Degradation(missing = emptyList(), stale = listOf(StaleSection(section = "post", cachedAt = cachedAt)))
            composed.sections shouldBe allFresh + ("post" to SectionOutcome.STALE)
        }
    })
