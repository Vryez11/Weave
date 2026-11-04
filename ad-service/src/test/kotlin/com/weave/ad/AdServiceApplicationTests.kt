package com.weave.ad

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest

@Tags("Integration")
@SpringBootTest
class AdServiceApplicationTests :
    FunSpec({
        extensions(SpringExtension())

        test("애플리케이션 컨텍스트가 뜬다") {}
    })
