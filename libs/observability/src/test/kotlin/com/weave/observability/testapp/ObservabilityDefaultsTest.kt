package com.weave.observability.testapp

import com.weave.observability.ObservabilityDefaultsPostProcessor
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment

/**
 * 라이브러리 기본 속성(design 결정 2)과 OTLP 내보내기 조건 (F-004).
 *
 * 서비스와 같은 자동 설정을 받는 [LoggingTestApplication]을 웹 없이 띄워 속성과 Bean을 본다.
 */
@Tags("Integration")
class ObservabilityDefaultsTest :
    FunSpec({
        fun run(
            vararg properties: String,
            profile: String? = null,
        ): ConfigurableApplicationContext =
            SpringApplicationBuilder(LoggingTestApplication::class.java)
                .web(WebApplicationType.NONE)
                .properties(*properties)
                .apply { if (profile != null) profiles(profile) }
                .run()

        fun ConfigurableApplicationContext.otlpSpanExporters(): List<SpanExporter> =
            getBeansOfType(SpanExporter::class.java).values.filter { it.javaClass.name.contains("Otlp") }

        test("F-004 기본 속성: 서비스가 정하지 않으면 샘플링 비율이 1.0이고 환경 변수·시스템 속성보다 뒤에 있다") {
            run().use { context ->
                val environment = context.environment
                environment.getProperty("management.tracing.sampling.probability") shouldBe "1.0"
                // 맨 뒤에는 Boot가 시작 뒤에 붙이는 applicationInfo가 올 수 있어, 앞선 소스와의 순서로 본다
                val names = environment.propertySources.map { it.name }
                val defaults = names.indexOf(ObservabilityDefaultsPostProcessor.PROPERTY_SOURCE_NAME)
                defaults shouldBeGreaterThan names.indexOf(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                defaults shouldBeGreaterThan names.indexOf(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            }
        }

        test("F-004 기본 속성: 서비스의 application.yml 값이 라이브러리 기본값을 이긴다") {
            run(profile = "sampling-override").use { context ->
                context.environment.getProperty("management.tracing.sampling.probability") shouldBe "0.25"
            }
        }

        test("F-004 OTLP 내보내기: 주소가 없으면 OTLP 스팬 내보내기 Bean이 없다") {
            run().use { context ->
                context.otlpSpanExporters().shouldBeEmpty()
            }
        }

        test("F-004 OTLP 내보내기: 주소를 주면 OTLP 스팬 내보내기 Bean이 생긴다 (위 테스트의 대조군)") {
            run("management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces").use { context ->
                context.otlpSpanExporters().shouldNotBeEmpty()
            }
        }
    })
