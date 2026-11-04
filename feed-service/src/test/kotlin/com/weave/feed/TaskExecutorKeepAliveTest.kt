package com.weave.feed

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.TimeUnit

/** architecture 4-2: 공유 스레드 풀의 코어 스레드는 일이 없으면 keep-alive 60초 뒤 내려간다(Boot 기본값) (F-006). */
@Tags("Integration")
@SpringBootTest
class TaskExecutorKeepAliveTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-006 applicationTaskExecutor는 코어 스레드 시간 초과를 허용하고 keep-alive는 60초다") {
            val executor =
                testContextManager()
                    .testContext.applicationContext
                    .getBean("applicationTaskExecutor", ThreadPoolTaskExecutor::class.java)

            executor.keepAliveSeconds shouldBe 60
            executor.threadPoolExecutor.allowsCoreThreadTimeOut() shouldBe true
            executor.threadPoolExecutor.getKeepAliveTime(TimeUnit.SECONDS) shouldBe 60L
        }
    })
