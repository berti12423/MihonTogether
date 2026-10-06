package mihon.feature.social

/** The three parts of the Friends screen. */
enum class FriendsTab { FRIENDS, CHATS, ME }

/** Where a social notification (or a deep link) leads inside Friends. Serializable: it travels in a Voyager screen. */
sealed interface FriendsRoute : java.io.Serializable {
    val tab: FriendsTab

    data class Chat(val uid: String) : FriendsRoute {
        override val tab get() = FriendsTab.CHATS
    }

    data class Group(val groupId: String) : FriendsRoute {
        override val tab get() = FriendsTab.CHATS
    }

    /** A room card; [join] runs the join flow right away (the notification's Join action). */
    data class Room(val roomId: String, val join: Boolean = false) : FriendsRoute {
        override val tab get() = FriendsTab.FRIENDS
    }

    data class Profile(val uid: String) : FriendsRoute {
        override val tab get() = FriendsTab.FRIENDS
    }

    /** Incoming friend requests (on the Friends tab). */
    data object Requests : FriendsRoute {
        private fun readResolve(): Any = Requests
        override val tab get() = FriendsTab.FRIENDS
    }

    data object Home : FriendsRoute {
        private fun readResolve(): Any = Home
        override val tab get() = FriendsTab.FRIENDS
    }

    companion object {
        const val EXTRA_SCREEN = "mihon.together.extra.SCREEN"
        const val EXTRA_ID = "mihon.together.extra.ID"
        private const val CHAT = "chat"
        private const val GROUP = "group"
        private const val ROOM = "room"
        private const val JOIN = "join"
        private const val PROFILE = "profile"
        private const val REQUESTS = "requests"

        private val UID = Regex("[A-Za-z0-9_-]{1,128}")
        private val HEX_ID = Regex("[0-9a-f]{64}")

        fun validUid(value: String?): String? = value?.takeIf { it.matches(UID) }

        fun validId(value: String?): String? = value?.takeIf { it.matches(HEX_ID) }

        /** Where tapping [push] goes; a missing or malformed subject falls back to the Friends tab. */
        fun forPush(push: SocialPushMessage): FriendsRoute = when (push.kind) {
            SocialPushMessage.MESSAGE, SocialPushMessage.READ_MATCH -> push.uid?.let(::Chat)
            SocialPushMessage.GROUP_MESSAGE, SocialPushMessage.GROUP_ADDED -> push.groupId?.let(::Group)
            SocialPushMessage.ROOM_INVITE -> push.roomId?.let { Room(it) }
            SocialPushMessage.FRIEND_REQUEST -> Requests
            SocialPushMessage.FRIEND_ACCEPTED -> push.uid?.let(::Profile)
            else -> null
        } ?: Home

        /** The intent extras (screen, id) for [route]. */
        fun extras(route: FriendsRoute): Pair<String, String?> = when (route) {
            is Chat -> CHAT to route.uid
            is Group -> GROUP to route.groupId
            is Room -> (if (route.join) JOIN else ROOM) to route.roomId
            is Profile -> PROFILE to route.uid
            Requests -> REQUESTS to null
            Home -> "" to null
        }

        /** Reads intent extras back; anything malformed means plain Friends. */
        fun parse(screen: String?, id: String?): FriendsRoute = when (screen) {
            CHAT -> validUid(id)?.let(::Chat)
            GROUP -> validId(id)?.let(::Group)
            ROOM -> validId(id)?.let { Room(it) }
            JOIN -> validId(id)?.let { Room(it, join = true) }
            PROFILE -> validUid(id)?.let(::Profile)
            REQUESTS -> Requests
            else -> null
        } ?: Home
    }
}

/** Tab badges: unread messages in conversations with current friends, plus group chats. */
object FriendsTabs {
    fun unreadChats(chats: List<ChatSummary>, groups: List<GroupSummary>, friendUids: Set<String>): Int =
        chats.filter { it.otherUid in friendUids }.sumOf { it.unread.coerceAtLeast(0) } +
            groups.sumOf { it.unread.coerceAtLeast(0) }

    /** Things waiting for me on the Friends tab: room invites, read-along requests and friend requests. */
    fun pendingFriends(state: FriendsState): Int {
        val me = state.account?.uid ?: return 0
        return state.rooms.count { it.isInvited(me) } + state.joinRequests.size + state.incoming.size
    }
}
