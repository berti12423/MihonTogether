package mihon.feature.social

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat

/** A push the backend sent (FCM data message). Texts are built here, so they follow the phone's language. */
data class SocialPushMessage(
    val kind: String,
    val name: String,
    val handle: String? = null,
    val title: String? = null,
    val roomId: String? = null,
    /** Group chats: the group's name and ID. */
    val group: String? = null,
    val groupId: String? = null,
    /** Who it's from: the sender, requester or host (validated Firebase uid). */
    val uid: String? = null,
) {
    companion object {
        const val FRIEND_REQUEST = "friend_request"
        const val FRIEND_ACCEPTED = "friend_accepted"
        const val ROOM_INVITE = "room_invite"
        const val MESSAGE = "message"
        const val JOIN_REQUEST = "join_request"
        const val GROUP_MESSAGE = "group_message"
        const val GROUP_ADDED = "group_added"

        /** We both want to read [title]: opens the chat with [uid]. */
        const val READ_MATCH = "read_match"

        /** A friend nudged me to read (title: their streak when it is at risk); opens Friends. */
        const val NUDGE = "nudge"
        val KINDS = listOf(
            FRIEND_REQUEST,
            FRIEND_ACCEPTED,
            ROOM_INVITE,
            MESSAGE,
            JOIN_REQUEST,
            GROUP_MESSAGE,
            GROUP_ADDED,
            READ_MATCH,
            NUDGE,
        )

        /** Only known kinds with a name; everything is length-capped and validated since it comes off the network. */
        fun parse(data: Map<String, String>): SocialPushMessage? {
            val kind = data["kind"]?.takeIf { it in KINDS } ?: return null
            val name = data["name"]?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: return null
            return SocialPushMessage(
                kind = kind,
                name = name,
                handle = data["handle"]?.take(24),
                title = data["title"]?.take(200)?.takeIf { it.isNotBlank() },
                roomId = FriendsRoute.validId(data["roomId"]),
                group = data["group"]?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
                groupId = FriendsRoute.validId(data["groupId"]),
                uid = FriendsRoute.validUid(data["uid"]),
            )
        }
    }

    /** Stable per subject, so a repeated invite or the next message replaces the old notification. */
    fun notificationId(): Int {
        val subject = groupId ?: roomId ?: uid?.let { if (kind == READ_MATCH) "$it|$title" else it } ?: handle ?: name
        return "$kind|$subject".hashCode()
    }
}

/** Which pushes to show; device settings, all on by default (the backend sends, the phone decides). */
@Inject
@SingleIn(AppScope::class)
class SocialNotificationPreferences(private val store: PreferenceStore) {
    fun enabled(kind: String): Preference<Boolean> = store.getBoolean("social_notify_$kind", true)

    /** Lock screen shows a short note instead of the text (VISIBILITY_PRIVATE with a public version). */
    fun hideOnLockScreen(): Preference<Boolean> = store.getBoolean("social_notify_hide_lock_screen", false)
}

/**
 * Registers this device's FCM token with the backend (production only: FCM isn't emulated, and emulator builds stay
 * isolated from Google services). Uses the social Firebase app, so the token belongs to the social project.
 */
@Inject
@SingleIn(AppScope::class)
class SocialPushRegistration(
    private val firebase: SocialFirebase,
    private val repository: SocialRepository,
    private val store: PreferenceStore,
) {
    private fun registered(uid: String) = store.getString(
        Preference.appStateKey("social_push_${SocialIdentity.key(uid)}"),
        "",
    )

    /** Sends the token when it's new or a week old (tokens can rotate quietly). Best effort. */
    suspend fun sync(uid: String) {
        if (BuildConfig.SOCIAL_ENVIRONMENT != "production") return
        try {
            val token = firebase.messaging.token.await()
            val (saved, at) = registered(uid).get().split('|').let {
                it.getOrNull(0) to it.getOrNull(1)?.toLongOrNull()
            }
            if (saved == token && at != null && System.currentTimeMillis() - at < RESEND_MS) return
            repository.registerDevice("register", token)
            registered(uid).set("$token|${System.currentTimeMillis()}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Social push registration failed" }
        }
    }

    /** Before signing out: stop pushes for this account on this phone. Best effort, bounded by the caller. */
    suspend fun unregister(uid: String) {
        val token = registered(uid).get().substringBefore('|').takeIf { it.isNotEmpty() } ?: return
        try {
            repository.registerDevice("unregister", token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Social push unregistration failed" }
        }
        registered(uid).delete()
    }

    private companion object {
        const val RESEND_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

/** Shows social pushes; tapping one opens the right place in Friends (see [SocialNotifications]). */
class SocialMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val push = SocialPushMessage.parse(message.data) ?: return
        if (!applicationContext.appGraph.socialNotificationPreferences.enabled(push.kind).get()) return
        // The chat (or group, or room) open on screen shows the message already; other conversations still notify.
        val process = ProcessLifecycleOwner.get().lifecycle
        val foreground = process.currentStateFlow.value.isAtLeast(Lifecycle.State.STARTED)
        if (!SocialForeground.shouldNotify(push, SocialForeground.onScreen, foreground)) return
        SocialNotifications.show(applicationContext, push)
    }

    override fun onNewToken(token: String) {
        // Registered on the next foreground start (SocialPushRegistration.sync needs the signed-in account).
    }

    companion object {
        const val ACTION_OPEN_FRIENDS = "mihon.together.OPEN_FRIENDS"
    }
}
