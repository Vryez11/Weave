// 서비스들이 가져다 쓰는 공통 라이브러리 규칙. 실행 jar 없이 일반 jar로 배포된다.
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("weave.kotlin-conventions")
    `java-library`
}

dependencies {
    // Spring Boot 플러그인과 같은 버전의 BOM을 써서 서비스와 의존성 버전을 맞춘다.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
}
