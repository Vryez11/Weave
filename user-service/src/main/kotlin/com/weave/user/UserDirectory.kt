package com.weave.user

import org.springframework.stereotype.Component

/**
 * 시작할 때 design 결정 1의 식으로 만든 고정 사용자를 메모리에 들고 있는다.
 * 같은 식으로 만들므로 몇 번을 다시 띄워도 프로필이 같다.
 */
@Component
class UserDirectory {
    private val byId: Map<Long, UserProfile> =
        (1L..USER_COUNT).associateWith(::fixedProfile)

    /** [ids] 중 있는 사용자의 프로필. 없는 ID는 빠지고, 같은 ID는 한 번만 온다. 요청한 순서를 따른다. */
    fun findAll(ids: Collection<Long>): List<UserProfile> = ids.distinct().mapNotNull(byId::get)

    companion object {
        const val USER_COUNT = 50L

        fun fixedProfile(id: Long): UserProfile =
            UserProfile(
                id = id,
                nickname = "사용자$id",
                profileImageUrl = "https://cdn.example.com/users/$id.png",
            )
    }
}
