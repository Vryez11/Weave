package com.weave.like

import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext

/**
 * like-service를 로컬에서 띄우는 진입점. `./gradlew :like-service:bootTestRun`이 이것을 실행한다.
 *
 * - `MySqlTestConfiguration`을 함께 붙여 Testcontainers MySQL(`mysql:9.7`)을 띄우므로 MySQL을 따로 설치하지 않아도 된다. Docker가 필요하다.
 * - 테스트에서도 [start]로 같은 방식으로 띄운다. 포트는 명령줄 인자(`--server.port=0`)로 넘긴다.
 * - `main`은 `LikeServiceApplication.kt`의 최상위 `main`과 이름이 겹치지 않게 object 안에 둔다.
 */
object TestLikeServiceApplication {
    @JvmStatic
    fun main(args: Array<String>) {
        start(*args)
    }

    fun start(vararg args: String): ConfigurableApplicationContext =
        SpringApplication
            .from { mainArgs -> com.weave.like.main(mainArgs) }
            .with(MySqlTestConfiguration::class.java)
            .run(*args)
            .applicationContext
}
