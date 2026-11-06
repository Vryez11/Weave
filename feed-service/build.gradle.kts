plugins {
    id("weave.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    // 하위 서비스 호출 격리 (add-call-isolation design 결정 1, 2). Boot 4.0.0으로 빌드된 판이라 4.1.2 호환은 ResilienceAutoConfigurationTest가 본다
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
}
