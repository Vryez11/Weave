// 서비스와 라이브러리가 공유하는 Kotlin 빌드 규칙.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "com.weave"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val kotestVersion = "6.2.6"
val mockkVersion = "1.14.11"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// 테스트는 Kotest 태그로 나눈다 (conventions/harness.md 2-3).
// - test: 단위 테스트. Integration 태그가 없는 것. Stop 훅이 돌린다
// - integrationTest: Integration 태그가 붙은 것. Spring 컨텍스트, WireMock, Testcontainers
// - check(build)는 둘 다 돌린다. CI가 쓴다
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 기능 ID로 거르기: ./gradlew test -Pkotest.filter.tests='*F-003*'
    // Kotest는 Gradle --tests로 중첩 테스트 이름을 거르기 어려워 Kotest 필터를 그대로 넘긴다
    listOf("kotest.filter.specs", "kotest.filter.tests").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

tasks.named<Test>("test") {
    systemProperty("kotest.tags", "!Integration")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Integration 태그가 붙은 테스트를 돌린다."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("kotest.tags", "Integration")
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(integrationTest)
}
