package com.weave.feed.degradation

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import com.weave.feed.client.user.UserResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * user 실패 때 작성자를 채우는 로컬 캐시 (add-graceful-degradation design 결정 7).
 *
 * user 호출이 성공할 때마다 받은 프로필을 [putAll]로 넣고, 실패했을 때만 [getAll]로 읽는다(fallback-only). 정상일 때는 읽지 않으므로
 * 조회 통계(`cache_gets_total{cache="user-profiles"}`)가 곧 대체 조회 수다. 유효 기간은 [Clock]으로 잰다(design 결정 11).
 * 인스턴스 메모리라 재시작하면 비어 있다.
 */
class UserProfileCache private constructor(
    private val cache: Cache<Long, UserResponse>?,
) {
    fun putAll(users: List<UserResponse>) {
        cache?.putAll(users.associateBy { it.id })
    }

    /** [authorIds] 중 유효 기간 안의 프로필이 있는 작성자만 돌려준다. 작성자마다 적중·누락을 하나씩 센다. */
    fun getAll(authorIds: Collection<Long>): Map<Long, UserResponse> = cache?.getAllPresent(authorIds) ?: emptyMap()

    companion object {
        const val NAME = "user-profiles"

        /** 넣지도 찾지도 않는다. user가 실패하면 작성자는 모두 `unknown`이다. */
        val DISABLED = UserProfileCache(null)

        fun create(
            expireAfterWrite: Duration,
            maximumSize: Long,
            clock: Clock,
            meterRegistry: MeterRegistry,
        ): UserProfileCache {
            val cache =
                Caffeine
                    .newBuilder()
                    .expireAfterWrite(expireAfterWrite)
                    .maximumSize(maximumSize)
                    .ticker(clock.asTicker())
                    .recordStats()
                    .build<Long, UserResponse>()
            CaffeineCacheMetrics.monitor(meterRegistry, cache, NAME)
            return UserProfileCache(cache)
        }

        private fun Clock.asTicker() =
            Ticker {
                val now = instant()
                now.epochSecond * 1_000_000_000 + now.nano
            }
    }
}

/**
 * user 로컬 캐시 설정 `weave.user-cache.*` (design 결정 7, 14).
 *
 * 값은 근거와 함께 tasks 4.2에서 main `application.yml`에 넣는다. 그때까지 둘 중 하나라도 없으면 캐시를 쓰지 않는다([UserProfileCache.DISABLED]).
 */
@ConfigurationProperties("weave.user-cache")
data class UserCacheProperties(
    /** 프로필을 넣은 뒤 대체에 쓸 수 있는 시간. */
    val expireAfterWrite: Duration? = null,
    /** 캐시에 둘 작성자 수의 상한. */
    val maximumSize: Long? = null,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserCacheProperties::class)
class UserProfileCacheConfig {
    @Bean
    fun userProfileCache(
        properties: UserCacheProperties,
        clock: Clock,
        meterRegistry: MeterRegistry,
    ): UserProfileCache {
        val expireAfterWrite = properties.expireAfterWrite ?: return UserProfileCache.DISABLED
        val maximumSize = properties.maximumSize ?: return UserProfileCache.DISABLED
        return UserProfileCache.create(expireAfterWrite, maximumSize, clock, meterRegistry)
    }
}
