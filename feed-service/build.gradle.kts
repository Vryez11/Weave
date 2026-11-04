plugins {
    id("weave.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
}
