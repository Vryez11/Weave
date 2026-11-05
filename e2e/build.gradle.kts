// 서비스 6개를 실제 프로세스(bootJar)로 띄워 피드를 끝까지 받는 종단 간 테스트.
// 서비스 코드에 기대지 않고, 각 서비스가 내놓는 bootJar만 받는다.
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import java.util.UUID

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

// ---------- Compose 테스트 (add-trace-logging design 결정 10) ----------
// 루트 compose.yaml을 프로젝트 이름 weave-compose-test로 띄우고 Compose 태그 테스트만 돌린 뒤,
// 성공·실패와 관계없이 내린다. 손으로 띄운 로컬 환경(프로젝트 weave)과 컨테이너·볼륨이 섞이지 않는다.
// 호스트 포트는 compose.yaml에 고정이라, 로컬 환경이 떠 있으면 먼저 내려야 한다.
val composeProject = "weave-compose-test"
val composeFile = rootProject.layout.projectDirectory.file("compose.yaml")
val composeEnvFile = layout.buildDirectory.file("compose-test.env")

fun dockerCompose(vararg args: String): List<String> =
    listOf(
        "docker",
        "compose",
        "-p",
        composeProject,
        "-f",
        composeFile.asFile.absolutePath,
        "--env-file",
        composeEnvFile.get().asFile.absolutePath,
    ) + args

// MySQL 계정을 실행할 때마다 임의 값으로 만든다. CI에는 .env가 없고, 비밀 값을 커밋하지 않는다
val composeTestEnv =
    tasks.register("composeTestEnv") {
        description = "Compose 테스트용 MySQL 계정을 임의 값으로 만들어 build/compose-test.env에 쓴다."
        val envFile = composeEnvFile
        outputs.file(envFile)
        outputs.upToDateWhen { false }
        doLast {
            fun random() = UUID.randomUUID().toString().replace("-", "")
            envFile.get().asFile.writeText(
                """
                LIKE_DB_USERNAME=weave_${random().take(8)}
                LIKE_DB_PASSWORD=${random()}
                MYSQL_ROOT_PASSWORD=${random()}

                """.trimIndent(),
            )
        }
    }

val composeTestDown =
    tasks.register<Exec>("composeTestDown") {
        description = "Compose 테스트 환경을 볼륨까지 지운다."
        commandLine(dockerCompose("down", "-v"))
    }

val composeTestUp =
    tasks.register<Exec>("composeTestUp") {
        description = "Compose 테스트 환경을 띄우고 헬스 체크가 통과할 때까지 기다린다."
        dependsOn(composeTestEnv)
        // compose.yaml이 각 서비스의 build/libs를 빌드 컨텍스트로 쓰므로 bootJar가 먼저 있어야 한다
        inputs.files(serviceJars).withPropertyName("serviceJars")
        commandLine(dockerCompose("up", "-d", "--build", "--wait"))
        // 띄우다 실패해도(헬스 체크 실패 등) 반쯤 뜬 컨테이너를 내린다
        finalizedBy(composeTestDown)
    }

tasks.register<Test>("composeTest") {
    description = "Docker Compose 환경을 띄워 Compose 태그가 붙은 테스트를 돌린다."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("kotest.tags", "Compose")
    // 주소는 compose.yaml이 호스트에 연 포트다(TraceLookupComposeScenarioTest KDoc)
    systemProperty("weave.compose.feed-url", "http://localhost:8080")
    systemProperty("weave.compose.loki-url", "http://localhost:3100")
    systemProperty("weave.compose.tempo-url", "http://localhost:3200")
    // 테스트 대상은 매번 새로 띄운 환경이라 입력이 같아도 다시 돌린다
    outputs.upToDateWhen { false }
    dependsOn(composeTestUp)
    finalizedBy(composeTestDown)
}
