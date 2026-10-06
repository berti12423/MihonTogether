package mihon.feature.social

import android.content.ClipData
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.ContentCopy
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A chat bubble that takes replies and reactions, for private and group chats: long-press opens the reaction bar
 * (quick emojis and "+" for any emoji) with Reply and Copy, a swipe to the right replies, and the reactions show as
 * chips under it. [content] is the bubble's inside (name, quote, card, text, time).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReactableBubble(
    mine: Boolean,
    me: String,
    reactions: Map<String, String>,
    copyText: String,
    highlighted: Boolean,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var anyEmoji by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    if (anyEmoji) {
        AnyEmojiDialog(
            onPick = {
                anyEmoji = false
                onReact(it)
            },
            onDismiss = { anyEmoji = false },
        )
    }
    val color by animateColorAsState(
        when {
            highlighted -> MaterialTheme.colorScheme.tertiaryContainer
            mine -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "bubble",
    )
    SwipeToReply(onReply) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                Box {
                    Column(
                        Modifier
                            .widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(color)
                            .combinedClickable(
                                onClickLabel = null,
                                onLongClickLabel = stringResource(MR.strings.social_chat_message_actions),
                                onLongClick = { menu = true },
                                onClick = {},
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        content = content,
                    )
                    MessageMenu(
                        expanded = menu,
                        current = reactions[me],
                        canCopy = copyText.isNotEmpty(),
                        onDismiss = { menu = false },
                        onReact = {
                            menu = false
                            onReact(it)
                        },
                        onMore = {
                            menu = false
                            anyEmoji = true
                        },
                        onReply = {
                            menu = false
                            onReply()
                        },
                        onCopy = {
                            menu = false
                            val entry = ClipData.newPlainText(copyText, copyText).toClipEntry()
                            scope.launch { clipboard.setClipEntry(entry) }
                        },
                    )
                }
                if (reactions.isNotEmpty()) ReactionChips(ChatReactions.chips(reactions, me), onReact)
            }
        }
    }
}

/** The long-press menu: the quick reactions in a row (mine marked) and "+", then Reply and Copy. */
@Composable
private fun MessageMenu(
    expanded: Boolean,
    current: String?,
    canCopy: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onMore: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            (ChatReactions.QUICK + listOfNotNull(current?.takeIf { it !in ChatReactions.QUICK })).forEach { emoji ->
                Text(
                    emoji,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (emoji == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        )
                        .clickable { onReact(emoji) }
                        .padding(6.dp),
                )
            }
            TextButton(onClick = onMore) {
                Text(
                    "+",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(MR.strings.social_chat_reply)) },
            leadingIcon = { Text("↩", style = MaterialTheme.typography.titleMedium) },
            onClick = onReply,
        )
        if (canCopy) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.social_chat_copy)) },
                leadingIcon = { Icon(MaterialSymbols.Rounded.ContentCopy, contentDescription = null) },
                onClick = onCopy,
            )
        }
    }
}

/** Reaction chips under a bubble ("❤️ 2"); mine highlighted; tapping one toggles my reaction to that emoji. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionChips(chips: List<ReactionChip>, onToggle: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(top = 2.dp).widthIn(max = 300.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { chip ->
            Surface(
                onClick = { onToggle(chip.emoji) },
                shape = RoundedCornerShape(12.dp),
                color = if (chip.mine) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                border = if (chip.mine) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Text(
                    "${chip.emoji} ${chip.count}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (chip.mine) FontWeight.Bold else null,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Swipe a message to the right to reply: it follows the finger with some resistance, a "↩" shows behind it, and
 * letting go past the threshold replies. Only horizontal drags are taken, so the list still scrolls.
 */
@Composable
private fun SwipeToReply(onReply: () -> Unit, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reply by rememberUpdatedState(onReply)
    val settle = {
        scope.launch { animate(offset, 0f) { value, _ -> offset = value } }
        Unit
    }
    Box(
        Modifier.pointerInput(threshold) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (offset >= threshold) reply()
                    settle()
                },
                onDragCancel = settle,
            ) { change, amount ->
                val next = (offset + amount * 0.5f).coerceIn(0f, threshold * 1.3f)
                if (next == offset) return@detectHorizontalDragGestures
                change.consume()
                if (offset < threshold && next >= threshold) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                offset = next
            }
        },
    ) {
        Text(
            "↩",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .graphicsLayer { alpha = (offset / threshold).coerceIn(0f, 1f) },
        )
        Box(Modifier.graphicsLayer { translationX = offset }) { content() }
    }
}

/**
 * The quote at the top of a reply's bubble: who wrote the original and its start; tapping it jumps there. Without
 * [preview] and [onClick] it's a placeholder (a quote I may not see).
 */
@Composable
fun ChatQuoteBlock(name: String, preview: String?, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            preview?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Above the message box while replying: what I reply to, and X to cancel. */
@Composable
fun ReplyBar(name: String, preview: String, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("↩", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                stringResource(MR.strings.social_chat_replying_to, name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel) {
            Icon(
                MaterialSymbols.Rounded.Close,
                contentDescription = stringResource(MR.strings.social_chat_reply_cancel),
            )
        }
    }
}

/** "+" in the reaction bar: type (or pick from the keyboard) one emoji; checked like the server does. */
@Composable
private fun AnyEmojiDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val emoji = value.trim()
    val valid = ReactionEmoji.isValid(emoji)
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.social_chat_any_emoji)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(MAX_EMOJI_INPUT) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium,
                placeholder = { Text(stringResource(MR.strings.social_chat_any_emoji_hint)) },
                isError = emoji.isNotEmpty() && !valid,
                supportingText = {
                    if (emoji.isNotEmpty() && !valid) Text(stringResource(MR.strings.social_chat_any_emoji_invalid))
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onPick(emoji) }) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

private const val MAX_EMOJI_INPUT = 32

/** Tapping a quote: scrolls the chat list to the original (when it's loaded) and lights it up for a moment. */
@Stable
class ChatJump internal constructor(private val list: LazyListState, private val scope: CoroutineScope) {
    var highlighted by mutableStateOf<Long?>(null)
        private set
    private var job: Job? = null

    /** [index] is the original's position in the list; -1 (not loaded) does nothing. */
    fun to(seq: Long, index: Int) {
        if (index < 0) return
        job?.cancel()
        job = scope.launch {
            list.animateScrollToItem(index)
            highlighted = seq
            delay(HIGHLIGHT_MILLIS)
            highlighted = null
        }
    }

    private companion object {
        const val HIGHLIGHT_MILLIS = 1_200L
    }
}

@Composable
fun rememberChatJump(list: LazyListState): ChatJump {
    val scope = rememberCoroutineScope()
    return remember(list, scope) { ChatJump(list, scope) }
}
