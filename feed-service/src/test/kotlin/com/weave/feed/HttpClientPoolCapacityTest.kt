package com.weave.feed

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest

/** design 결정 8: `application.yml`의 용량 설정으로 그룹마다 라우트당·전체 최대 커넥션이 20이다 (F-006). */
@Tags("Integration")
@SpringBootTest
class HttpClientPoolCapacityTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-006 하위 서비스 그룹 다섯의 커넥션 풀이 라우트당·전체 20이다") {
            connectionManagersByDependency().forEach { (dependency, manager) ->
                (dependency to manager.defaultMaxPerRoute) shouldBe (dependency to 20)
                (dependency to manager.maxTotal) shouldBe (dependency to 20)
            }
        }
    })
