package mihon.feature.social

import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.util.system.cancelNotification
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The conversation on screen while the app is in the foreground (a private chat, a group chat or the reading room), so
 * its pushes don't pop up over it. Other conversations still notify. Screens set it with [ShownOnScreen].
 */
object SocialForeground {
    sealed interface Target {
        data class Chat(val uid: String) : Target
        data class Group(val groupId: String) : Target
        data class Room(val roomId: String) : Target
    }

    // Read from the FCM worker thread, written on the main thread.
    @Volatile
    private var current: Target? = null

    val onScreen: Target? get() = current

    @Synchronized
    fun show(target: Target) {
        current = target
    }

    /** Clears [target] only: the next screen may already have set its own. */
    @Synchronized
    fun hide(target: Target) {
        if (current == target) current = null
    }

    /** The conversation a push is about; null for kinds that aren't (friend and join requests, accepted friends). */
    fun targetOf(push: SocialPushMessage): Target? = when (push.kind) {
        SocialPushMessage.MESSAGE, SocialPushMessage.READ_MATCH -> push.uid?.let(Target::Chat)
        SocialPushMessage.GROUP_MESSAGE, SocialPushMessage.GROUP_ADDED -> push.groupId?.let(Target::Group)
        SocialPushMessage.ROOM_INVITE -> push.roomId?.let(Target::Room)
        else -> null
    }

    /** Skip only a push for the conversation that's on screen while the app is in the foreground. */
    fun shouldNotify(push: SocialPushMessage, onScreen: Target?, foreground: Boolean): Boolean =
        !foreground || onScreen == null || targetOf(push) != onScreen

    /** Notification extra naming the conversation, for clearing ids that can't be recomputed (read matches). */
    const val EXTRA_SUBJECT = "mihon.together.extra.SUBJECT"

    fun subject(target: Target): String = when (target) {
        is Target.Chat -> "chat:${target.uid}"
        is Target.Group -> "group:${target.groupId}"
        is Target.Room -> "room:${target.roomId}"
    }

    /** The notification ids [SocialPushMessage.notificationId] gives this conversation's pushes (title-free ones). */
    fun notificationIds(target: Target): List<Int> = when (target) {
        is Target.Chat -> listOf(SocialPushMessage(SocialPushMessage.MESSAGE, "", uid = target.uid))
        is Target.Group -> listOf(SocialPushMessage.GROUP_MESSAGE, SocialPushMessage.GROUP_ADDED)
            .map { SocialPushMessage(it, "", groupId = target.groupId) }
        is Target.Room -> listOf(SocialPushMessage(SocialPushMessage.ROOM_INVITE, "", roomId = target.roomId))
    }.map { it.notificationId() }

    /** Opening a conversation clears its notifications. Best effort. */
    fun cancelNotifications(context: Context, target: Target) {
        try {
            notificationIds(target).forEach(context::cancelNotification)
            val subject = subject(target)
            context.getSystemService<NotificationManager>()?.let { manager ->
                manager.activeNotifications
                    .filter { it.notification.extras.getString(EXTRA_SUBJECT) == subject }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Social notifications not cleared" }
        }
    }
}

/**
 * Marks [target] as on screen while this screen is started (cleared when it stops, e.g. the app goes to the
 * background, or leaves composition) and clears its notifications each time it comes back. Null marks nothing.
 */
@Composable
fun ShownOnScreen(target: SocialForeground.Target?) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(target, lifecycle) {
        if (target == null) return@DisposableEffect onDispose {}
        // Added observers are brought up to the current state, so a started screen gets ON_START right away.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    SocialForeground.show(target)
                    SocialForeground.cancelNotifications(context, target)
                }
                Lifecycle.Event.ON_STOP -> SocialForeground.hide(target)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            SocialForeground.hide(target)
        }
    }
}
