package com.weave.feed.gateway

import com.weave.feed.client.ad.AdClient
import com.weave.feed.client.ad.AdResponse
import com.weave.feed.client.like.LikeClient
import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.like.ToggleResponse
import com.weave.feed.client.post.PostClient
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.recommendation.RecommendationClient
import com.weave.feed.client.recommendation.RecommendationResponse
import com.weave.feed.client.user.UserClient
import com.weave.feed.client.user.UserResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/*
 * 의존성마다 하나씩 두는 Gateway Bean (design 결정 2, 아키텍처 5-2).
 *
 * 고도화 3의 Resilience4j 어노테이션이 이 메서드들에 붙는다. 조회 메서드는 `CompletableFuture`를 돌려주고,
 * MVP에서는 모든 의존성이 Boot의 `applicationTaskExecutor` 하나를 함께 쓴다(결정 4). 타임아웃도 없다.
 */

@Component
class PostGateway(
    private val client: PostClient,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    fun getLatestPosts(size: Int): CompletableFuture<List<PostResponse>> =
        CompletableFuture.supplyAsync({ client.getLatestPosts(size).posts }, executor)
}

@Component
class UserGateway(
    private val client: UserClient,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    fun getUsers(ids: List<Long>): CompletableFuture<List<UserResponse>> =
        CompletableFuture.supplyAsync({ client.getUsers(ids).users }, executor)
}

@Component
class LikeGateway(
    private val client: LikeClient,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    fun getLikes(
        userId: Long,
        postIds: List<Long>,
    ): CompletableFuture<List<LikeResponse>> = CompletableFuture.supplyAsync({ client.getLikes(userId, postIds).likes }, executor)

    /** 토글은 병렬로 부를 것이 없어 요청 스레드에서 바로 부른다. */
    fun toggle(
        userId: Long,
        postId: Long,
    ): ToggleResponse = client.toggle(userId, postId)
}

@Component
class RecommendationGateway(
    private val client: RecommendationClient,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    fun getRecommendations(): CompletableFuture<List<RecommendationResponse>> =
        CompletableFuture.supplyAsync({ client.getRecommendations().recommendations }, executor)
}

@Component
class AdGateway(
    private val client: AdClient,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {
    fun getAds(): CompletableFuture<List<AdResponse>> = CompletableFuture.supplyAsync({ client.getAds().ads }, executor)
}
