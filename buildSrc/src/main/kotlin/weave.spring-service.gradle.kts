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
    testImplementation(testFixtures(project(":libs:observability")))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("io.kotest:kotest-extensions-spring:6.2.6")
    testImplementation("com.ninja-squad:springmockk:5.0.1")
}

// 실행 jar(bootJar)를 다른 모듈이 받아 쓸 수 있게 내놓는다. e2e 모듈이 서비스를 프로세스로 띄울 때 쓴다.
val bootJarElements =
    configurations.create("bootJarElements") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }

artifacts {
    add(bootJarElements.name, tasks.named("bootJar"))
}
