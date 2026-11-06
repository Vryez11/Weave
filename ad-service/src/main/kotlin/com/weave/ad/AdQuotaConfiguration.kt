package com.weave.ad

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

// ad 요청 한도(design 결정 3). weave.ad.quota.enabled가 true일 때만(application-experiment.yml) 필터와 quota 엔드포인트를 둔다.
// 시계는 컨텍스트의 Clock을 쓰고 없을 때만 시스템 시계를 쓴다. 테스트가 시각을 옮길 수 있게 하려는 것이다.
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("weave.ad.quota.enabled")
class AdQuotaConfiguration {
    @Bean
    fun adQuota(clock: ObjectProvider<Clock>): AdQuota = AdQuota(clock.getIfAvailable { Clock.systemUTC() })

    @Bean
    fun adQuotaEndpoint(quota: AdQuota): AdQuotaEndpoint = AdQuotaEndpoint(quota)

    // Filter Bean은 Boot가 order대로 등록한다
    @Bean
    fun adQuotaFilter(quota: AdQuota): AdQuotaFilter = AdQuotaFilter(quota)
}
