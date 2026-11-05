package com.weave.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.tracing.Tracer
import jakarta.servlet.FilterChain
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * actuator 경로에서 체인이 예외를 던지면 `TraceLoggingFilter`가 `unhandled_exception` ERROR 줄을 남기고 다시 던진다(design 결정 5).
 * Tomcat의 `[dispatcherServlet]` 줄은 logback에서 막으므로, 이 줄이 없으면 예외가 어디에도 남지 않는다.
 * 필터의 로거에 [ListAppender]를 직접 붙여 본다. Spring 컨텍스트 없이 돌아 logback-spring.xml은 쓰지 않는다.
 */
class TraceLoggingFilterActuatorExceptionTest :
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

        fun ILoggingEvent.keyValues(): Map<String, Any?> = keyValuePairs.orEmpty().associate { it.key to it.value }

        test("F-004 actuator 경로에서 처리되지 않은 예외는 unhandled_exception ERROR 줄 하나로 남고 다시 던져진다") {
            val failure = IllegalStateException("scrape failed")
            val chain = FilterChain { _, _ -> throw failure }

            val thrown =
                shouldThrow<IllegalStateException> {
                    filter.doFilter(MockHttpServletRequest("GET", "/actuator/x"), MockHttpServletResponse(), chain)
                }

            thrown shouldBeSameInstanceAs failure
            val events = appender.list.toList()
            events shouldHaveSize 1
            val event = events.single()
            event.level shouldBe Level.ERROR
            event.keyValues() shouldBe mapOf("event" to "unhandled_exception", "method" to "GET", "path" to "/actuator/x")
            event.throwableProxy.className shouldBe IllegalStateException::class.java.name
            event.throwableProxy.message shouldBe "scrape failed"
            events.filter { it.keyValues()["event"] == "http_request" }.shouldBeEmpty()
        }
    })
