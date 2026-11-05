import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("weave.spring-library")
    // 서비스 테스트가 함께 쓰는 로그 검사 도구(LogCapture). weave.spring-service가 서비스 테스트에 넣는다
    `java-test-fixtures`
}

dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // logback-spring.xml의 JSON 인코더(design 결정 1). Boot BOM에 없어 버전을 적는다
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    // 트레이싱(design 결정 3). 메트릭 OTLP 레지스트리까지 가져오는 spring-boot-starter-opentelemetry 대신 모듈을 골라 넣는다
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:context-propagation")
    // X-Trace-Id·요청 완료 로그 필터(design 결정 5). 서비스가 이미 가지므로 컴파일에만 쓴다
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("io.kotest:kotest-extensions-spring:6.2.6")
    testFixturesImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testFixturesImplementation("tools.jackson.core:jackson-databind")
}
