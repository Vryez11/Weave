package com.weave.feed

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * 테스트가 앞으로 옮길 수 있는 시계 (add-graceful-degradation design 결정 11).
 *
 * 실제 시간에 [advance]로 더한 만큼을 얹은 시각을 준다. 실제 시간과 함께 흐르므로 요청 시간 예산과 호출 시간 상한은 평소처럼 재지고,
 * 테스트는 요청과 요청 사이에 [advance]로 "5분 뒤"를 만든다. 요청이 도는 동안에는 옮기지 않는다.
 * [withZone]으로 만든 시계도 같은 이동 거리를 함께 쓴다.
 */
class MovableClock private constructor(
    private val offset: AtomicReference<Duration>,
    private val zone: ZoneId,
) : Clock() {
    constructor() : this(AtomicReference(Duration.ZERO), ZoneOffset.UTC)

    fun advance(duration: Duration) {
        offset.updateAndGet { it + duration }
    }

    override fun instant(): Instant = Instant.now() + offset.get()

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MovableClock(offset, zone)
}

/**
 * 컨텍스트의 `Clock`을 [MovableClock]으로 바꾼다. 시나리오 테스트에 `@Import(MovableClockConfiguration::class)`로 붙인다.
 *
 * main의 `Clock` Bean(`systemUTC`, design 결정 11)을 덮어쓰지 않고 다른 이름의 `@Primary` Bean으로 둔다. Boot는 Bean 덮어쓰기를 막는다.
 * `Clock`을 받는 곳(`FeedAssembler`, `PostStaleCache`, `UserProfileCache`의 `Ticker`)은 모두 이 시계를 받는다.
 * 테스트는 `getBean(MovableClock::class.java)`로 꺼내 옮긴다.
 */
@TestConfiguration(proxyBeanMethods = false)
class MovableClockConfiguration {
    @Bean
    @Primary
    fun movableClock(): MovableClock = MovableClock()
}
