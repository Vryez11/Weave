import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("weave.spring-library")
    // 서비스 테스트가 함께 쓰는 로그 검사 도구(LogCapture). weave.spring-service가 서비스 테스트에 넣는다
    `java-test-fixtures`
}

dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("io.kotest:kotest-extensions-spring:6.2.6")
    testFixturesImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testFixturesImplementation("tools.jackson.core:jackson-databind")
}
