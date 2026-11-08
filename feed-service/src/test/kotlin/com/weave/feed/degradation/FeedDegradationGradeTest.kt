package com.weave.feed.degradation

import com.weave.feed.FeedAd
import com.weave.feed.FeedAuthor
import com.weave.feed.FeedLike
import com.weave.feed.FeedRecommendation
import com.weave.feed.MyLike
import com.weave.feed.StaleSection
import com.weave.feed.client.ad.AdResponse
import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.recommendation.RecommendationResponse
import com.weave.feed.client.user.UserResponse
import com.weave.feed.gateway.TimeBudgetExhaustedException
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.TimeoutException

/**
 * 등급표(PRD 3장)대로 실패를 대체하는 응답 구성 (add-graceful-degradation design 결정 1~3, 10, tasks 2.2).
 *
 * 정상 입력은 [FeedDegradationTest]가 본다. user를 로컬 캐시로 채우는 것(`cached`)은 tasks 2.3이고, 여기서 user 실패는 작성자가
 * 모두 `unknown`이다.
 */
class FeedDegradationGradeTest :
    FunSpec({
        val createdAt = Instant.parse("2026-01-01T00:12:00Z")

        fun post(
            id: Long,
            authorId: Long,
        ) = PostResponse(id = id, authorId = authorId, title = "게시글 $id", content = "게시글 ${id}의 본문", createdAt = createdAt)

        fun user(id: Long) = UserResponse(id = id, nickname = "사용자$id", profileImageUrl = "https://cdn.example.com/users/$id.png")

        fun author(id: Long) = FeedAuthor(id = id, nickname = "사용자$id", profileImageUrl = "https://cdn.example.com/users/$id.png")

        fun unknownAuthor(id: Long) = FeedAuthor(id = id, nickname = null, profileImageUrl = null, unknown = true)

        val posts = PostSource.Fresh(listOf(post(12, 4), post(10, 3), post(11, 3)))
        val users = Result.success(listOf(user(3), user(4)))
        val likes =
            Result.success(
                listOf(
                    LikeResponse(postId = 10, likeCount = 2, liked = true),
                    LikeResponse(postId = 11, likeCount = 0, liked = false),
                    LikeResponse(postId = 12, likeCount = 5, liked = false),
                ),
            )
        val recommendations = Result.success(listOf(RecommendationResponse(postId = 10, title = "게시글 10", reason = "많이 본 게시글")))
        val ads = Result.success(listOf(AdResponse(id = 1, title = "광고 1", imageUrl = "https://a/1.png", landingUrl = "https://l/1")))
        val failure = Result.failure<Nothing>(IllegalStateException("503"))

        val normalAuthors = listOf(author(4), author(3), author(3))
        val normalLikes = listOf(FeedLike(5, MyLike.NOT_LIKED), FeedLike(2, MyLike.LIKED), FeedLike(0, MyLike.NOT_LIKED))
        val normalRecommendations = listOf(FeedRecommendation(postId = 10, title = "게시글 10", reason = "많이 본 게시글"))
        val normalAds = listOf(FeedAd(id = 1, title = "광고 1", imageUrl = "https://a/1.png", landingUrl = "https://l/1"))

        test("F-002 user 실패: 게시글마다 작성자 ID만 있는 unknown 작성자이고, 나머지 영역은 그대로이며 user만 빠진다") {
            val composed = FeedDegradation.compose(posts, failure, likes, recommendations, ads)

            composed.response.posts.map { it.id } shouldBe listOf(12L, 10L, 11L)
            composed.response.posts.map { it.author } shouldBe listOf(unknownAuthor(4), unknownAuthor(3), unknownAuthor(3))
            composed.response.posts.map { it.like } shouldBe normalLikes
            composed.response.recommendations shouldBe normalRecommendations
            composed.response.ads shouldBe normalAds
            composed.response.degradation.missing shouldBe listOf("user")
            composed.sections["user"] shouldBe SectionOutcome.MISSING
        }

        test("F-002 like 실패: 모든 게시글의 좋아요가 count null과 UNKNOWN이고 like만 빠진다") {
            val composed = FeedDegradation.compose(posts, users, failure, recommendations, ads)

            composed.response.posts.map { it.like } shouldBe List(3) { FeedLike(count = null, myLike = MyLike.UNKNOWN) }
            composed.response.posts.map { it.author } shouldBe normalAuthors
            composed.response.recommendations shouldBe normalRecommendations
            composed.response.ads shouldBe normalAds
            composed.response.degradation.missing shouldBe listOf("like")
            composed.sections["like"] shouldBe SectionOutcome.MISSING
        }

        test("F-002 recommendation 실패: recommendations가 빈 목록이고 recommendation만 빠진다") {
            val composed = FeedDegradation.compose(posts, users, likes, failure, ads)

            composed.response.recommendations shouldBe emptyList()
            composed.response.posts.map { it.author } shouldBe normalAuthors
            composed.response.posts.map { it.like } shouldBe normalLikes
            composed.response.ads shouldBe normalAds
            composed.response.degradation.missing shouldBe listOf("recommendation")
            composed.sections["recommendation"] shouldBe SectionOutcome.MISSING
        }

        test("F-002 ad 실패: ads가 빈 목록이고 ad만 빠진다") {
            val composed = FeedDegradation.compose(posts, users, likes, recommendations, failure)

            composed.response.ads shouldBe emptyList()
            composed.response.posts.map { it.author } shouldBe normalAuthors
            composed.response.posts.map { it.like } shouldBe normalLikes
            composed.response.recommendations shouldBe normalRecommendations
            composed.response.degradation.missing shouldBe listOf("ad")
            composed.sections["ad"] shouldBe SectionOutcome.MISSING
        }

        test("F-002 중요·선택 등급이 모두 실패: 게시글은 같은 순서로 남고 missing은 의존성 순서대로 넷, post는 fresh다") {
            val composed = FeedDegradation.compose(posts, failure, failure, failure, failure)

            composed.response.posts.map { it.id } shouldBe listOf(12L, 10L, 11L)
            composed.response.degradation.missing shouldBe listOf("user", "like", "recommendation", "ad")
            composed.response.degradation.stale shouldBe emptyList()
            composed.sections shouldBe
                mapOf(
                    "post" to SectionOutcome.FRESH,
                    "user" to SectionOutcome.MISSING,
                    "like" to SectionOutcome.MISSING,
                    "recommendation" to SectionOutcome.MISSING,
                    "ad" to SectionOutcome.MISSING,
                )
        }

        test("F-002 stale 게시글과 빠진 영역이 함께면 degradation에 둘 다 있다") {
            val cachedAt = Instant.parse("2026-11-08T01:23:45Z")

            val composed = FeedDegradation.compose(PostSource.Stale(posts.posts, cachedAt), users, failure, recommendations, ads)

            composed.response.degradation.missing shouldBe listOf("like")
            composed.response.degradation.stale shouldBe listOf(StaleSection(section = "post", cachedAt = cachedAt))
            composed.sections["post"] shouldBe SectionOutcome.STALE
        }

        test("F-002 실패의 종류(시간 상한, 서킷 열림, 벌크헤드 거절, 예산 초과)와 관계없이 같은 방법으로 대체한다") {
            listOf(
                TimeoutException(),
                CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("user")),
                BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("user")),
                TimeBudgetExhaustedException("user"),
            ).forEach { error ->
                val composed =
                    FeedDegradation.compose(
                        posts,
                        Result.failure(error),
                        Result.failure(error),
                        Result.failure(error),
                        Result.failure(error),
                    )

                composed.response.posts.map { it.author } shouldBe listOf(unknownAuthor(4), unknownAuthor(3), unknownAuthor(3))
                composed.response.degradation.missing shouldBe listOf("user", "like", "recommendation", "ad")
            }
        }
    })
