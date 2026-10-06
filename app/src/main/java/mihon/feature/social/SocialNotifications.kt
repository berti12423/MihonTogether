package mihon.feature.social

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.os.bundleOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Social notifications: tapping opens the right place in Friends (chat, group, room card, requests); messages offer
 * Reply, friend requests Accept and room invites Join. The lock screen can show a short note instead of the text.
 */
object SocialNotifications {
    const val ACTION_REPLY = "mihon.together.action.REPLY"
    const val ACTION_ACCEPT = "mihon.together.action.ACCEPT"

    /** Settings key for "Hide message text on the lock screen" (see [SocialNotificationPreferences]). */
    const val LOCK_SCREEN = "hide_lock_screen"
    private const val KEY_REPLY = "social_reply"
    private const val EXTRA_KIND = "mihon.together.extra.KIND"
    private const val EXTRA_TARGET = "mihon.together.extra.TARGET"
    private const val EXTRA_NOTIFICATION = "mihon.together.extra.NOTIFICATION"
    private const val EXTRA_NAME = "mihon.together.extra.NAME"
    private const val EXTRA_TEXT = "mihon.together.extra.TEXT"
    private const val EXTRA_GROUP = "mihon.together.extra.GROUP"

    fun show(context: Context, push: SocialPushMessage) {
        val id = push.notificationId()
        val route = FriendsRoute.forPush(push)
        val (title, text) = texts(context, push)
        val isMessage = push.kind == SocialPushMessage.MESSAGE || push.kind == SocialPushMessage.GROUP_MESSAGE
        val builder = base(context, id, route, push.kind).apply {
            setContentTitle(title)
            setContentText(text)
            if (isMessage) {
                setStyle(messaging(context, push.group, listOf(push.name to text)))
            } else {
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
            }
        }
        when (push.kind) {
            SocialPushMessage.MESSAGE -> push.uid?.let { builder.addAction(replyAction(context, push, id, it, text)) }
            SocialPushMessage.GROUP_MESSAGE ->
                push.groupId?.let { builder.addAction(replyAction(context, push, id, it, text)) }
            SocialPushMessage.FRIEND_REQUEST -> push.uid?.let { uid ->
                val accept = Intent(context, SocialNotificationReceiver::class.java)
                    .setAction(ACTION_ACCEPT)
                    .putExtra(EXTRA_TARGET, uid)
                    .putExtra(EXTRA_NOTIFICATION, id)
                    .putExtra(EXTRA_NAME, push.name)
                builder.addAction(
                    R.drawable.ic_mihon,
                    context.stringResource(MR.strings.push_action_accept),
                    PendingIntent.getBroadcast(context, id * 31 + 2, accept, IMMUTABLE),
                )
            }
            SocialPushMessage.ROOM_INVITE -> push.roomId?.let { roomId ->
                builder.addAction(
                    R.drawable.ic_mihon,
                    context.stringResource(MR.strings.push_action_join),
                    openFriends(context, id * 31 + 3, FriendsRoute.Room(roomId, join = true)),
                )
            }
        }
        // Lets opening that conversation clear this one too, even when its id can't be recomputed (read matches).
        SocialForeground.targetOf(push)?.let { target ->
            builder.addExtras(bundleOf(SocialForeground.EXTRA_SUBJECT to SocialForeground.subject(target)))
        }
        context.notify(id, builder.build())
    }

