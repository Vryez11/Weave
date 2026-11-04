package com.weave.like

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.testcontainers.mysql.MySQLContainer

/**
 * like-service 테스트가 쓰는 MySQL 컨테이너.
 *
 * - `@TestConfiguration`이 아닌 `@Configuration`이라 `@SpringBootTest`의 컴포넌트 스캔에 잡혀, 따로 `@Import`하지 않아도
 *   모든 컨텍스트 테스트가 이 DB를 쓴다. 로컬 실행에서는 `SpringApplication.from(...).with(MySqlTestConfiguration::class)`로 붙인다.
 * - 컨테이너는 JVM 하나에 하나만 띄운다. 애플리케이션 컨텍스트를 두 번 띄워도(영속 시나리오) 같은 DB를 보도록,
 *   컨텍스트가 닫혀도 컨테이너를 멈추지 않는다(`destroyMethod = ""`). 남은 컨테이너는 Testcontainers가 JVM이 끝날 때 치운다.
 * - 테이블 비우기는 각 테스트가 맡는다.
 */
@Configuration(proxyBeanMethods = false)
class MySqlTestConfiguration {
    @Bean(destroyMethod = "")
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer = container

    companion object {
        val container: MySQLContainer by lazy { MySQLContainer("mysql:9.7") }
    }
}
