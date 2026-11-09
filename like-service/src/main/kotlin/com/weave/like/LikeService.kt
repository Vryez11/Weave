package com.weave.like

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class LikeService(
    private val postLikeRepository: PostLikeRepository,
    private val postLikeCountRepository: PostLikeCountRepository,
) {
    /**
     * 사용자의 좋아요 상태를 [liked]로 만든다. [version]이 반영해 둔 값보다 크지 않으면 바꾸지 않고 지금 상태를 돌려준다.
     *
     * 행 잠금을 먼저 잡고 그 안에서 판단한다(add-idempotent-like design 결정 4). READ COMMITTED여야 없는 행에 갭 잠금을 잡지 않고,
     * 잠근 뒤의 읽기가 마지막으로 커밋된 값을 본다. 잠금 순서는 언제나 좋아요 행 → 카운터 행이라 순환이 없다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun setLike(
        userId: Long,
        postId: Long,
        liked: Boolean,
        version: Long,
    ): LikeStatus {
        postLikeRepository.lockOrCreate(userId, postId, Instant.now(Clock.systemUTC()))
        val row = checkNotNull(postLikeRepository.findByUserIdAndPostId(userId, postId)) { "방금 만든 좋아요 행이 없다" }

        val decision = LikeIntent.decide(LikeState(row.liked, row.version), liked, version)
        if (decision.applied) {
            postLikeRepository.updateState(userId, postId, decision.state.liked, decision.state.version)
        }
        if (decision.countDelta != 0L) {
            postLikeCountRepository.addAtomically(postId, ATOMIC_SHARD, decision.countDelta)
        }

        val count = postLikeCountRepository.sumByPostIds(listOf(postId)).singleOrNull()?.likeCount ?: 0
        return LikeStatus(postId = postId, likeCount = count, liked = decision.state.liked)
    }

    /** 게시글마다 좋아요 수와 사용자의 좋아요 여부. 같은 ID는 한 번만, 요청 순서대로 준다. */
    @Transactional(readOnly = true)
    fun findAll(
        userId: Long,
        postIds: Collection<Long>,
    ): List<LikeStatus> {
        val ids = postIds.distinct()
        val counts = postLikeCountRepository.sumByPostIds(ids).associate { it.postId to it.likeCount }
        val likedPostIds = postLikeRepository.findAllByUserIdAndPostIdInAndLikedTrue(userId, ids).mapTo(HashSet()) { it.postId }
        return ids.map { LikeStatus(postId = it, likeCount = counts[it] ?: 0, liked = it in likedPostIds) }
    }

    private companion object {
        const val ATOMIC_SHARD = 0
    }
}

data class LikeStatus(
    val postId: Long,
    val likeCount: Long,
    val liked: Boolean,
)
