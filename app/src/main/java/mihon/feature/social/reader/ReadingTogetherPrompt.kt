package mihon.feature.social.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A small card over the reader for a guest in an active room: on the room's title it offers to follow the host in
 * one tap; on another title it says what the host reads and offers to go there. "Just chat" keeps the room for
 * messages and reactions only.
 */
@Composable
fun ReadingTogetherPrompt(
    state: ReaderSocialState,
    onFollow: () -> Unit,
    onJustChat: () -> Unit,
    onGoThere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val room = state.room ?: return
    if (room.state != "active" || !state.foreground || state.chatOnly || state.isHost) return
    val elsewhere = state.status == ReaderSocialStatus.ELSEWHERE
    if (!elsewhere && !state.followOffer) return
    val friend = room.nameOf(room.hostUid)
        ?: state.friends.firstOrNull { it.uid == room.hostUid }?.displayName
        ?: stringResource(MR.strings.reader_social_friend)
    val message = if (elsewhere) {
        stringResource(
            MR.strings.reader_social_host_elsewhere,
            friend,
            room.content.title,
            room.position?.chapterLabel.orEmpty(),
        )
    } else {
        stringResource(MR.strings.reader_social_follow_offer, friend)
    }
    Surface(
        modifier = modifier.widthIn(max = 420.dp).padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onJustChat) { Text(stringResource(MR.strings.reader_social_just_chat)) }
                Button(onClick = if (elsewhere) onGoThere else onFollow) {
                    Text(
                        stringResource(
                            if (elsewhere) MR.strings.reader_social_go_there else MR.strings.reader_social_follow,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * "Bob asks to read along": shown over the reader while I read something I may share. Accepting invites them (into my
 * room if I host one, otherwise a new room from this chapter).
 */
@Composable
fun JoinRequestPrompt(
    state: ReaderSocialState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = state.joinRequests.firstOrNull() ?: return
    if (!state.foreground || state.status.isPaused || state.local == null) return
    // A guest in someone else's room can't invite; the request waits (and shows on the Friends screen).
    if (state.room != null && !state.isHost) return
    val name = request.name.ifBlank { request.handle }
    Surface(
        modifier = modifier.widthIn(max = 420.dp).padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                stringResource(MR.strings.reader_social_join_request, name),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onDecline(request.requesterUid) }) {
                    Text(stringResource(MR.strings.reader_social_join_decline))
                }
                Button(onClick = { onAccept(request.requesterUid) }, enabled = !state.busy) {
                    Text(stringResource(MR.strings.reader_social_join_accept))
                }
            }
        }
    }
}
