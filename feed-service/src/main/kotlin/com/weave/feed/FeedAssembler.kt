package com.weave.feed

import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.user.UserResponse
import com.weave.feed.gateway.AdGateway
import com.weave.feed.gateway.LikeGateway
import com.weave.feed.gateway.PostGateway
import com.weave.feed.gateway.RecommendationGateway
import com.weave.feed.gateway.UserGateway
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 하위 서비스를 불러 피드를 만든다 (design 결정 4, 아키텍처 4-1).
 *
 * post, recommendation, ad를 한꺼번에 시작하고, post가 끝나면 user와 like를 함께 시작한다.
 * 기다리는 시간에 상한이 없고, 하나라도 실패하면 `join()`이 `CompletionException`을 던져 피드 전체가 실패한다.
 *
 * [timeBudget]과 [clock]은 요청 시간 예산(add-call-isolation design 결정 4, tasks 2.6)이 쓴다. 아직 쓰지 않는다.
 * 예산 값(`weave.feed.time-budget`)은 tasks 4.3에서 넣으므로 그전에는 없을 수 있다.
 */
@Component
class FeedAssembler(
    private val postGateway: PostGateway,
    private val userGateway: UserGateway,
    private val likeGateway: LikeGateway,
    private val recommendationGateway: RecommendationGateway,
    private val adGateway: AdGateway,
    @Value("\${weave.feed.time-budget:#{null}}") private val timeBudget: Duration?,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun assemble(
        userId: Long,
        size: Int,
    ): FeedResponse {
        val posts = postGateway.getLatestPosts(size).thenCompose { fillDetails(userId, it) }
        val recommendations = recommendationGateway.getRecommendations()
        val ads = adGateway.getAds()

        return FeedResponse(
            posts = posts.join(),
            recommendations = recommendations.join().map { FeedRecommendation(it.postId, it.title, it.reason) },
            ads = ads.join().map { FeedAd(it.id, it.title, it.imageUrl, it.landingUrl) },
        )
    }

    private fun fillDetails(
        userId: Long,
        posts: List<PostResponse>,
    ): CompletableFuture<List<FeedPost>> {
        if (posts.isEmpty()) return CompletableFuture.completedFuture(emptyList())

        val users = userGateway.getUsers(posts.map { it.authorId }.distinct())
        val likes = likeGateway.getLikes(userId, posts.map { it.id })
        return users.thenCombine(likes) { userList, likeList -> toFeedPosts(posts, userList, likeList) }
    }

    private fun toFeedPosts(
        posts: List<PostResponse>,
        users: List<UserResponse>,
        likes: List<LikeResponse>,
    ): List<FeedPost> {
        val usersById = users.associateBy { it.id }
        val likesByPostId = likes.associateBy { it.postId }
        return posts.map { post ->
            val like = likesByPostId[post.id]
            FeedPost(
                id = post.id,
                title = post.title,
                content = post.content,
                createdAt = post.createdAt,
                author = usersById[post.authorId]?.let { FeedAuthor(it.id, it.nickname, it.profileImageUrl) },
                like =
                    FeedLike(
                        count = like?.likeCount ?: 0,
                        myLike = if (like?.liked == true) MyLike.LIKED else MyLike.NOT_LIKED,
                    ),
            )
        }
    }
}
