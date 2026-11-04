package com.weave.feed

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/**
 * HTTP 서비스 클라이언트 프록시가 실제로 쓰는 커넥션 풀을 꺼낸다 (design 결정 3, 8).
 *
 * 프록시 → RestClient → 요청 팩토리 → HttpClient → 커넥션 매니저로 이어지는 내부 필드를 이름에 기대지 않고
 * 객체 그래프를 따라가며 찾는다. 그룹마다 풀이 따로인지, 풀 크기와 타임아웃 기본값이 얼마인지 볼 때 쓴다.
 */
object HttpClientPools {
    private const val MAX_DEPTH = 20

    /** [client]가 닿는 커넥션 매니저. 정확히 하나여야 한다. */
    fun connectionManagerOf(client: Any): PoolingHttpClientConnectionManager {
        val found = findAll(client, PoolingHttpClientConnectionManager::class.java)
        return found.singleOrNull()
            ?: error("${client.javaClass.name}에서 커넥션 매니저를 하나만 찾아야 하는데 ${found.size}개를 찾았다")
    }

    /**
     * [root]에서 닿는 [type]의 객체를 모두 찾는다. 찾은 객체 안쪽은 더 들어가지 않는다.
     * 타임아웃 기본값(`RequestConfig`, `ConnectionConfig`, `SocketConfig`)을 볼 때도 쓴다.
     */
    fun <T : Any> findAll(
        root: Any,
        type: Class<T>,
    ): List<T> {
        val found = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var current = listOf(root)
        repeat(MAX_DEPTH) {
            current =
                current.flatMap { node ->
                    if (!visited.add(node)) return@flatMap emptyList()
                    if (type.isInstance(node)) {
                        found.add(type.cast(node))
                        return@flatMap emptyList()
                    }
                    children(node)
                }
            if (current.isEmpty()) return found.toList()
        }
        return found.toList()
    }

    private fun children(node: Any): List<Any> =
        when {
            Proxy.isProxyClass(node.javaClass) -> listOf(Proxy.getInvocationHandler(node))
            node is Map<*, *> -> (node.keys + node.values).filterNotNull()
            node is Iterable<*> -> node.filterNotNull()
            node is Array<*> -> node.filterNotNull()
            isJdkType(node.javaClass) -> emptyList()
            else -> fieldValues(node)
        }

    private fun fieldValues(node: Any): List<Any> =
        generateSequence(node.javaClass) { it.superclass }
            .takeWhile { !isJdkType(it) }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive && it.trySetAccessible() }
            .mapNotNull { it.get(node) }
            .toList()

    private fun isJdkType(type: Class<*>): Boolean =
        type.name.startsWith("java.") || type.name.startsWith("jdk.") || type.name.startsWith("sun.")
}
