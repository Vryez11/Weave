// 하위 서비스 5개의 앱 수준 장애 주입(add-fault-experiments design 결정 2).
// 컨벤션 플러그인에 넣지 않고 하위 서비스의 build.gradle.kts에서만 의존성으로 더해, feed-service에는 들어가지 않는다.
plugins {
    id("weave.spring-library")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // 장애 주입 필터와 faults 엔드포인트. 서비스가 weave.spring-service로 이미 가지므로 컴파일에만 쓴다
    compileOnly("org.springframework:spring-web")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.boot:spring-boot-actuator")
    // 테스트 소스의 작은 웹 애플리케이션(GET /work)을 실제 Tomcat으로 띄운다
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // 시나리오 테스트가 부르는 /actuator/faults·health·prometheus. 서비스가 weave.spring-service로 이미 가진 것과 같다
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("io.kotest:kotest-extensions-spring:6.2.6")
    // 넣은 에러가 traceId가 붙은 요청 완료 로그에 남는지 본다. 서비스와 같은 필터 순서로 띄우려고 관찰 설정을 함께 싣는다
    testImplementation(project(":libs:observability"))
    testImplementation(testFixtures(project(":libs:observability")))
}
