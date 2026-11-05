package com.weave.observability

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

/**
 * Tomcat이 처리되지 않은 예외를 남기는 `dispatcherServlet` 로거의 줄을 막는다(design 결정 5).
 *
 * 그 줄은 서버 스팬의 scope가 닫힌 뒤에 쓰여 `traceId`가 없다. 같은 예외는 [TraceLoggingFilter]가 scope 안에서
 * `http_request` ERROR 줄에 담아 남기므로 중복이다. 로거 이름은
 * `org.apache.catalina.core.ContainerBase.[<엔진>].[<호스트>].[<경로>].[dispatcherServlet]`이고 엔진 이름이
 * `Tomcat` 또는 `Tomcat-N`(테스트에서 서버를 여러 번 띄울 때)이라 logback `<logger>`로는 가리킬 수 없어 이름 형태로 거른다.
 */
class TomcatServletExceptionLogFilter : Filter<ILoggingEvent>() {
    // 모든 로그 호출에서 돌므로 접두사로 먼저 거른다
    override fun decide(event: ILoggingEvent): FilterReply {
        val name = event.loggerName
        val denied = name.startsWith(CONTAINER_LOGGER_PREFIX) && DISPATCHER_SERVLET_LOGGER.matches(name)
        return if (denied) FilterReply.DENY else FilterReply.NEUTRAL
    }

    companion object {
        private const val CONTAINER_LOGGER_PREFIX = "org.apache.catalina.core.ContainerBase.["
        private val DISPATCHER_SERVLET_LOGGER =
            Regex("""org\.apache\.catalina\.core\.ContainerBase\.\[Tomcat(-\d+)?]\.\[[^\]]+]\.\[[^\]]*]\.\[dispatcherServlet]""")
    }
}
