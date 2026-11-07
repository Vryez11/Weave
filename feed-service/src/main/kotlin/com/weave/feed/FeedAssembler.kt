package com.weave.feed

import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.user.UserResponse
import com.weave.feed.degradation.FeedDegradation
import com.weave.feed.degradation.PostCacheRead
import com.weave.feed.degradation.PostSource
import com.weave.feed.degradation.PostStaleCache
import com.weave.feed.degradation.PostsUnavailableException
import com.weave.feed.degradation.UserProfileCache
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
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 하위 서비스를 불러 피드를 만든다 (design 결정 4, 아키텍처 4-1).
 *
 * post, recommendation, ad를 한꺼번에 시작하고, post가 끝나면 user와 like를 함께 시작한다. 받은 결과로 응답을 만드는 것은
 * [FeedDegradation.compose]다(add-graceful-degradation design 결정 3). post 결과를 먼저 받고, user·like·recommendation·ad의 결과는
 * 실패도 값([Result])으로 받아 넘겨 등급대로 대체한다. user가 성공하면 받은 프로필을 [UserProfileCache]에 넣고, 실패하면 그 캐시에서
 * 작성자를 찾아 넘긴다. post가 성공하면 그 게시글을 [PostStaleCache]에 넘기고(쓰기는 비동기), 실패하면 같은 `size`의 stale 캐시로
 * 게시글을 채운다. 그것도 안 되면 [PostsUnavailableException]이다(design 결정 4, 8). 캐시는 요청 스레드에서 다룬다.
 *
 * 요청 시간 예산(add-call-isolation design 결정 4): 요청을 받은 시각에 [timeBudget]만큼 뒤를 마감으로 정하고(시계는 [clock]),
 * 의존성마다 마감까지 남은 시간만 기다린다. post가 끝났을 때 남은 시간이 없으면 user와 like를 부르지 않는다. 마감이 지나면
 * 끝나지 않은 의존성마다(부르지 않은 user·like 포함) `budget_exhausted` 실패 로그를 남기고 [TimeBudgetExhaustedException]으로
 * 끝내며, 그 의존성은 다른 실패와 같이 등급대로 대체한다. 예산 값은 `weave.feed.time-budget`이다(아키텍처 4-3).
 */
