package com.weave.feed.client.user

import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange

/** user-service의 `user-profile` API. */
@HttpExchange
interface UserClient {
    @GetExchange("/users")
    fun getUsers(
        @RequestParam ids: List<Long>,
    ): UsersResponse
}

data class UsersResponse(
    val users: List<UserResponse>,
)

data class UserResponse(
    val id: Long,
    val nickname: String,
    val profileImageUrl: String,
)
