package com.weave.like

import com.weave.like.LikeCounterProperties.Strategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.util.Optional

class LikeCounterTest :
    FunSpec({
        val repository = mockk<PostLikeCountRepository>()

        beforeEach { justRun { repository.addAtomically(any(), any(), any()) } }

        test("F-003 atomic은 0번 칸에 원자적으로 더한다") {
            LikeCounter(LikeCounterProperties(Strategy.ATOMIC), repository).add(userId = 7, postId = 5, delta = 1)

            verify { repository.addAtomically(5, 0, 1) }
        }

        test("F-003 sharded는 사용자 ID로 고른 칸에 더하고 같은 사용자의 취소도 같은 칸에 간다") {
            val counter = LikeCounter(LikeCounterProperties(Strategy.SHARDED, shards = 4), repository)

            counter.add(userId = 7, postId = 5, delta = 1)
            counter.add(userId = 7, postId = 5, delta = -1)

            verify { repository.addAtomically(5, 3, 1) }
            verify { repository.addAtomically(5, 3, -1) }
        }

        test("F-003 read-modify-write는 0번 칸을 읽어 더한 값을 저장한다") {
            val saved = slot<PostLikeCount>()
            every { repository.findById(PostLikeCountId(5, 0)) } returns
                Optional.of(PostLikeCount(PostLikeCountId(5, 0), likeCount = 2))
            every { repository.save(capture(saved)) } answers { saved.captured }

            LikeCounter(LikeCounterProperties(Strategy.READ_MODIFY_WRITE), repository).add(userId = 7, postId = 5, delta = 1)

            saved.captured.likeCount shouldBe 3
        }

        test("F-003 read-modify-write는 칸이 없으면 만든다") {
            val saved = slot<PostLikeCount>()
            every { repository.findById(PostLikeCountId(6, 0)) } returns Optional.empty()
            every { repository.save(capture(saved)) } answers { saved.captured }

            LikeCounter(LikeCounterProperties(Strategy.READ_MODIFY_WRITE), repository).add(userId = 7, postId = 6, delta = 1)

            saved.captured.likeCount shouldBe 1
        }

        listOf(null, 0, -1).forEach { shards ->
            test("F-003 sharded에 shards가 $shards 면 시작할 때 실패한다") {
                shouldThrow<IllegalArgumentException> { LikeCounterProperties(Strategy.SHARDED, shards = shards) }
            }
        }

        test("F-003 설정의 read-modify-write는 느슨한 바인딩으로 읽히고 없는 방식은 실패한다") {
            fun bind(strategy: String): LikeCounterProperties =
                Binder(MapConfigurationPropertySource(mapOf("weave.like.counter.strategy" to strategy)))
                    .bindOrCreate("weave.like.counter", LikeCounterProperties::class.java)

            bind("read-modify-write").strategy shouldBe Strategy.READ_MODIFY_WRITE
            shouldThrow<BindException> { bind("nonsense") }
        }
    })
