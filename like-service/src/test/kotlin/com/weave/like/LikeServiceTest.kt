package com.weave.like

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

class LikeServiceTest :
    FunSpec({
        val postLikeRepository = mockk<PostLikeRepository>()
        val postLikeCountRepository = mockk<PostLikeCountRepository>()
        val service = LikeService(postLikeRepository, postLikeCountRepository)

        fun postLike(
            userId: Long,
            postId: Long,
        ) = PostLike(userId = userId, postId = postId, createdAt = Instant.EPOCH)

        test("F-006 배치 조회는 수와 내 좋아요 여부를 게시글마다 맞춰 준다") {
            every { postLikeCountRepository.findAllById(listOf(3L, 4L, 7L)) } returns
                listOf(PostLikeCount(postId = 4, likeCount = 2), PostLikeCount(postId = 3, likeCount = 5))
            every { postLikeRepository.findAllByUserIdAndPostIdIn(1, listOf(3L, 4L, 7L)) } returns
                listOf(postLike(userId = 1, postId = 4))

            service.findAll(1, listOf(3L, 4L, 7L)) shouldBe
                listOf(
                    LikeStatus(postId = 3, likeCount = 5, liked = false),
                    LikeStatus(postId = 4, likeCount = 2, liked = true),
                    LikeStatus(postId = 7, likeCount = 0, liked = false),
                )
        }

        test("F-006 배치 조회는 같은 ID를 한 번만, 요청 순서대로 준다") {
            every { postLikeCountRepository.findAllById(listOf(5L, 2L)) } returns emptyList()
            every { postLikeRepository.findAllByUserIdAndPostIdIn(1, listOf(5L, 2L)) } returns emptyList()

            service.findAll(1, listOf(5L, 2L, 5L, 2L)).map { it.postId } shouldBe listOf(5L, 2L)
            verify { postLikeCountRepository.findAllById(listOf(5L, 2L)) }
        }
    })
