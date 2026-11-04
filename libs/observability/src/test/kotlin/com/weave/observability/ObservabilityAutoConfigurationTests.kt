package com.weave.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates

class ObservabilityAutoConfigurationTests :
    FunSpec({
        test("자동 설정 목록에 등록돼 있다") {
            val classLoader = ObservabilityAutoConfiguration::class.java.classLoader
            val candidates = ImportCandidates.load(AutoConfiguration::class.java, classLoader)

            candidates.candidates shouldContain ObservabilityAutoConfiguration::class.java.name
        }
    })
