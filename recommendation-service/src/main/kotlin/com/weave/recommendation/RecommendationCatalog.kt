package com.weave.recommendation

import org.springframework.stereotype.Component

/**
 * 시작할 때 design 결정 1의 식으로 만든 고정 추천을 메모리에 들고 있는다.
 * 같은 식으로 만들므로 몇 번을 다시 띄워도 데이터가 같고, 사용자와 관계없이 같다.
 */
@Component
class RecommendationCatalog {
    private val recommendations: List<Recommendation> = (1L..RECOMMENDATION_COUNT).map(::fixedRecommendation)

    /** k 순서대로 앞에서부터 [size]개. */
    fun top(size: Int): List<Recommendation> = recommendations.take(size)

    companion object {
        const val RECOMMENDATION_COUNT = 20L

        fun fixedRecommendation(k: Long): Recommendation {
            val postId = 10 * k
            return Recommendation(
                postId = postId,
                title = "게시글 $postId",
                reason = "많이 본 게시글",
            )
        }
    }
}
