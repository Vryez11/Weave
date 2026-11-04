plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.3.21")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.2")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
