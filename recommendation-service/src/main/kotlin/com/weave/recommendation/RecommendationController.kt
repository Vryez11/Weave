package com.weave.recommendation

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class RecommendationController(
    private val recommendationCatalog: RecommendationCatalog,
) {
    /** `X-User-Id`는 받지 않는다. 추천은 사용자와 관계없다(PRD 8장, 개인화 제외). */
    @GetMapping("/recommendations")
    fun recommendations(
        @RequestParam(defaultValue = "$DEFAULT_SIZE") size: Int,
    ): RecommendationsResponse {
        if (size !in SIZE_RANGE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 ${SIZE_RANGE.first}~${SIZE_RANGE.last}만 받는다: $size")
        }
        return RecommendationsResponse(recommendationCatalog.top(size))
    }

    data class RecommendationsResponse(
        val recommendations: List<Recommendation>,
    )

    companion object {
        private const val DEFAULT_SIZE = 5
        private val SIZE_RANGE = 1..20
    }
}
