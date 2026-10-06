package mihon.feature.social.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.feature.social.RoomMember
import mihon.feature.social.RoomMessage
import mihon.feature.social.SocialRoom
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Plain content: the reader owns account, room, consent and network operations. */
@Composable
fun ReadingTogetherDialog(
    state: ReaderSocialState,
    onConfirmPageOrder: () -> Unit,
    onInvite: (String) -> Unit,
    onFollow: (Boolean) -> Unit,
    onAcceptFollow: () -> Unit = { onFollow(true) },
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
    onSendChat: (String) -> Unit = {},
    onReact: (String) -> Unit = {},
    onKick: (String) -> Unit = {},
    onDropIn: (Boolean) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.reader_social_read_together)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(statusString(state.status)))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.local?.let { local ->
                    Text(local.content.title, style = MaterialTheme.typography.titleSmall)
                    Text(local.position.chapterLabel)
                }
                state.room?.let { room ->
                    RoomMembers(room, state, onKick)
                    if (!state.isHost) {
                        Text(
                            stringResource(MR.strings.reader_social_positions_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.room?.let { room ->
                    Text(stringResource(MR.strings.reader_social_host_position))
                    Text(room.content.title)
                    room.position?.let { position ->
                        Text("${position.chapterLabel} · ${position.pageIndex + 1}/${position.pageCount}")
                    }
                }
                if (state.signedIn && state.foreground && state.local != null && !state.status.isPaused) {
                    val active = state.room?.state == "active"
                    // Only shown when following needs a fresh consent (a new edition); the follow prompt and
                    // inviting otherwise give it in one tap.
                    if (state.status == ReaderSocialStatus.CONFIRM_MAPPING) {
                        Text(stringResource(MR.strings.reader_social_mapping_consent))
                        Button(onClick = onConfirmPageOrder, enabled = !state.mappingConfirmed && !state.busy) {
                            Text(stringResource(MR.strings.reader_social_confirm_mapping))
                        }
                    }
                    if (active && !state.isHost && state.status != ReaderSocialStatus.ELSEWHERE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.following,
                                onCheckedChange = { if (it) onAcceptFollow() else onFollow(false) },
                                enabled = !state.busy,
                            )
                            Text(stringResource(MR.strings.reader_social_follow_host))
                        }
                    }
                    if (active) {
                        RoomChat(state, onSendChat, onReact)
                    }
                    val room = state.room
                    if (room != null && state.isHost && room.state != "closed") {
                        DropInSwitch(checked = room.open, enabled = !state.busy, onChange = onDropIn)
                    }
                    if (room != null && state.isHost && room.state != "closed") {
                        val invitable = state.friends.filter { it.uid !in room.participants }
                        if (!room.full && invitable.isNotEmpty()) {
                            Text(
                                stringResource(MR.strings.reader_social_invite_more),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            invitable.forEach { friend ->
                                TextButton(onClick = { onInvite(friend.uid) }, enabled = !state.busy) {
                                    Text(friend.displayName + " (@" + friend.handle + ")")
                                }
                            }
                        } else if (room.full) {
                            Text(
                                stringResource(MR.strings.reader_social_room_full),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (state.selectedRoomId == null && state.status != ReaderSocialStatus.MISMATCH) {
                        DropInSwitch(checked = state.dropInOnCreate, enabled = !state.busy, onChange = onDropIn)
                        Text(stringResource(MR.strings.reader_social_invite_consent))
                        Text(stringResource(MR.strings.reader_social_mapping_consent))
                        if (state.friends.isEmpty()) Text(stringResource(MR.strings.reader_social_no_friends))
                        state.friends.forEach { friend ->
                            TextButton(
                                onClick = { onInvite(friend.uid) },
                                enabled = !state.busy,
                            ) { Text(friend.displayName + " (@" + friend.handle + ")") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
        dismissButton = {
            if (state.selectedRoomId != null) {
                TextButton(onClick = onLeave, enabled = !state.busy) {
                    Text(stringResource(MR.strings.reader_social_leave))
                }
            }
        },
    )
}

/** “Let friends drop in”: off by default, for the room I host or the next one I start. */
@Composable
private fun DropInSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(MR.strings.reader_social_drop_in), modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
        Text(
            stringResource(MR.strings.reader_social_drop_in_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoomChat(state: ReaderSocialState, onSendChat: (String) -> Unit, onReact: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Text(stringResource(MR.strings.reader_social_chat), style = MaterialTheme.typography.titleSmall)
    state.messages.takeLast(10).forEach { message ->
        val name = state.room?.nameOf(message.senderUid)
            ?: state.friends.firstOrNull { it.uid == message.senderUid }?.displayName
            ?: stringResource(MR.strings.reader_social_friend)
        if (message.kind in RoomMessage.EVENTS) {
            Text(
                roomEventText(message.kind, name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val sender = if (message.senderUid == state.selfUid) stringResource(MR.strings.reader_social_you) else name
            Text("$sender: ${message.emoji ?: message.text.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (state.chatError) {
        Text(stringResource(MR.strings.reader_social_chat_failed), color = MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.replace("\n", " ").take(RoomMessage.MAX_TEXT) },
            label = { Text(stringResource(MR.strings.reader_social_chat_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = draft.isNotBlank(),
            onClick = {
                onSendChat(draft)
                draft = ""
            },
        ) { Text(stringResource(MR.strings.reader_social_send)) }
    }
    ReactionStrip(onReact)
}

/** Who's in the room: host first, then joined and invited people; the host can remove anyone. */
@Composable
private fun RoomMembers(room: SocialRoom, state: ReaderSocialState, onKick: (String) -> Unit) {
    val me = state.selfUid ?: return
    Text(
        stringResource(MR.strings.reader_social_members, room.participants.size, room.maxMembers),
        style = MaterialTheme.typography.titleSmall,
    )
    val everyone = listOfNotNull(room.members[me] ?: RoomMember(me, RoomMember.JOINED, "")) + room.others(me)
    everyone.forEach { member ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            val name = if (member.uid == me) {
                stringResource(MR.strings.reader_social_you)
            } else {
                member.name.ifBlank { stringResource(MR.strings.reader_social_friend) }
            }
            val tag = when {
                member.uid == room.hostUid -> stringResource(MR.strings.reader_social_member_host)
                member.state == RoomMember.INVITED -> stringResource(MR.strings.reader_social_member_invited)
                else -> null
            }
            val signal = when (state.signals[member.uid]) {
                RoomMessage.KIND_READY -> " ✅"
                RoomMessage.KIND_WAIT -> " ⏸"
                else -> ""
            }
            Text((if (tag != null) "$name · $tag" else name) + signal, modifier = Modifier.weight(1f))
            if (state.isHost && member.uid != me) {
                TextButton(onClick = { onKick(member.uid) }, enabled = !state.busy) {
                    Text(stringResource(MR.strings.reader_social_remove_member))
                }
            }
        }
    }
}

/** The words for a server room event about [name]. */
@Composable
fun roomEventText(kind: String, name: String): String = when (kind) {
    RoomMessage.KIND_JOINED -> stringResource(MR.strings.reader_social_event_joined, name)
    RoomMessage.KIND_LEFT -> stringResource(MR.strings.reader_social_event_left, name)
    RoomMessage.KIND_REMOVED -> stringResource(MR.strings.reader_social_event_removed, name)
    RoomMessage.KIND_READY -> stringResource(MR.strings.reader_social_signal_ready, name)
    RoomMessage.KIND_WAIT -> stringResource(MR.strings.reader_social_signal_wait, name)
    else -> stringResource(MR.strings.reader_social_event_host, name)
}

/** One-tap reactions; also used by the floating emote picker on the reader. */
@Composable
fun ReactionStrip(onReact: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState())) {
        RoomMessage.REACTIONS.forEach { emoji ->
            TextButton(onClick = { onReact(emoji) }) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
        }
    }
}

private fun statusString(status: ReaderSocialStatus) = when (status) {
    ReaderSocialStatus.SIGNED_OUT -> MR.strings.reader_social_signed_out
    ReaderSocialStatus.PRIVATE -> MR.strings.reader_social_private
    ReaderSocialStatus.INVISIBLE -> MR.strings.reader_social_invisible
    ReaderSocialStatus.READY -> MR.strings.reader_social_ready
    ReaderSocialStatus.INVITED -> MR.strings.reader_social_invited
    ReaderSocialStatus.ACTIVE -> MR.strings.reader_social_active
    ReaderSocialStatus.WAITING -> MR.strings.reader_social_waiting
    ReaderSocialStatus.EXPIRED -> MR.strings.reader_social_expired
    ReaderSocialStatus.REVOKED -> MR.strings.reader_social_revoked
    ReaderSocialStatus.MISMATCH -> MR.strings.reader_social_mismatch
    ReaderSocialStatus.ELSEWHERE -> MR.strings.reader_social_elsewhere
    ReaderSocialStatus.CONFIRM_MAPPING -> MR.strings.reader_social_mapping_needed
    ReaderSocialStatus.LOADING -> MR.strings.reader_social_loading
    ReaderSocialStatus.ERROR -> MR.strings.reader_social_error
    ReaderSocialStatus.RETRACTION_PENDING -> MR.strings.reader_social_retraction_pending
}
