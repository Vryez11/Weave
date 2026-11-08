package com.weave.feed

import com.weave.feed.degradation.PostCacheProperties
import com.weave.feed.degradation.PostStaleCache
import com.weave.feed.degradation.UserCacheProperties
import com.weave.feed.degradation.UserProfileCache
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import java.time.Duration

/**
 * 고도화 4의 대체 설정값 (F-002, add-graceful-degradation design 결정 4~7, 14).
 *
 * [IsolationSettingsTest]처럼 `spring.config.location`으로 main의 `application.yml`만 읽는다. 테스트 기본값
 * (`classpath:/config/application.yml`의 Redis `localhost:1` 등)이 섞이면 실제 값을 볼 수 없어서다.
 * 값을 바꾸면 design의 근거와 함께 이 테스트를 고친다. Redis에 연결하지 않아도 컨텍스트는 뜬다(연결은 첫 명령 때 맺는다).
 */
@Tags("Integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application.yml"],
)
class DegradationSettingsTest :
    FunSpec({
        extensions(SpringExtension())

        suspend fun context(): ApplicationContext = testContextManager().testContext.applicationContext

        test("F-002 post 캐시 읽기 상한: Redis 명령 타임아웃과 연결 타임아웃이 30ms다 (design 결정 6)") {
            val redis = context().getBean(DataRedisProperties::class.java)
            redis.timeout shouldBe Duration.ofMillis(30)
            redis.connectTimeout shouldBe Duration.ofMillis(30)
        }

        test("F-002 user 로컬 캐시: 유효 기간 1시간, 최대 10,000명이고 캐시가 켜져 있다 (design 결정 7)") {
            val context = context()
            val properties = context.getBean(UserCacheProperties::class.java)
            properties.expireAfterWrite shouldBe Duration.ofHours(1)
            properties.maximumSize shouldBe 10_000L
            context.getBean(UserProfileCache::class.java) shouldNotBe UserProfileCache.DISABLED
        }

        test("F-002 post stale 캐시: stale 허용 10분, 같은 키 쓰기 간격 1초이고 캐시가 켜져 있다 (design 결정 4, 5)") {
            val context = context()
            val properties = context.getBean(PostCacheProperties::class.java)
            properties.maxStaleness shouldBe Duration.ofMinutes(10)
            properties.writeInterval shouldBe Duration.ofSeconds(1)
            context.getBean(PostStaleCache::class.java) shouldNotBe PostStaleCache.DISABLED
        }

        test("F-002 Redis 헬스는 feed-service 헬스에 들지 않는다 (design 결정 4)") {
            context().environment.getProperty("management.health.redis.enabled") shouldBe "false"
        }
    })
