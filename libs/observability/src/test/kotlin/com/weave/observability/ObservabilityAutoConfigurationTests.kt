package com.weave.observability

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import kotlin.test.assertContains

class ObservabilityAutoConfigurationTests {

    @Test
    fun `자동 설정 목록에 등록돼 있다`() {
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader)

        assertContains(candidates.candidates, ObservabilityAutoConfiguration::class.java.name)
    }
}
