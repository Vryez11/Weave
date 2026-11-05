package com.weave.observability

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.ResourcePropertySource

/**
 * 라이브러리 기본 속성(`observability-defaults.properties`)을 속성 소스 맨 뒤에 붙인다(design 결정 2).
 *
 * 서비스의 `application.yml`과 환경 변수가 언제나 이긴다. 설정 파일(ConfigData)이 속성 소스를 붙인 뒤에 돌아야
 * 맨 뒤 자리를 지키므로 가장 늦은 순서로 둔다.
 */
class ObservabilityDefaultsPostProcessor :
    EnvironmentPostProcessor,
    Ordered {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val sources = environment.propertySources
        if (sources.contains(PROPERTY_SOURCE_NAME)) return
        val resource = ClassPathResource(RESOURCE, javaClass.classLoader)
        sources.addLast(ResourcePropertySource(PROPERTY_SOURCE_NAME, resource))
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    companion object {
        const val PROPERTY_SOURCE_NAME = "observabilityDefaults"
        private const val RESOURCE = "observability-defaults.properties"
    }
}
