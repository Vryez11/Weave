package com.weave.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * `TraceLoggingFilter`가 요청 완료 로그를 건너뛰는 경로는 `/actuator`와 `/actuator/`로 시작하는 경로뿐이다(design 결정 5).
 * 필터의 로거에 [ListAppender]를 직접 붙여 본다. Spring 컨텍스트 없이 돌아 logback-spring.xml은 쓰지 않는다.
 */
class TraceLoggingFilterActuatorPathTest :
    FunSpec({
        val filter = TraceLoggingFilter(StaticListableBeanFactory().getBeanProvider(Tracer::class.java))
        val logger = LoggerFactory.getLogger(TraceLoggingFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()

        beforeSpec {
            appender.start()
            logger.addAppender(appender)
        }
        afterSpec {
            logger.detachAppender(appender)
            appender.stop()
        }
        beforeTest { appender.list.clear() }

        fun requestLogPaths(path: String): List<Any?> {
            filter.doFilter(MockHttpServletRequest("GET", path), MockHttpServletResponse(), MockFilterChain())
            return appender.list
                .filter { event -> event.keyValuePairs.orEmpty().any { it.key == "event" && it.value == "http_request" } }
                .map { event -> event.keyValuePairs.first { it.key == "path" }.value }
        }

        listOf("/actuatorx", "/actuator-like", "/actuatorx/health").forEach { path ->
            test("F-004 /actuator로 글자만 시작하는 경로는 요청 완료 로그를 남긴다: $path") {
                requestLogPaths(path) shouldContainExactly listOf(path)
            }
        }

        listOf("/actuator", "/actuator/", "/actuator/health", "/actuator/prometheus").forEach { path ->
            test("F-004 /actuator 경로는 요청 완료 로그를 남기지 않는다: $path") {
                requestLogPaths(path).shouldBeEmpty()
            }
        }
    })
