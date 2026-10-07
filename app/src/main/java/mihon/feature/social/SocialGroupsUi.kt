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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.relativeTimeSpanString
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** One group in the Messages list: name, member count, last line, unread count. */
@Composable
fun GroupRow(summary: GroupSummary, onOpen: () -> Unit) {
    Card(onClick = onOpen) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    summary.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    summary.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        summary.lastText.isEmpty() -> pluralStringResource(
                            MR.plurals.social_group_members,
                            summary.members,
                            summary.members,
                        )
                        summary.lastSenderName.isEmpty() -> summary.lastText
                        else -> "${summary.lastSenderName}: ${summary.lastText}"
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

/** A group conversation: names over other people's messages, system lines in the middle, newest at the bottom. */
@Composable
fun GroupChatView(
    open: OpenGroup,
    me: String,
    contentPadding: PaddingValues,
    blocked: Set<String> = emptySet(),
    titles: List<SocialContent> = emptyList(),
    onPickTitle: (SocialContent) -> Unit = {},
    onRecommend: (SocialContent) -> Unit = {},
    onReact: (seq: Long, emoji: String) -> Unit = { _, _ -> },
    onSend: (text: String, replyTo: Long?) -> Unit,
) {
    var draft by rememberSaveable(open.groupId) { mutableStateOf("") }
    var replyingSeq by rememberSaveable(open.groupId) { mutableStateOf<Long?>(null) }
    val replying = replyingSeq?.let { seq -> open.messages.firstOrNull { it.seq == seq && !it.isEvent } }
    // The text being sent: the draft and reply stay until the send succeeded (a failed one can be retried).
    var pending by remember(open.groupId) { mutableStateOf<String?>(null) }
    LaunchedEffect(open.sent) {
        val sent = pending ?: return@LaunchedEffect
        if (draft.trim() == sent) draft = ""
        replyingSeq = null
        pending = null
    }
    val you = stringResource(MR.strings.reader_social_you)
    val listState = rememberLazyListState()
    val jump = rememberChatJump(listState)
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        TitlePickerDialog(titles, onPick = {
            onRecommend(it)
            choosing = false
        }) { choosing = false }
    }
    val group = open.group
    // Group quotes keep the sender's name, so it shows even after they left.
    val quoteName = { quote: ChatQuote ->
        if (quote.senderUid == me) you else quote.senderName ?: group?.member(quote.senderUid)?.name.orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        // People I blocked stay hidden here too (the server refuses to add them, but they may join later).
        val messages = open.messages.filter { it.isEvent || it.senderUid !in blocked }.asReversed()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            items(messages.size, key = { messages[it].seq }) { index ->
                val message = messages[index]
                // Show the name on the first of consecutive messages by the same person (the list is reversed).
                val previous = messages.getOrNull(index + 1)
                val showName = previous == null || previous.isEvent || previous.senderUid != message.senderUid
                if (message.isEvent) {
                    EventLine(message, me)
                } else {
                    GroupBubble(
                        message,
                        me = me,
                        showName = showName,
                        accent = group?.member(message.senderUid)?.accent,
                        since = group?.member(me)?.since ?: Long.MAX_VALUE,
                        blocked = blocked,
                        highlighted = jump.highlighted == message.seq,
                        quoteName = quoteName,
                        onPickTitle = onPickTitle,
                        onReply = { replyingSeq = message.seq },
                        onReact = { onReact(message.seq, it) },
                        onQuote = { seq -> jump.to(seq, messages.indexOfFirst { it.seq == seq }) },
                    )
                }
            }
        }
        when {
            open.gone -> Text(
                stringResource(MR.strings.social_group_gone),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
            else -> {
                if (open.failed) {
                    Text(
                        stringResource(MR.strings.social_chat_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                replying?.let {
                    ReplyBar(
                        if (it.senderUid == me) you else it.senderName,
                        ChatReactions.preview(it.text, it.content),
                    ) { replyingSeq = null }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (titles.isNotEmpty()) {
                        RecommendButton(enabled = !open.sending && group != null) {
                            choosing = true
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.replace('\n', ' ').take(MAX_GROUP_MESSAGE) },
                        placeholder = { Text(stringResource(MR.strings.social_chat_hint)) },
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = draft.isNotBlank() && !open.sending && group != null,
                        onClick = {
                            pending = draft.trim()
                            onSend(draft.trim(), replying?.seq)
                        },
                    ) { Text(stringResource(MR.strings.social_chat_send)) }
                }
                Text(
                    stringResource(MR.strings.social_group_expiry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupBubble(
    message: GroupMessage,
    me: String,
    showName: Boolean,
    accent: Int?,
    since: Long,
    blocked: Set<String>,
    highlighted: Boolean,
    quoteName: (ChatQuote) -> String,
    onPickTitle: (SocialContent) -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onQuote: (Long) -> Unit,
) {
    val mine = message.senderUid == me
    ReactableBubble(
        mine = mine,
        me = me,
        reactions = ChatReactions.withoutBlocked(message.reactions, blocked),
        copyText = message.text.ifEmpty { message.content?.title.orEmpty() },
        highlighted = highlighted,
        onReply = onReply,
        onReact = onReact,
    ) {
        if (!mine && showName) {
            Text(
                message.senderName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent?.let(ProfileStyle::accent) ?: MaterialTheme.colorScheme.primary,
            )
        }
        message.replyTo?.let { quote ->
            val visible = ChatReactions.visibleQuote(quote, since, blocked)
            if (visible != null) {
                ChatQuoteBlock(quoteName(visible), visible.preview) { onQuote(visible.seq) }
            } else {
                ChatQuoteBlock(stringResource(MR.strings.social_chat_earlier_message), null, null)
            }
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

@Composable
private fun EventLine(message: GroupMessage, me: String) {
    val who = if (message.senderUid == me) stringResource(MR.strings.reader_social_you) else message.senderName
    val line = when (message.event) {
        "created" -> stringResource(MR.strings.social_group_event_created, who, message.text)
        "added" -> stringResource(MR.strings.social_group_event_added, who, message.text)
        "removed" -> stringResource(MR.strings.social_group_event_removed, who, message.text)
        "left" -> stringResource(MR.strings.social_group_event_left, message.text)
        "owner" -> stringResource(MR.strings.social_group_event_owner, message.text)
        "renamed" -> stringResource(MR.strings.social_group_event_renamed, who, message.text)
        else -> return
    }
    Text(
        line,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Pick friends (checkboxes) and, for a new group, a name. */
@Composable
fun GroupPickerDialog(
    title: String,
    friends: List<SocialProfile>,
    askName: Boolean,
    confirm: String,
    onConfirm: (name: String, uids: List<String>) -> Unit,
    onDismiss: () -> Unit,
    max: Int = GROUP_PICK_MAX,
    initial: List<String> = emptyList(),
) {
    var name by rememberSaveable { mutableStateOf("") }
    val picked = remember {
        mutableStateListOf<String>().apply {
            addAll(initial.filter { uid -> friends.any { it.uid == uid } }.distinct().take(max))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (askName) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.replace('\n', ' ').take(40) },
                        label = { Text(stringResource(MR.strings.social_group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                if (friends.isEmpty()) Text(stringResource(MR.strings.social_group_no_friends))
                friends.sortedBy { it.displayName.lowercase() }.forEach { friend ->
                    val checked = friend.uid in picked
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (checked) {
                                    picked.remove(friend.uid)
                                } else if (picked.size < max) {
                                    picked.add(friend.uid)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(friend.displayName, Modifier.padding(start = 8.dp).weight(1f))
                        Text(
                            "@${friend.handle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty() && (!askName || name.isNotBlank()),
                onClick = { onConfirm(name.trim(), picked.toList()) },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

/** Members (owner can remove), add friends, rename, leave. */
@Composable
fun GroupInfoDialog(
    group: SocialGroup,
    me: String,
    friends: List<SocialProfile>,
    busy: Boolean,
    onIntent: (FriendsIntent) -> Unit,
    /** Pick a title and read it with my friends in this group; null hides it (no titles or no friends here). */
    onReadTogether: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var name by rememberSaveable(group.groupId) { mutableStateOf(group.name) }
    val addable = friends.filter { friend -> group.member(friend.uid) == null }
    if (adding) {
        GroupPickerDialog(
            title = stringResource(MR.strings.social_group_add),
            friends = addable,
            askName = false,
            confirm = stringResource(MR.strings.social_group_add),
            onConfirm = { _, uids ->
                onIntent(FriendsIntent.AddToGroup(uids.take(GROUP_PICK_MAX - group.members.size + 1)))
                adding = false
            },
            onDismiss = { adding = false },
        )
        return
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(MR.strings.social_group_leave)) },
            text = { Text(stringResource(MR.strings.social_group_leave_confirm, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(FriendsIntent.LeaveGroup)
                    onDismiss()
                }) { Text(stringResource(MR.strings.social_group_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group.name) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (renaming) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.replace('\n', ' ').take(40) },
                        label = { Text(stringResource(MR.strings.social_group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                            onIntent(FriendsIntent.RenameGroup(name))
                            renaming = false
                        }) { Text(stringResource(MR.strings.action_save)) }
                        TextButton(onClick = { renaming = false }) { Text(stringResource(MR.strings.action_cancel)) }
                    }
                }
                Text(
                    pluralStringResource(MR.plurals.social_group_members, group.members.size, group.members.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                group.members.forEach { member ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).background(ProfileStyle.accent(member.accent), CircleShape))
                        Text(
                            if (member.uid == me) stringResource(MR.strings.reader_social_you) else member.name,
                            Modifier.padding(start = 8.dp).weight(1f),
                        )
                        if (member.uid == group.ownerUid) {
                            Text(
                                stringResource(MR.strings.social_group_owner),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (group.ownerUid == me) {
                            TextButton(enabled = !busy, onClick = {
                                onIntent(FriendsIntent.RemoveFromGroup(member.uid))
                            }) {
                                Text(stringResource(MR.strings.social_group_remove))
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (onReadTogether != null) {
                    TextButton(enabled = !busy, onClick = onReadTogether) {
                        Text(stringResource(MR.strings.social_read_together))
                    }
                }
                TextButton(
                    enabled = !busy && addable.isNotEmpty() && group.members.size < GROUP_PICK_MAX + 1,
                    onClick = { adding = true },
                ) { Text(stringResource(MR.strings.social_group_add)) }
                TextButton(enabled = !busy, onClick = { renaming = true }) {
                    Text(stringResource(MR.strings.social_group_rename))
                }
                TextButton(enabled = !busy, onClick = { leaving = true }) {
                    Text(stringResource(MR.strings.social_group_leave), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
}

/** My level: title, progress to the next level and lifetime counters. */
@Composable
fun XpCard(xp: SocialXp?) {
    val total = xp?.total ?: 0
    val level = XpLevels.levelFor(total)
    val (into, size) = XpLevels.progress(total)
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(level)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(XpLevels.title(level)), style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(
                progress = { if (size > 0) into.toFloat() / size else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(MR.strings.social_xp_progress, into, size, level + 1),
                style = MaterialTheme.typography.bodySmall,
            )
            if (xp != null) {
                Text(
                    stringResource(MR.strings.social_xp_stats, xp.chapters, xp.together, xp.bestStreak),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(MR.strings.social_xp_week, xp.weekXp(xpWeek()), xp.streak, xp.freezes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // Season badges (season_yyyy_mm) are listed on the stats page, not in this count.
                    stringResource(
                        MR.strings.social_badges_count,
                        Badge.entries.count { it.id in xp.badges },
                        Badge.entries.size,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                BadgeRow(xp.badges, showLocked = true)
            }
            Text(
                stringResource(MR.strings.social_xp_how),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Achievement icons; tapping one explains it. With [showLocked], locked ones appear greyed out so there's
 * something to aim for.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BadgeRow(badges: Map<String, Long>, showLocked: Boolean = false) {
    var open by remember { mutableStateOf<Badge?>(null) }
    val shown = Badge.entries.filter { showLocked || it.id in badges }
    if (shown.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { badge ->
            val unlocked = badge.id in badges
            Surface(
                onClick = { open = badge },
                shape = CircleShape,
                color = if (unlocked) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(
                    badge.icon,
                    modifier = Modifier.padding(8.dp).alpha(if (unlocked) 1f else 0.3f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
    open?.let { badge ->
        val at = badges[badge.id]
        AlertDialog(
            onDismissRequest = { open = null },
            icon = { Text(badge.icon, style = MaterialTheme.typography.headlineMedium) },
            title = { Text(stringResource(badge.title)) },
            text = {
                Column {
                    Text(stringResource(badge.description))
                    Text(
                        if (at != null) {
                            stringResource(MR.strings.badge_unlocked_at, relativeTimeSpanString(at))
                        } else {
                            stringResource(MR.strings.badge_locked)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(MR.strings.action_close)) } },
        )
    }
}

/** "Lv 7" in a small pill. */
@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            stringResource(MR.strings.social_level_short, level),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

private const val MAX_GROUP_MESSAGE = 500

/** Friends I can pick at once: a group is me plus up to 19 others. */
private const val GROUP_PICK_MAX = 19
