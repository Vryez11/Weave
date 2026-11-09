package com.weave.like

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class LikeService(
    private val postLikeRepository: PostLikeRepository,
    private val postLikeCountRepository: PostLikeCountRepository,
) {
    /**
     * 사용자의 좋아요 상태를 뒤집는다.
     *
     * 읽고 고쳐 저장하는 방식을 일부러 쓴다(design 결정 6). 동시 요청에서 수가 어긋나고,
     * 같은 사용자의 동시 첫 요청은 유니크 위반으로 실패한다. 고도화 6(F-003)에서 재현할 대상이라 막지 않는다.
     */
    @Transactional
    fun toggle(
        userId: Long,
        postId: Long,
    ): LikeStatus {
        val existing = postLikeRepository.findByUserIdAndPostId(userId, postId)
        val liked =
            if (existing != null) {
                postLikeRepository.delete(existing)
                false
            } else {
                postLikeRepository.save(PostLike(userId = userId, postId = postId, createdAt = Instant.now(Clock.systemUTC())))
                true
            }

        val id = PostLikeCountId(postId = postId, shard = 0)
        val count = postLikeCountRepository.findByIdOrNull(id) ?: PostLikeCount(id = id, likeCount = 0)
        count.likeCount += if (liked) 1 else -1
        postLikeCountRepository.save(count)

        return LikeStatus(postId = postId, likeCount = count.likeCount, liked = liked)
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
}

data class LikeStatus(
    val postId: Long,
    val likeCount: Long,
    val liked: Boolean,
)
