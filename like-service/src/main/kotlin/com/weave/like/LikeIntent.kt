package com.weave.like

/** 사용자 한 명의 게시글 하나에 대해 반영해 둔 상태. */
data class LikeState(
    val liked: Boolean,
    val version: Long,
)

/** 요청을 처리한 뒤의 상태와 좋아요 수에 더할 값. [applied]가 false면 늦게 온 요청이라 아무것도 바꾸지 않는다. */
data class LikeDecision(
    val state: LikeState,
    val applied: Boolean,
    val countDelta: Long,
)

/** 요청의 `version`이 반영해 둔 값보다 클 때만 바꾼다(add-idempotent-like design 결정 2). */
object LikeIntent {
    fun decide(
        current: LikeState,
        liked: Boolean,
        version: Long,
    ): LikeDecision {
        if (version <= current.version) return LikeDecision(current, applied = false, countDelta = 0)
        val delta =
            when {
                liked == current.liked -> 0L
                liked -> 1L
                else -> -1L
            }
        return LikeDecision(LikeState(liked, version), applied = true, countDelta = delta)
    }
}
