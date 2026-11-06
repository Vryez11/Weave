package com.weave.feed.client

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Boot 그룹 설정(`spring.http.serviceclient.<그룹>.*`)에 없는 HttpClient 설정 (add-call-isolation design 결정 3, 5).
 *
 * 연결·응답 타임아웃은 Boot 그룹 설정의 `connect-timeout`, `read-timeout`으로 준다. 값이 없으면 HttpClient 기본값을 쓴다.
 */
@ConfigurationProperties("weave.http-client")
data class HttpClientProperties(
    /** 모든 그룹의 라우트당·전체 최대 커넥션. */
    val maxConnectionsPerRoute: Int? = null,
    /** 모든 그룹에서 커넥션 풀의 연결을 기다리는 상한. */
    val connectionRequestTimeout: Duration? = null,
    /** 그룹 이름(`post` 등)마다의 덮어쓰기. */
    val groups: Map<String, Group> = emptyMap(),
) {
    data class Group(
        /** 이 그룹의 라우트당·전체 최대 커넥션. [maxConnectionsPerRoute]보다 앞선다. */
        val maxConnections: Int? = null,
    )

    /** [group]이 덮어쓴 최대 커넥션. 없으면 null이고, 그때는 모든 그룹의 값([maxConnectionsPerRoute])이 그대로 쓰인다. */
    fun maxConnectionsOverrideOf(group: String): Int? = groups[group]?.maxConnections
}
