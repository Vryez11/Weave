package com.weave.feed

import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.user.UserResponse
import com.weave.feed.gateway.AdGateway
import com.weave.feed.gateway.DownstreamFailure
import com.weave.feed.gateway.LikeGateway
import com.weave.feed.gateway.PostGateway
import com.weave.feed.gateway.RecommendationGateway
import com.weave.feed.gateway.TimeBudgetExhaustedException
import com.weave.feed.gateway.UserGateway
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 하위 서비스를 불러 피드를 만든다 (design 결정 4, 아키텍처 4-1).
 *
 * post, recommendation, ad를 한꺼번에 시작하고, post가 끝나면 user와 like를 함께 시작한다.
 * 하나라도 실패하면 그 실패가 `CompletionException`으로 올라가 피드 전체가 실패한다.
 *
 * 요청 시간 예산(add-call-isolation design 결정 4): 요청을 받은 시각에 [timeBudget]만큼 뒤를 마감으로 정하고(시계는 [clock]),
 * 의존성마다 마감까지 남은 시간만 기다린다. post가 끝났을 때 남은 시간이 없으면 user와 like를 부르지 않는다. 마감이 지나면
 * 끝나지 않은 의존성마다(부르지 않은 user·like 포함) `budget_exhausted` 실패 로그를 남기고 [TimeBudgetExhaustedException]으로
 * 끝낸 뒤 그 예외로 실패한다. 예산 값(`weave.feed.time-budget`)은 tasks 4.3에서 넣으므로 그전에는 없을 수 있고, 없으면 상한 없이 기다린다.
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
        val budget = RequestBudget(timeBudget?.let { clock.instant() + it }, clock)
        val posts = budget.call("post") { postGateway.getLatestPosts(size) }.thenCompose { fillDetails(budget, userId, it) }
        val recommendations = budget.call("recommendation") { recommendationGateway.getRecommendations() }
        val ads = budget.call("ad") { adGateway.getAds() }

        return FeedResponse(
            posts = budget.await(posts),
            recommendations = budget.await(recommendations).map { FeedRecommendation(it.postId, it.title, it.reason) },
            ads = budget.await(ads).map { FeedAd(it.id, it.title, it.imageUrl, it.landingUrl) },
        )
    }

    private fun fillDetails(
        budget: RequestBudget,
        userId: Long,
        posts: List<PostResponse>,
    ): CompletableFuture<List<FeedPost>> {
        if (posts.isEmpty()) return CompletableFuture.completedFuture(emptyList())

        val users = budget.call("user") { userGateway.getUsers(posts.map { it.authorId }.distinct()) }
        val likes = budget.call("like") { likeGateway.getLikes(userId, posts.map { it.id }) }
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

/**
 * 피드 요청 하나의 마감 시각과 그 요청이 시작한 의존성 호출 (design 결정 4). [deadline]이 없으면 상한 없이 기다린다.
 *
 * 마감이 지나면 [expire]가 끝나지 않은 호출을 모두 [TimeBudgetExhaustedException]으로 끝내고 실패 로그를 남긴다. 그 뒤에 시작하려는
 * 호출(다른 스레드에서 post가 막 끝나 부르려던 user·like)은 부르지 않고 같은 방식으로 바로 끝낸다. 먼저 끝낸 호출은 [IsolatedCalls]가
 * 실패 로그를 다시 남기지 않는다.
 */
internal class RequestBudget(
    private val deadline: Instant?,
    private val clock: Clock,
) {
    private val started = mutableListOf<Pair<String, CompletableFuture<*>>>()
    private var expired = false

    /** 마감까지 남은 시간. 마감이 지났으면 0이다. 마감이 없으면 null이다. */
    fun remaining(): Duration? = deadline?.let { maxOf(Duration.between(clock.instant(), it), Duration.ZERO) }

    /**
     * 남은 시간이 있으면 [start]로 [dependency]를 부른다. 없으면 부르지 않고, 마감 처리 때(이미 지났으면 바로) `budget_exhausted`로 끝날
     * future를 돌려준다.
     */
    fun <T> call(
        dependency: String,
        start: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val notCalled = CompletableFuture<T>()
        synchronized(this) {
            if (!expired) {
                val future = if (remaining() == Duration.ZERO) notCalled else start()
                started += dependency to future
                return future
            }
        }
        exhaust(dependency, notCalled)
        return notCalled
    }

    /**
     * 마감까지 [future]를 기다려 값을 돌려준다. 실패하면 그 실패를 `CompletionException`으로 던진다.
     * 마감이 지나면 [expire]한 뒤 [TimeBudgetExhaustedException]을 던진다.
     */
    fun <T> await(future: CompletableFuture<T>): T {
        val remaining = remaining() ?: return future.join()
        try {
            return future.get(remaining.toNanos(), TimeUnit.NANOSECONDS)
        } catch (e: ExecutionException) {
            throw CompletionException(e.cause)
        } catch (_: TimeoutException) {
            expire()
            // 기다리는 동안 다른 이유로 먼저 실패했다면 그 실패를 그대로 던진다
            return future.join()
        }
    }

    /** 끝나지 않은 호출을 모두 [TimeBudgetExhaustedException]으로 끝내고 의존성마다 실패 로그를 남긴다. */
    fun expire() {
        val pending =
            synchronized(this) {
                expired = true
                started.toList()
            }
        pending.forEach { (dependency, future) -> exhaust(dependency, future) }
    }

    private fun exhaust(
        dependency: String,
        future: CompletableFuture<*>,
    ) {
        val error = TimeBudgetExhaustedException(dependency)
        if (future.completeExceptionally(error)) DownstreamFailure.log(dependency, error)
    }
}
