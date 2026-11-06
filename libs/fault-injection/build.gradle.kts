// 하위 서비스 5개의 앱 수준 장애 주입(add-fault-experiments design 결정 2).
// 컨벤션 플러그인에 넣지 않고 하위 서비스의 build.gradle.kts에서만 의존성으로 더해, feed-service에는 들어가지 않는다.
plugins {
    id("weave.spring-library")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // 테스트 소스의 작은 웹 애플리케이션(GET /work)을 실제 Tomcat으로 띄운다
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("io.kotest:kotest-extensions-spring:6.2.6")
}
