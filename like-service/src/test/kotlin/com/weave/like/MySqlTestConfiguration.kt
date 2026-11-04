package com.weave.like

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.testcontainers.mysql.MySQLContainer

/**
 * like-service 테스트가 쓰는 MySQL 컨테이너.
 *
 * - `@TestConfiguration`이 아닌 `@Configuration`이라 `@SpringBootTest`의 컴포넌트 스캔에 잡혀, 따로 `@Import`하지 않아도
 *   모든 컨텍스트 테스트가 이 DB를 쓴다. 로컬 실행에서는 `SpringApplication.from(...).with(MySqlTestConfiguration::class)`로 붙인다.
 * - 컨테이너는 JVM 하나에 하나만 띄우고, JVM이 끝날 때 Testcontainers가 치운다. 애플리케이션 컨텍스트를 두 번 띄워도(영속 시나리오)
 *   같은 DB를 보도록 컨테이너를 Bean으로 내놓지 않는다. Bean으로 내놓으면(`@ServiceConnection`) Boot가 컨텍스트를 닫을 때
 *   컨테이너도 멈추고, 다음 컨텍스트가 빈 DB로 새 컨테이너를 띄운다. 대신 접속 정보만 `JdbcConnectionDetails`로 내놓는다.
 * - 테이블 비우기는 각 테스트가 맡는다.
 */
@Configuration(proxyBeanMethods = false)
class MySqlTestConfiguration {
    @Bean
    fun mysqlConnectionDetails(): JdbcConnectionDetails =
        object : JdbcConnectionDetails {
            override fun getJdbcUrl(): String = container.jdbcUrl

            override fun getUsername(): String = container.username

            override fun getPassword(): String = container.password
        }

    companion object {
        val container: MySQLContainer by lazy { MySQLContainer("mysql:9.7").apply { start() } }
    }
}
