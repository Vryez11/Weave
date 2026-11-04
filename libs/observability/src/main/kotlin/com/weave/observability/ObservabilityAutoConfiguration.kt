package com.weave.observability

import org.springframework.boot.autoconfigure.AutoConfiguration

// 모든 서비스에 공통으로 적용하는 로깅·트레이싱 설정. 서비스는 의존성만 추가하면 자동으로 적용된다.
@AutoConfiguration
class ObservabilityAutoConfiguration
