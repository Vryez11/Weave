package com.weave.feed

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 요청 시간 예산, post stale 판단, user 캐시 유효 기간이 함께 쓰는 시계 (add-graceful-degradation design 결정 11).
 *
 * 통합 테스트는 이 Bean을 덮어쓰지 않고 다른 이름의 `@Primary` 시계(`MovableClockConfiguration`)를 둔다.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
