package mihon.feature.social.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import mihon.feature.social.RoomMessage
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val MAX_BUBBLES = 4
private const val BUBBLE_HOLD_MS = 2_200L

/**
 * Clash Royale-style emotes over the reader: reactions and short messages pop in from the screen edge with a
 * bounce (the friend's from the left, mine from the right), hold briefly and fade. A floating emote button on
 * the left edge opens the reaction picker while the room is active.
 */
@Composable
fun ReadingTogetherReactions(
    state: ReaderSocialState,
    popups: Flow<RoomPopup>,
    onReact: (String) -> Unit,
    onSendChat: (String) -> Unit = {},
    onFollowMode: (Boolean) -> Unit = {},
    onSignal: (String) -> Unit = {},
) {
    val bubbles = remember { mutableStateListOf<RoomPopup>() }
    LaunchedEffect(popups) {
        popups.collect { popup ->
            bubbles.add(popup)
            while (bubbles.size > MAX_BUBBLES) bubbles.removeAt(0)
        }
    }
    val active = state.room?.state == "active" && state.foreground
    LaunchedEffect(active) { if (!active) bubbles.clear() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val travel = with(LocalDensity.current) { maxWidth.toPx() * 0.6f }
        bubbles.forEachIndexed { index, popup ->
            key(popup.seq) {
                EmoteBubble(
                    popup = popup,
                    travel = travel,
                    modifier = Modifier
                        .align(if (popup.mine) Alignment.TopEnd else Alignment.TopStart)
                        .padding(top = 160.dp + (index * 76).dp, start = 16.dp, end = 16.dp),
                    onDone = { bubbles.remove(popup) },
                )
            }
        }
        if (active) {
            // Guests on the room's title can switch between following and just chatting from here too.
            val followMode = state.following.takeIf { !state.isHost && state.status != ReaderSocialStatus.ELSEWHERE }
            EmotePicker(
                onReact,
                onSendChat,
                followMode,
                onFollowMode,
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp, top = 220.dp),
                onSignal,
                readyCount = state.signals.values.count { it == RoomMessage.KIND_READY },
                waiting = state.signals.values.count { it == RoomMessage.KIND_WAIT },
            )
        }
    }
}

@Composable
private fun EmoteBubble(popup: RoomPopup, travel: Float, modifier: Modifier, onDone: () -> Unit) {
    val offset = remember { Animatable(if (popup.mine) travel else -travel) }
    val scale = remember { Animatable(0.3f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(popup.seq) {
        val bounce =
            spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        launch { offset.animateTo(0f, bounce) }
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow))
        delay(BUBBLE_HOLD_MS)
        alpha.animateTo(0f, tween(300))
        onDone()
    }
    Surface(
        modifier = modifier
            .graphicsLayer {
                translationX = offset.value
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
            .widthIn(max = 220.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (popup.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        val event = popup.event
        if (event != null) {
            Text(
                roomEventText(event, popup.senderName.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else if (popup.emoji != null) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                // With several people in the room, say who reacted.
                if (!popup.mine && popup.senderName != null) {
                    Text(popup.senderName, style = MaterialTheme.typography.labelSmall)
                }
                Text(popup.emoji, fontSize = 40.sp)
            }
        } else {
            Text(
                if (!popup.mine && popup.senderName != null) {
                    "${popup.senderName}: ${popup.text.orEmpty()}"
                } else {
                    popup.text.orEmpty()
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun EmotePicker(
    onReact: (String) -> Unit,
    onSendChat: (String) -> Unit,
    following: Boolean?,
    onFollowMode: (Boolean) -> Unit,
    modifier: Modifier,
    onSignal: (String) -> Unit = {},
    readyCount: Int = 0,
    waiting: Int = 0,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(MR.strings.reader_social_react)
    Row(modifier, verticalAlignment = Alignment.Top) {
        Surface(
            onClick = { open = !open },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) { Text("😀", fontSize = 24.sp) }
        }
        AnimatedVisibility(visible = open, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 4.dp,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Column {
                    ReactionStrip(
                        onReact = {
                            onReact(it)
                            open = false
                        },
                    )
                    // Ready check: one tap to say "I'm done" or "wait for me"; counts show where the room stands.
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            onSignal(RoomMessage.KIND_READY)
                            open = false
                        }) { Text(stringResource(MR.strings.reader_social_ready_button)) }
                        TextButton(onClick = {
                            onSignal(RoomMessage.KIND_WAIT)
                            open = false
                        }) { Text(stringResource(MR.strings.reader_social_wait)) }
                        if (readyCount + waiting > 0) {
                            Text(
                                stringResource(MR.strings.reader_social_signal_counts, readyCount, waiting),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    QuickChat(
                        onSend = {
                            onSendChat(it)
                            open = false
                        },
                    )
                    if (following != null) {
                        TextButton(
                            onClick = {
                                onFollowMode(!following)
                                open = false
                            },
                            modifier = Modifier.padding(start = 4.dp),
                        ) {
                            val mode = if (following) {
                                MR.strings.reader_social_just_chat
                            } else {
                                MR.strings.reader_social_follow
                            }
                            Text(stringResource(mode))
                        }
                    }
                }
            }
        }
    }
}

/** A one-line message box under the emotes, so a quick message doesn't need the Read together dialog. */
@Composable
private fun QuickChat(onSend: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val send = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            draft = ""
        }
    }
    Row(
        Modifier.padding(start = 12.dp, end = 4.dp, bottom = 6.dp).widthIn(max = 340.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.replace('\n', ' ').take(RoomMessage.MAX_TEXT) },
            placeholder = { Text(stringResource(MR.strings.reader_social_chat_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = send, enabled = draft.isNotBlank()) {
            Text(stringResource(MR.strings.reader_social_send))
        }
    }
}
