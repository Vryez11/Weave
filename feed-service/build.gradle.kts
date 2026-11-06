plugins {
    id("weave.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    // 하위 서비스 호출 격리 (add-call-isolation design 결정 1, 2). Boot 4.0.0으로 빌드된 판이라 4.1.2 호환은 ResilienceAutoConfigurationTest가 본다
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    // 벌크헤드 스레드의 traceId 전파(TraceContextPropagator, design 결정 8). libs/observability가 런타임에 이미 넣고 BOM이 버전을 정한다
    implementation("io.micrometer:context-propagation")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
}
