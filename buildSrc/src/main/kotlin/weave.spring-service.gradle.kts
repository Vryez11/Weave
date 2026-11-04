// 모든 서비스 모듈이 공유하는 빌드 규칙. 각 모듈은 독립 실행 가능한 Spring Boot 앱이다.
plugins {
    id("weave.kotlin-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":libs:observability"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
}
