plugins {
    id("weave.spring-service")
    kotlin("plugin.jpa")
}

dependencies {
    // experiment 프로필에서만 켜지는 앱 수준 장애 주입(add-fault-experiments design 결정 2)
    implementation(project(":libs:fault-injection"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.testcontainers:testcontainers-mysql")
}

// JPA 엔티티는 프록시로 감싸지므로 final이면 안 된다
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
