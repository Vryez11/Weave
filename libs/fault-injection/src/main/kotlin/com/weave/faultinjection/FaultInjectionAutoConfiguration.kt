package com.weave.faultinjection

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 하위 서비스의 앱 수준 장애 주입(design 결정 2). weave.fault-injection.enabled가 true일 때만 필터와 faults 엔드포인트를 둔다.
// 노출 목록은 서비스의 application-experiment.yml이 정한다. 라이브러리는 프로필 파일을 싣지 않는다.
@AutoConfiguration
@ConditionalOnBooleanProperty("weave.fault-injection.enabled")
@EnableConfigurationProperties(FaultInjectionProperties::class)
class FaultInjectionAutoConfiguration {
    @Bean
    fun faultState(properties: FaultInjectionProperties): FaultState = FaultState(properties.defaults())

    @Bean
    fun faultsEndpoint(state: FaultState): FaultsEndpoint = FaultsEndpoint(state)

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    class ServletConfiguration {
        // Filter Bean은 Boot가 order대로 등록한다
        @Bean
        fun faultInjectionFilter(state: FaultState): FaultInjectionFilter = FaultInjectionFilter(state)
    }
}
