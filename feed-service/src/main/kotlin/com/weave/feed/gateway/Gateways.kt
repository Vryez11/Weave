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
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/*
 * 의존성마다 하나씩 두는 Gateway Bean (design 결정 2, 아키텍처 5-2).
 *
 * 조회 메서드는 [IsolatedCalls]로만 하위 서비스를 부른다(add-call-isolation design 결정 1). 서킷 브레이커, 호출 시간 상한,
 * 의존성별 벌크헤드와 실패 로그는 그쪽이 맡고, Gateway는 의존성 이름과 부를 함수만 넘긴다.
 */

@Component
class PostGateway(
    private val client: PostClient,
    private val isolated: IsolatedCalls,
) {
    fun getLatestPosts(size: Int): CompletableFuture<List<PostResponse>> = isolated.call("post") { client.getLatestPosts(size).posts }
}

@Component
class UserGateway(
    private val client: UserClient,
    private val isolated: IsolatedCalls,
) {
    fun getUsers(ids: List<Long>): CompletableFuture<List<UserResponse>> = isolated.call("user") { client.getUsers(ids).users }
}

@Component
class LikeGateway(
    private val client: LikeClient,
    private val isolated: IsolatedCalls,
) {
    fun getLikes(
        userId: Long,
        postIds: List<Long>,
    ): CompletableFuture<List<LikeResponse>> = isolated.call("like") { client.getLikes(userId, postIds).likes }

    /** 토글은 병렬로 부를 것이 없어 요청 스레드에서 바로 부른다. */
    fun toggle(
        userId: Long,
        postId: Long,
    ): ToggleResponse =
        try {
            client.toggle(userId, postId)
        } catch (e: Exception) {
            DownstreamFailure.log("like", e)
            throw e
        }
}

@Component
class RecommendationGateway(
    private val client: RecommendationClient,
    private val isolated: IsolatedCalls,
) {
    fun getRecommendations(): CompletableFuture<List<RecommendationResponse>> =
        isolated.call("recommendation") { client.getRecommendations().recommendations }
}

@Component
class AdGateway(
    private val client: AdClient,
    private val isolated: IsolatedCalls,
) {
    fun getAds(): CompletableFuture<List<AdResponse>> = isolated.call("ad") { client.getAds().ads }
}