    private fun texts(context: Context, push: SocialPushMessage): Pair<String, String> = when (push.kind) {
        SocialPushMessage.FRIEND_REQUEST -> context.stringResource(MR.strings.push_friend_request_title) to
            context.stringResource(MR.strings.push_friend_request, push.name)
        SocialPushMessage.FRIEND_ACCEPTED -> context.stringResource(MR.strings.push_friend_accepted_title) to
            context.stringResource(MR.strings.push_friend_accepted, push.name)
        SocialPushMessage.ROOM_INVITE -> context.stringResource(MR.strings.push_room_invite_title, push.name) to
            context.stringResource(MR.strings.push_room_invite, push.title.orEmpty())
        SocialPushMessage.MESSAGE -> push.name to (push.title ?: context.stringResource(MR.strings.push_message))
        SocialPushMessage.GROUP_MESSAGE ->
            (push.group?.let { "${push.name} · $it" } ?: push.name) to
                (push.title ?: context.stringResource(MR.strings.push_message))
        SocialPushMessage.GROUP_ADDED -> context.stringResource(MR.strings.push_group_added_title) to
            context.stringResource(MR.strings.push_group_added, push.name, push.title.orEmpty())
        SocialPushMessage.READ_MATCH -> context.stringResource(
            MR.strings.push_read_match_title,
            push.title.orEmpty(),
        ) to
            context.stringResource(MR.strings.push_read_match, push.name)
        SocialPushMessage.NUDGE -> context.stringResource(MR.strings.push_nudge_title, push.name) to
            (
                push.title?.toIntOrNull()?.takeIf { it >= 2 }
                    ?.let { context.stringResource(MR.strings.push_nudge_streak, it) }
                    ?: context.stringResource(MR.strings.push_nudge)
                )
        else -> context.stringResource(MR.strings.push_join_request_title) to
            context.stringResource(MR.strings.push_join_request, push.name)
    }

    /** The common shape: icon, tap target, category and the lock screen choice. */
    private fun base(context: Context, id: Int, route: FriendsRoute, kind: String): NotificationCompat.Builder =
        context.notificationBuilder(Notifications.CHANNEL_SOCIAL) {
            setSmallIcon(R.drawable.ic_mihon)
            setCategory(
                if (kind == SocialPushMessage.MESSAGE || kind == SocialPushMessage.GROUP_MESSAGE) {
                    NotificationCompat.CATEGORY_MESSAGE
                } else {
                    NotificationCompat.CATEGORY_SOCIAL
                },
            )
            setContentIntent(openFriends(context, id, route))
            setAutoCancel(true)
            if (context.appGraph.socialNotificationPreferences.hideOnLockScreen().get()) {
                setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                setPublicVersion(
                    context.notificationBuilder(Notifications.CHANNEL_SOCIAL) {
                        setSmallIcon(R.drawable.ic_mihon)
                        setContentTitle(context.stringResource(MR.strings.push_hidden_title))
                        setContentText(context.stringResource(MR.strings.push_hidden_text))
                    }.build(),
                )
            }
        }

    /** Opens Friends at [route]; each request code gets its own PendingIntent (extras differ per notification). */
    fun openFriends(context: Context, requestCode: Int, route: FriendsRoute): PendingIntent {
        val (screen, target) = FriendsRoute.extras(route)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(SocialMessagingService.ACTION_OPEN_FRIENDS)
            .putExtra(FriendsRoute.EXTRA_SCREEN, screen)
            .putExtra(FriendsRoute.EXTRA_ID, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or IMMUTABLE)
    }

    /** A conversation: [lines] are (sender, text), a null sender is me. */
    private fun messaging(
        context: Context,
        group: String?,
        lines: List<Pair<String?, String>>,
    ): NotificationCompat.MessagingStyle {
        val me = Person.Builder().setName(context.stringResource(MR.strings.push_you)).build()
        val now = System.currentTimeMillis()
        // Not `apply {}`: Style has its own apply(builder) method.
        val style = NotificationCompat.MessagingStyle(me)
        if (group != null) {
            style.setConversationTitle(group)
            style.setGroupConversation(true)
        }
        lines.forEach { (sender, line) ->
            style.addMessage(line, now, sender?.let { Person.Builder().setName(it).build() })
        }
        return style
    }

