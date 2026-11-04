// 서비스 6개를 실제 프로세스(bootJar)로 띄워 피드를 끝까지 받는 종단 간 테스트.
// 서비스 코드에 기대지 않고, 각 서비스가 내놓는 bootJar만 받는다.
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("weave.kotlin-conventions")
}

val services =
    listOf(
        "feed-service",
        "post-service",
        "user-service",
        "like-service",
        "recommendation-service",
        "ad-service",
    )

// 서비스들의 bootJar를 받는 구성 (weave.spring-service의 bootJarElements)
val serviceJars =
    configurations.create("serviceJars") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }

dependencies {
    services.forEach { serviceJars(project(path = ":$it", configuration = "bootJarElements")) }
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
}

// jar 경로를 weave.e2e.jar.<서비스 모듈 이름> 시스템 속성으로 넘긴다. 예) weave.e2e.jar.feed-service
tasks.named<Test>("integrationTest") {
    val jars = serviceJars.incoming.artifacts.resolvedArtifacts
    inputs.files(serviceJars).withPropertyName("serviceJars")
    jvmArgumentProviders +=
        CommandLineArgumentProvider {
            jars.get().map { artifact ->
                val service = (artifact.id.componentIdentifier as ProjectComponentIdentifier).projectName
                "-Dweave.e2e.jar.$service=${artifact.file.absolutePath}"
            }
        }
}
