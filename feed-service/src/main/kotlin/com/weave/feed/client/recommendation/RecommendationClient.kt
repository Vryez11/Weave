package com.weave.feed.client.recommendation

import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange

/** recommendation-service의 `recommendation` API. 개수는 서비스 기본값을 쓴다. */
@HttpExchange
interface RecommendationClient {
    @GetExchange("/recommendations")
    fun getRecommendations(): RecommendationsResponse
}

data class RecommendationsResponse(
    val recommendations: List<RecommendationResponse>,
)

data class RecommendationResponse(
    val postId: Long,
    val title: String,
    val reason: String,
)
