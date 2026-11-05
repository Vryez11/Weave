package com.weave.observability

import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 모든 서비스에 공통으로 적용하는 로깅·트레이싱 설정. 서비스는 의존성만 추가하면 자동으로 적용된다.
@AutoConfiguration
class ObservabilityAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    class ServletConfiguration {
        // Filter Bean은 Boot가 order대로 등록하고, OncePerRequestFilter라 오류 디스패치에도 건다
        @Bean
        fun traceLoggingFilter(tracer: ObjectProvider<Tracer>): TraceLoggingFilter = TraceLoggingFilter(tracer)
    }
}
