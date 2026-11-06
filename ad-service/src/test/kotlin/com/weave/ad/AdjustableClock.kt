package com.weave.ad

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 테스트가 시각을 멈추고 옮길 수 있는 시계. [fixedInstant]가 없으면 실제 시계를 따른다.
 *
 * 요청 한도는 컨텍스트의 `Clock`을 쓰고 없을 때만 시스템 시계를 쓴다(design 결정 3).
 * 이 시계를 쓰려면 테스트 클래스에 `@Import(AdjustableClockConfiguration::class)`를 붙인다.
 */
class AdjustableClock : Clock() {
    @Volatile
    var fixedInstant: Instant? = null

    fun freeze() {
        fixedInstant = Instant.now()
    }

    fun advance(duration: Duration) {
        fixedInstant = checkNotNull(fixedInstant) { "멈춘 시계만 옮길 수 있다" }.plus(duration)
    }

    fun useRealTime() {
        fixedInstant = null
    }

    override fun instant(): Instant = fixedInstant ?: Instant.now()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock {
        require(zone == ZoneOffset.UTC) { "UTC만 쓴다: $zone" }
        return this
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AdjustableClockConfiguration {
    @Bean
    fun adjustableClock(): AdjustableClock = AdjustableClock()
}
