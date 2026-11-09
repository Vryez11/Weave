package com.weave.like

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * `weave.like.counter.*`. 값과 근거는 `application.yml`과 `docs/experiments/idempotent-like.md`에 있다.
 * [shards]는 [Strategy.SHARDED]일 때만 쓰고, 그때는 반드시 있어야 한다. 잘못된 값이면 서비스가 뜨지 않는다.
 */
@ConfigurationProperties("weave.like.counter")
data class LikeCounterProperties(
    val strategy: Strategy,
    val shards: Int? = null,
) {
    init {
        require(strategy != Strategy.SHARDED || (shards != null && shards >= 1)) { "sharded에는 1 이상의 shards가 필요하다: $shards" }
    }

    enum class Strategy {
        /** 비교 기준선(S-11). 동시 요청에서 수가 어긋난다. */
        READ_MODIFY_WRITE,
        ATOMIC,
        SHARDED,
    }
}

/** 좋아요 수를 고치는 방식을 고른다(add-idempotent-like design 결정 6). 호출자의 트랜잭션 안에서 돈다. */
@Component
@EnableConfigurationProperties(LikeCounterProperties::class)
class LikeCounter(
    private val properties: LikeCounterProperties,
    private val repository: PostLikeCountRepository,
) {
    fun add(
        userId: Long,
        postId: Long,
        delta: Long,
    ) {
        when (properties.strategy) {
            LikeCounterProperties.Strategy.READ_MODIFY_WRITE -> readModifyWrite(postId, delta)
            LikeCounterProperties.Strategy.ATOMIC -> repository.addAtomically(postId, SINGLE_SHARD, delta)
            // 사용자로 칸을 고르면 같은 사용자의 +1과 -1이 같은 칸에 들어가 칸의 값이 음수가 되지 않는다
            LikeCounterProperties.Strategy.SHARDED ->
                repository.addAtomically(postId, Math.floorMod(userId, requireNotNull(properties.shards)), delta)
        }
    }

    private fun readModifyWrite(
        postId: Long,
        delta: Long,
    ) {
        val id = PostLikeCountId(postId, SINGLE_SHARD)
        val count = repository.findByIdOrNull(id) ?: PostLikeCount(id = id, likeCount = 0)
        count.likeCount += delta
        repository.save(count)
    }

    private companion object {
        const val SINGLE_SHARD = 0
    }
}
