plugins {
    id("weave.spring-service")
}

dependencies {
    // experiment 프로필에서만 켜지는 앱 수준 장애 주입(add-fault-experiments design 결정 2)
    implementation(project(":libs:fault-injection"))
}
