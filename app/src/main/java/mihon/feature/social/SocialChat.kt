package mihon.feature.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.relativeTimeSpanString
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource

/** A friend's conversation: newest at the bottom, one-line messages (the backend keeps them single-line). */
@Composable
fun ChatView(
    chat: OpenChat,
    me: String,
    contentPadding: PaddingValues,
    titles: List<SocialContent> = emptyList(),
    onPickTitle: (SocialContent) -> Unit = {},
    onRecommend: (SocialContent) -> Unit = {},
    /** My library index, to tell whether a matched title can be read together right away. */
    localIndex: Map<String, Long> = emptyMap(),
    onReadTogether: (SocialContent) -> Unit = onPickTitle,
    /** The friend's name, for quotes and the reply bar. */
    otherName: String = "",
    onReact: (seq: Long, emoji: String) -> Unit = { _, _ -> },
    onSend: (text: String, replyTo: Long?) -> Unit,
) {
    var draft by rememberSaveable(chat.otherUid) { mutableStateOf("") }
    var replyingSeq by rememberSaveable(chat.otherUid) { mutableStateOf<Long?>(null) }
    val replying = replyingSeq?.let { seq -> chat.messages.firstOrNull { it.seq == seq } }
    // The text being sent: the draft and reply stay until the send succeeded (a failed one can be retried).
    var pending by remember(chat.otherUid) { mutableStateOf<String?>(null) }
    LaunchedEffect(chat.sent) {
        val sent = pending ?: return@LaunchedEffect
        if (draft.trim() == sent) draft = ""
        replyingSeq = null
        pending = null
    }
    val you = stringResource(MR.strings.reader_social_you)
    val nameOf = { uid: String -> if (uid == me) you else otherName }
    val listState = rememberLazyListState()
    val jump = rememberChatJump(listState)
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        TitlePickerDialog(titles, onPick = {
            onRecommend(it)
            choosing = false
        }) { choosing = false }
    }
    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        val shown = chat.messages.asReversed()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            items(shown, key = { it.seq }) { message ->
                when (message.system) {
                    ChatMessage.SYSTEM_MATCH -> message.content?.let { content ->
                        MatchCard(content, localIndex[content.contentKey] != null, onPickTitle, onReadTogether)
                    }
                    ChatMessage.SYSTEM_RECAP -> message.recap?.let { RecapLine(it) }
                    else -> Bubble(
                        message,
                        me = me,
                        highlighted = jump.highlighted == message.seq,
                        quoteName = nameOf,
                        onPickTitle = onPickTitle,
                        onReply = { replyingSeq = message.seq },
                        onReact = { onReact(message.seq, it) },
                        onQuote = { seq -> jump.to(seq, shown.indexOfFirst { it.seq == seq }) },
                    )
                }
            }
            if (chat.loaded && chat.messages.isEmpty()) {
                item { Text(stringResource(MR.strings.social_chat_empty), style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (chat.failed) {
            Text(
                stringResource(MR.strings.social_chat_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        replying?.let {
            ReplyBar(nameOf(it.senderUid), ChatReactions.preview(it.text, it.content)) { replyingSeq = null }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (titles.isNotEmpty()) RecommendButton(enabled = !chat.sending) { choosing = true }
            OutlinedTextField(
                value = draft,
                // Messages are single-line on the server; Enter becomes a space.
                onValueChange = { draft = it.replace('\n', ' ').take(MAX_MESSAGE) },
                placeholder = { Text(stringResource(MR.strings.social_chat_hint)) },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = draft.isNotBlank() && !chat.sending,
                onClick = {
                    pending = draft.trim()
                    onSend(draft.trim(), replying?.seq)
                },
            ) { Text(stringResource(MR.strings.social_chat_send)) }
        }
        Text(
            stringResource(MR.strings.social_chat_expiry),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun Bubble(
    message: ChatMessage,
    me: String,
    highlighted: Boolean,
    quoteName: (String) -> String,
    onPickTitle: (SocialContent) -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onQuote: (Long) -> Unit,
) {
    ReactableBubble(
        mine = message.senderUid == me,
        me = me,
        reactions = message.reactions,
        copyText = message.text.ifEmpty { message.content?.title.orEmpty() },
        highlighted = highlighted,
        onReply = onReply,
        onReact = onReact,
    ) {
        message.replyTo?.let { quote ->
            ChatQuoteBlock(quoteName(quote.senderUid), quote.preview) { onQuote(quote.seq) }
        }
        message.content?.let { TitleCard(it) { onPickTitle(it) } }
        if (message.text.isNotEmpty()) Text(message.text)
        Text(
            relativeTimeSpanString(message.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "You both want to read X": the server's match line, with Read together (or the title's options). */
@Composable
private fun MatchCard(
    content: SocialContent,
    inLibrary: Boolean,
    onPickTitle: (SocialContent) -> Unit,
    onReadTogether: (SocialContent) -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(MR.strings.social_match_title, content.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                TitleCard(content) { onPickTitle(content) }
                TextButton(onClick = { if (inLibrary) onReadTogether(content) else onPickTitle(content) }) {
                    Text(
                        stringResource(
                            if (inLibrary) MR.strings.social_read_together else MR.strings.social_search_title,
                        ),
                    )
                }
            }
        }
    }
}

/** "📖 Read together: X • ch. A–B • N min", centered like an event line. */
@Composable
private fun RecapLine(recap: ChatRecap) {
    val parts = buildList {
        add(stringResource(MR.strings.social_recap_line, recap.title))
        recap.chapterSpan()?.let { (from, to) ->
            add(
                if (to == null) {
                    stringResource(MR.strings.social_recap_chapter, from)
                } else {
                    stringResource(MR.strings.social_recap_chapters, from, to)
                },
            )
        }
        recap.minutes?.let { add(stringResource(MR.strings.social_recap_minutes, it)) }
    }
    Text(
        parts.joinToString(" • "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** One conversation in the Friends list: friend, last line, unread count. */
@Composable
fun ChatRow(
    summary: ChatSummary,
    friend: SocialProfile,
    me: String,
    loadMedia: ProfileMediaLoader,
    onOpen: () -> Unit,
) {
    Card(onClick = onOpen) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(friend, loadMedia, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (summary.lastSenderUid == me) {
                        stringResource(MR.strings.social_chat_you, summary.lastText)
                    } else {
                        summary.lastText
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.unread > 0) Badge(text = summary.unread.toString())
        }
    }
}

private const val MAX_MESSAGE = 500

/** A recommended title inside a message: cover and name; tapping it offers Open (in my library) or Search. */
@Composable
fun TitleCard(content: SocialContent, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        eu.kanade.presentation.manga.components.MangaCover.Book(
            data = tachiyomi.domain.manga.model.MangaCover(
                mangaId = -1,
                sourceId = -1,
                isMangaFavorite = false,
                url = content.coverUrl,
                lastModified = 0,
            ),
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.padding(start = 8.dp).widthIn(max = 200.dp)) {
            Text(stringResource(MR.strings.social_recommended), style = MaterialTheme.typography.labelSmall)
            Text(
                content.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            content.source?.let {
                Text(
                    it.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The "📖" button left of the message box: recommend one of my titles. */
@Composable
fun RecommendButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick) {
        Text("📖", style = MaterialTheme.typography.titleMedium)
    }
}
