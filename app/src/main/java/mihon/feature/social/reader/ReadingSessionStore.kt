package mihon.feature.social.reader

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * "Read together" started outside the reader (a friend's profile, a title, a group): the reader opens the title and,
 * once a supported chapter of [contentKey] has loaded, starts a room with the first friend and invites the rest.
 */
data class PendingRoomInvite(val contentKey: String, val friendUids: List<String>, val createdAt: Long) {
    fun expired(now: Long) = now - createdAt !in 0..MAX_AGE_MS

    /** Who to invite: my accepted friends among [friendUids], in order, without duplicates, at most five. */
    fun targets(friends: Set<String>, me: String?): List<String> =
        friendUids.distinct().filter { it in friends && it != me }.take(MAX_INVITES)

    companion object {
        /** Opening the reader and loading a chapter takes seconds; an old intent must not start a room later. */
        const val MAX_AGE_MS = 3 * 60_000L

        /** A room holds the host plus five friends. */
        const val MAX_INVITES = 5
    }
}

/** An in-memory handoff between Friends and the reader; no room turns are persisted. */
@Inject
@SingleIn(AppScope::class)
class ReadingSessionStore {
    private val selected = MutableStateFlow<String?>(null)
    val selectedRoomId = selected.asStateFlow()
    private val pending = MutableStateFlow<PendingRoomInvite?>(null)
    val pendingInvite = pending.asStateFlow()

    // One host device/session fence survives activity recreation, but not process death.
    val writerSessionId: String = UUID.randomUUID().toString()

    fun select(roomId: String) {
        selected.value = roomId
    }

    fun clear(roomId: String? = null) {
        if (roomId == null) pending.value = null
        if (roomId == null || selected.value == roomId) selected.value = null
    }

    fun setPending(invite: PendingRoomInvite) {
        pending.value = invite
    }

    /** Takes [invite] if it's still the pending one; only one reader can start the room. */
    fun takePending(invite: PendingRoomInvite): Boolean = pending.compareAndSet(invite, null)
}