@Component
class FeedAssembler(
    private val postGateway: PostGateway,
    private val userGateway: UserGateway,
    private val likeGateway: LikeGateway,
    private val recommendationGateway: RecommendationGateway,
    private val adGateway: AdGateway,
    @Value("\${weave.feed.time-budget}") private val timeBudget: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val userProfileCache: UserProfileCache = UserProfileCache.DISABLED,
    private val postStaleCache: PostStaleCache = PostStaleCache.DISABLED,
) {
    /** post를 대체할 수 없으면 [PostsUnavailableException]을 던진다(design 결정 8). */
    fun assemble(
        userId: Long,
        size: Int,
    ): FeedResponse {
        val budget = RequestBudget(clock.instant() + timeBudget, clock)
        val posts = budget.call("post") { postGateway.getLatestPosts(size) }
        val freshDetails = posts.thenApply { fetchDetails(budget, userId, it) }
        val recommendations = budget.call("recommendation") { recommendationGateway.getRecommendations() }
        val ads = budget.call("ad") { adGateway.getAds() }

        val (source, details) =
            budget.awaitResult(posts).fold(
                onSuccess = {
                    postStaleCache.write(size, it)
                    PostSource.Fresh(it) to freshDetails
                },
                onFailure = { stalePosts(budget, userId, size) },
            )
        val users = budget.awaitResult(details.thenCompose { it.users })
        return FeedDegradation
            .compose(
                posts = source,
                users = users,
                likes = budget.awaitResult(details.thenCompose { it.likes }),
                recommendations = budget.awaitResult(recommendations),
                ads = budget.awaitResult(ads),
                cachedUsers = cachedUsers(users, source.posts),
            ).response
    }

    /**
     * post가 실패하면 요청 스레드에서 stale 캐시를 읽고, 쓸 수 있으면 그 게시글로 user·like를 시작한다(design 결정 3). 쓸 수 없으면
     * recommendation·ad를 기다리지 않고 [PostsUnavailableException]을 던진다. 남은 호출은 각자의 시간 상한에서 끝난다.
     */
    private fun stalePosts(
        budget: RequestBudget,
        userId: Long,
        size: Int,
    ): Pair<PostSource, CompletableFuture<Details>> =
        when (val cached = postStaleCache.read(size)) {
            is PostCacheRead.Hit -> {
                val details = fetchDetails(budget, userId, cached.posts)
                PostSource.Stale(cached.posts, cached.cachedAt) to completedFuture(details)
            }
            is PostCacheRead.Unusable -> throw PostsUnavailableException(cached.reason)
        }

    /** user가 성공하면 받은 프로필을 캐시에 넣고, 실패하면 게시글의 작성자를 캐시에서 찾는다(design 결정 7). */
    private fun cachedUsers(
        users: Result<List<UserResponse>>,
        posts: List<PostResponse>,
    ): Map<Long, UserResponse> =
        users.fold(
            onSuccess = {
                userProfileCache.putAll(it)
                emptyMap()
            },
            onFailure = { userProfileCache.getAll(posts.map { post -> post.authorId }.distinct()) },
        )

    /** 게시글의 작성자와 좋아요를 함께 부른다. 게시글이 없으면 부르지 않고 둘 다 빈 목록이다. */
    private fun fetchDetails(
        budget: RequestBudget,
        userId: Long,
        posts: List<PostResponse>,
    ): Details {
        if (posts.isEmpty()) return Details(completedFuture(emptyList()), completedFuture(emptyList()))

        return Details(
            users = budget.call("user") { userGateway.getUsers(posts.map { it.authorId }.distinct()) },
            likes = budget.call("like") { likeGateway.getLikes(userId, posts.map { it.id }) },
        )
    }

    /** 게시글 하나 목록으로 시작한 user·like 호출. 결과는 따로 받아 하나가 실패해도 다른 하나는 쓴다(design 결정 3). */
    private class Details(
        val users: CompletableFuture<List<UserResponse>>,
        val likes: CompletableFuture<List<LikeResponse>>,
    )
}

/**
 * 피드 요청 하나의 마감 시각과 그 요청이 시작한 의존성 호출 (design 결정 4).
 *
 * 마감이 지나면 [expire]가 끝나지 않은 호출을 모두 [TimeBudgetExhaustedException]으로 끝내고 실패 로그를 남긴다. 그 뒤에 시작하려는
 * 호출(다른 스레드에서 post가 막 끝나 부르려던 user·like)은 부르지 않고 같은 방식으로 바로 끝낸다. 먼저 끝낸 호출은 [IsolatedCalls]가
 * 실패 로그를 다시 남기지 않는다.
 */
internal class RequestBudget(
    private val deadline: Instant,
    private val clock: Clock,
) {
    private val started = mutableListOf<Pair<String, CompletableFuture<*>>>()
    private var expired = false

    /** 마감까지 남은 시간. 마감이 지났으면 0이다. */
    fun remaining(): Duration = maxOf(Duration.between(clock.instant(), deadline), Duration.ZERO)

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
        val remaining = remaining()
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

    /**
     * [await]처럼 기다리되 실패를 던지지 않고 값으로 돌려준다(add-graceful-degradation design 결정 3). 실패의 종류(HTTP 오류, 시간 상한,
     * 서킷 열림, 벌크헤드 거절, [TimeBudgetExhaustedException])는 가리지 않는다. 실패 로그는 이미 [IsolatedCalls]나 [expire]가 남겼다.
     */
    fun <T> awaitResult(future: CompletableFuture<T>): Result<T> =
        try {
            Result.success(await(future))
        } catch (e: CompletionException) {
            Result.failure(e.cause ?: e)
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
