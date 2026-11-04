package com.weave.feed

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/** design 결정 8: `application.yml`의 용량 설정으로 공유 스레드 풀의 코어가 100이고, 대기열은 기본값(무제한)이다 (F-006). */
@Tags("Integration")
@SpringBootTest
class TaskExecutorCapacityTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-006 applicationTaskExecutor의 코어 스레드가 100이고 대기열은 무제한이다") {
            val executor =
                testContextManager()
                    .testContext.applicationContext
                    .getBean("applicationTaskExecutor", ThreadPoolTaskExecutor::class.java)

            executor.corePoolSize shouldBe 100
            executor.queueCapacity shouldBe Int.MAX_VALUE
            executor.threadPoolExecutor.queue.remainingCapacity() shouldBe Int.MAX_VALUE
        }
    })
