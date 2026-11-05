package com.weave.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.spi.FilterReply
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TomcatServletExceptionLogFilterTest :
    FunSpec({
        val filter = TomcatServletExceptionLogFilter()

        fun decide(loggerName: String): FilterReply =
            filter.decide(
                LoggingEvent().apply {
                    this.loggerName = loggerName
                    level = Level.ERROR
                },
            )

        listOf(
            "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]",
            "org.apache.catalina.core.ContainerBase.[Tomcat-6].[localhost].[/].[dispatcherServlet]",
            "org.apache.catalina.core.ContainerBase.[Tomcat-12].[localhost].[/app].[dispatcherServlet]",
        ).forEach { loggerName ->
            test("F-004 처리되지 않은 예외를 남기는 Tomcat 로거는 막는다: $loggerName") {
                decide(loggerName) shouldBe FilterReply.DENY
            }
        }

        listOf(
            "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/]",
            "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[otherServlet]",
            "org.apache.catalina.core.StandardService",
            "com.weave.observability.TraceLoggingFilter",
        ).forEach { loggerName ->
            test("F-004 다른 로거는 그대로 둔다: $loggerName") {
                decide(loggerName) shouldBe FilterReply.NEUTRAL
            }
        }
    })
