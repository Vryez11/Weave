package com.weave.observability.testapp

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** 라이브러리의 로그 설정을 실제 웹 요청으로 확인하려는 작은 애플리케이션. 서비스가 쓰는 것과 같은 자동 설정을 받는다. */
@SpringBootApplication
class LoggingTestApplication

@RestController
class LoggingTestController {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/log")
    fun log(): String {
        log.info(REQUEST_LOG_MESSAGE)
        return "ok"
    }

    @GetMapping("/log-exception")
    fun logException(): String {
        log.error(EXCEPTION_LOG_MESSAGE, IllegalStateException("로그 테스트용 예외"))
        return "ok"
    }

    companion object {
        const val REQUEST_LOG_MESSAGE = "요청을 처리하며 쓴 로그"
        const val EXCEPTION_LOG_MESSAGE = "예외를 담아 쓴 로그"
    }
}
