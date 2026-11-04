package com.weave.user

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class UserController(
    private val userDirectory: UserDirectory,
) {
    @GetMapping("/users")
    fun profiles(
        @RequestParam ids: List<Long>,
    ): UsersResponse {
        if (ids.size !in IDS_RANGE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ids는 ${IDS_RANGE.first}~${IDS_RANGE.last}개만 받는다: ${ids.size}개")
        }
        return UsersResponse(userDirectory.findAll(ids))
    }

    data class UsersResponse(
        val users: List<UserProfile>,
    )

    companion object {
        private val IDS_RANGE = 1..100
    }
}
