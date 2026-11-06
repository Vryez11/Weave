package com.weave.faultinjection.testapp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** 장애 주입을 실제 웹 요청으로 확인하려는 작은 애플리케이션. 하위 서비스가 받는 것과 같은 자동 설정을 받는다. */
@SpringBootApplication
class FaultInjectionTestApplication

@RestController
class WorkController {
    /** 하위 서비스의 업무 엔드포인트 역할. 장애 주입 필터가 지연과 에러를 넣는 대상이다. */
    @GetMapping("/work")
    fun work(): String = WORK_RESPONSE

    companion object {
        const val WORK_RESPONSE = "ok"
    }
}