    private fun replyAction(
        context: Context,
        push: SocialPushMessage,
        id: Int,
        target: String,
        text: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, SocialNotificationReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_KIND, push.kind)
            .putExtra(EXTRA_TARGET, target)
            .putExtra(EXTRA_NOTIFICATION, id)
            .putExtra(EXTRA_NAME, push.name)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_GROUP, push.group)
        // RemoteInput fills in the reply, so this one PendingIntent has to stay mutable.
        val pending = PendingIntent.getBroadcast(
            context,
            id * 31 + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.stringResource(MR.strings.push_reply_hint)).build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_mihon,
            context.stringResource(MR.strings.push_action_reply),
            pending,
        )
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    /** The text typed into the notification's Reply box, cleaned like the chat box does; null when empty. */
    fun replyText(raw: CharSequence?): String? = raw?.toString()?.replace('\n', ' ')?.trim()?.take(500)
        ?.takeIf { it.isNotEmpty() }

    internal fun handle(context: Context, intent: Intent, finish: () -> Unit) {
        val target = intent.getStringExtra(EXTRA_TARGET)
        val id = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        when (intent.action) {
            ACTION_REPLY -> {
                val kind = intent.getStringExtra(EXTRA_KIND)
                val text = replyText(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY))
                val group = intent.getStringExtra(EXTRA_GROUP)
                val original = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val isGroup = kind == SocialPushMessage.GROUP_MESSAGE
                val route = when {
                    isGroup -> FriendsRoute.validId(target)?.let(FriendsRoute::Group)
                    else -> FriendsRoute.validUid(target)?.let(FriendsRoute::Chat)
                } ?: FriendsRoute.Home
                if (text == null || target == null) {
                    finish()
                    return
                }
                runAsync(finish) {
                    try {
                        val repository = context.appGraph.socialRepository
                        checkNotNull(repository.currentUid) { "Signed out" }
                        withTimeout(SEND_TIMEOUT_MS) {
                            if (isGroup) {
                                repository.sendGroupMessage(
                                    target,
                                    text,
                                )
                            } else {
                                repository.sendMessage(target, text)
                            }
                        }
                        val updated = base(context, id, route, SocialPushMessage.MESSAGE)
                            .setContentTitle(name)
                            .setContentText(text)
                            .setStyle(messaging(context, group, listOf(name to original, null to text)))
                            .setOnlyAlertOnce(true)
                            .setSilent(true)
                        context.notify(id, updated.build())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Social notification reply not sent" }
                        failed(context, id, route, name, MR.strings.push_reply_failed)
                    }
                }
            }
            ACTION_ACCEPT -> {
                val uid = FriendsRoute.validUid(target)
                if (uid == null) {
                    finish()
                    return
                }
                runAsync(finish) {
                    try {
                        val repository = context.appGraph.socialRepository
                        checkNotNull(repository.currentUid) { "Signed out" }
                        withTimeout(SEND_TIMEOUT_MS) { repository.friendAction("accept", uid) }
                        context.cancelNotification(id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Social notification accept failed" }
                        failed(context, id, FriendsRoute.Requests, name, MR.strings.push_accept_failed)
                    }
                }
            }
            else -> finish()
        }
    }

    /** The action didn't go through: say so, and let a tap open the place to do it by hand. */
    private fun failed(
        context: Context,
        id: Int,
        route: FriendsRoute,
        name: String,
        text: dev.icerock.moko.resources.StringResource,
    ) {
        val notification = base(context, id, route, SocialPushMessage.FRIEND_REQUEST)
            .setContentTitle(name.ifEmpty { context.stringResource(MR.strings.push_hidden_title) })
            .setContentText(context.stringResource(text))
            .setOnlyAlertOnce(true)
            .build()
        context.notify(id, notification)
    }

    private fun runAsync(finish: () -> Unit, block: suspend () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block()
            } finally {
                finish()
            }
        }
    }

    private const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE

    /** A broadcast may run about ten seconds after goAsync. */
    private const val SEND_TIMEOUT_MS = 8_000L
}

/** Reply and Accept from a social notification, without opening the app. */
class SocialNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        SocialNotifications.handle(context.applicationContext, intent) { pending.finish() }
    }
}
