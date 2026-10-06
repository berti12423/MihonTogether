package mihon.feature.social

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import mihon.icons.materialsymbols.roundedfilled.Favorite
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

sealed interface FriendsIntent {
    data class SignIn(val email: String, val password: String, val register: Boolean) : FriendsIntent
    data class ResetPassword(val email: String) : FriendsIntent
    data object VerifyEmail : FriendsIntent
    data object RefreshAccount : FriendsIntent
    data object SignOut : FriendsIntent
    data class SaveProfile(val handle: String, val displayName: String) : FriendsIntent
    data class RequestFriend(val handle: String) : FriendsIntent

    /** An invite's handle (link, friend code or clipboard): shows that profile with a one-tap request. */
    data class LookupInvite(val handle: String) : FriendsIntent
    data object SendInvite : FriendsIntent
    data object DismissInvite : FriendsIntent
    data class FriendAction(val action: String, val uid: String) : FriendsIntent
    data class ChangeSharing(val settings: SocialSharing, val hideKey: String? = null) : FriendsIntent
    data class ToggleFavorite(val key: String) : FriendsIntent
    data object PublishLibrary : FriendsIntent
    data class SelectFriend(val uid: String?) : FriendsIntent
    data class JoinRoom(val room: SocialRoom) : FriendsIntent
    data class DropIn(val card: OpenRoomCard) : FriendsIntent
    data class OpenRoom(val room: SocialRoom) : FriendsIntent
    data class CloseRoom(val room: SocialRoom) : FriendsIntent
    data object Refresh : FriendsIntent
    data class RestoreHidden(val key: String) : FriendsIntent
    data class DeleteAccount(val password: String) : FriendsIntent
    data object CheckConnection : FriendsIntent
    data class SaveStyle(val bio: String, val accent: Int) : FriendsIntent
    data class PickMedia(val kind: String, val uri: Uri) : FriendsIntent
    data class RemoveMedia(val kind: String) : FriendsIntent
    data class DismissRankingNotice(val optOut: Boolean) : FriendsIntent
    data class OpenGrid(val kind: String) : FriendsIntent
    data class LoadGrid(val all: Boolean) : FriendsIntent
    data object CloseGrid : FriendsIntent
    data class ShareCovers(val on: Boolean) : FriendsIntent
    data class OpenChat(val uid: String) : FriendsIntent
    data class Nudge(val uid: String) : FriendsIntent
    data class AskToRead(val uid: String) : FriendsIntent
    data object InstallRoomSource : FriendsIntent
    data object TrustRoomSource : FriendsIntent
    data class AcceptJoin(val uid: String) : FriendsIntent
    data class DeclineJoin(val uid: String) : FriendsIntent
    data object CloseChat : FriendsIntent
    data class SendMessage(val text: String, val replyTo: Long? = null) : FriendsIntent

    /** Toggle my reaction to a message in the open chat or group. */
    data class React(val seq: Long, val emoji: String) : FriendsIntent
    data class OpenGroup(val groupId: String) : FriendsIntent

    /** Send a title card to the open chat or group. */
    data class Recommend(val content: SocialContent) : FriendsIntent
    data object CloseGroup : FriendsIntent
    data class SendGroupMessage(val text: String, val replyTo: Long? = null) : FriendsIntent
    data class CreateGroup(val name: String, val uids: List<String>) : FriendsIntent
    data class AddToGroup(val uids: List<String>) : FriendsIntent
    data class RemoveFromGroup(val uid: String) : FriendsIntent
    data class RenameGroup(val name: String) : FriendsIntent
    data object LeaveGroup : FriendsIntent
    data class SelectTab(val tab: FriendsTab) : FriendsIntent

    /** Open my copy of a title and start a room with these friends once a chapter has loaded. */
    data class ReadTogether(val contentKey: String, val uids: List<String>) : FriendsIntent

    /** Same, with my friends in the open group chat. */
    data class GroupReadTogether(val contentKey: String) : FriendsIntent

    /** "I'd read this with you" on a friend's title. */
    data class ToggleReadWish(val otherUid: String, val content: SocialContent) : FriendsIntent
}

class FriendsScreen(
    private val prefillHandle: String? = null,
    /** Where a notification leads (chat, group, room, requests); applied once. */
    private val route: FriendsRoute? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<FriendsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(viewModel, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.observe() }
        }
        var routed by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(route) {
            if (route != null && !routed) {
                routed = true
                viewModel.openRoute(route)
            }
        }
        // Invites: an opened invite link shows the inviter's card once; with no friends yet, the clipboard is checked
        // once ever for a friend code copied on the web invite page before installing (no install referrer).
        val signedIn = state.account != null && state.profile != null
        var inviteOpened by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(signedIn, prefillHandle) {
            if (signedIn && prefillHandle != null && !inviteOpened) {
                inviteOpened = true
                viewModel.lookupInvite(prefillHandle)
            }
        }
        val noFriendsYet = signedIn && state.friendsReady && state.friends.isEmpty() && state.outgoing.isEmpty()
        LaunchedEffect(noFriendsYet) {
            if (noFriendsYet) viewModel.checkClipboardInvite()
        }
        val context = LocalContext.current
        LaunchedEffect(state.sourceJump) {
            val (sourceId, title) = state.sourceJump ?: return@LaunchedEffect
            viewModel.consumeSourceJump()
            navigator.push(BrowseSourceScreen(sourceId, title))
        }
        LaunchedEffect(state.roomJump) {
            val jump = state.roomJump ?: return@LaunchedEffect
            viewModel.consumeRoomJump()
            if (jump.chapterId != null) {
                context.startActivity(ReaderActivity.newIntent(context, jump.mangaId, jump.chapterId))
            } else {
                navigator.push(MangaScreen(jump.mangaId))
            }
        }
        FriendsContent(
            state = state,
            onIntent = viewModel::dispatch,
            onBack = navigator::pop,
            onOpenManga = { navigator.push(MangaScreen(it)) },
            onSearch = { navigator.push(GlobalSearchScreen(it)) },
            onBrowseSource = { sourceId, title -> navigator.push(BrowseSourceScreen(sourceId, title)) },
            loadMedia = viewModel::loadMedia,
            prefillHandle = prefillHandle,
            notificationPreference = viewModel::notificationPreference,
        )
    }
}

/** What "Read together" still needs a title for: a friend (their current title first, if I have it) or the group. */
private sealed interface ReadTogetherTarget {
    data class Friend(val uid: String, val pinned: SocialContent?) : ReadTogetherTarget
    data object Group : ReadTogetherTarget
}

// Rendering and local form mechanics can be previewed without Firebase or the app graph.
@Composable
fun FriendsContent(
    state: FriendsState,
    onIntent: (FriendsIntent) -> Unit,
    onBack: () -> Unit,
    onOpenManga: (Long) -> Unit = {},
    onSearch: (String) -> Unit = {},
    loadMedia: ProfileMediaLoader = { _, _, _ -> null },
    prefillHandle: String? = null,
    notificationPreference: (String) -> Preference<Boolean>? = { null },
    onBrowseSource: (Long, String) -> Unit = { _, title -> onSearch(title) },
) {
    // Every title picker below (chats, groups, Read together, favorites) shows my library's own covers.
    CompositionLocalProvider(LocalTitleCovers provides state.covers) {
        FriendsBody(
            state,
            onIntent,
            onBack,
            onOpenManga,
            onSearch,
            loadMedia,
            prefillHandle,
            notificationPreference,
            onBrowseSource,
        )
    }
}

@Composable
private fun FriendsBody(
    state: FriendsState,
    onIntent: (FriendsIntent) -> Unit,
    onBack: () -> Unit,
    onOpenManga: (Long) -> Unit,
    onSearch: (String) -> Unit,
    loadMedia: ProfileMediaLoader,
    prefillHandle: String?,
    notificationPreference: (String) -> Preference<Boolean>?,
    onBrowseSource: (Long, String) -> Unit,
) {
    var picked by remember { mutableStateOf<SocialContent?>(null) }
    var friendsFor by remember { mutableStateOf<SocialContent?>(null) }
    var titleFor by remember { mutableStateOf<ReadTogetherTarget?>(null) }
    picked?.let { content ->
        val localId = state.localIndex[content.contentKey]
        // A friend's title (profile or grid): offer the private "I'd read this with you" wish.
        val owner = state.selectedFriend?.profile?.uid?.takeIf { state.chat == null && state.group == null }
        SharedTitleDialog(
            content = content,
            localId = localId,
            onOpenManga = onOpenManga,
            // Their source when I have it installed (matched by its hashed key), otherwise all my sources.
            onSearch = { title ->
                when (val guide = RoomSourceGuide.locate(content, state.installedSources)) {
                    is RoomSourceGuide.Installed -> onBrowseSource(guide.sourceId, title)
                    else -> onSearch(title)
                }
            },
            onReadTogether = if (localId != null && state.friends.isNotEmpty()) {
                { friendsFor = content }
            } else {
                null
            },
            wished = owner?.let { uid ->
                state.readWishes.any { it.otherUid == uid && it.contentKey == content.contentKey }
            },
            onToggleWish = { owner?.let { onIntent(FriendsIntent.ToggleReadWish(it, content)) } },
        ) { picked = null }
    }
    friendsFor?.let { content ->
        GroupPickerDialog(
            title = stringResource(MR.strings.social_read_together_pick_friends, content.title),
            friends = state.friends,
            askName = false,
            confirm = stringResource(MR.strings.social_read_together),
            max = mihon.feature.social.reader.PendingRoomInvite.MAX_INVITES,
            initial = listOfNotNull(state.selectedFriend?.profile?.uid ?: state.chat?.otherUid),
            onConfirm = { _, uids ->
                onIntent(FriendsIntent.ReadTogether(content.contentKey, uids))
                friendsFor = null
            },
            onDismiss = { friendsFor = null },
        )
    }
    titleFor?.let { target ->
        TitlePickerDialog(
            titles = state.library,
            title = stringResource(MR.strings.social_read_together_pick_title),
            pinned = (target as? ReadTogetherTarget.Friend)?.pinned,
            onPick = { content ->
                onIntent(
                    when (target) {
                        is ReadTogetherTarget.Friend -> FriendsIntent.ReadTogether(
                            content.contentKey,
                            listOf(target.uid),
                        )
                        ReadTogetherTarget.Group -> FriendsIntent.GroupReadTogether(content.contentKey)
                    },
                )
                titleFor = null
            },
        ) { titleFor = null }
    }
    val grid = state.grid?.takeIf { state.selectedFriend?.profile?.uid == it.uid }
    val chat = state.chat
    val chatFriend = chat?.let { open -> state.friends.firstOrNull { it.uid == open.otherUid } }
    val openGroup = state.group
    var groupInfo by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    if (newGroup) {
        GroupPickerDialog(
            title = stringResource(MR.strings.social_group_new),
            friends = state.friends,
            askName = true,
            confirm = stringResource(MR.strings.social_group_create),
            onConfirm = { name, uids ->
                onIntent(FriendsIntent.CreateGroup(name, uids))
                newGroup = false
            },
            onDismiss = { newGroup = false },
        )
    }
    val infoGroup = openGroup?.group
    if (groupInfo && infoGroup != null && state.account != null) {
        val me = state.account.uid
        val friendIds = state.friends.mapTo(mutableSetOf()) { it.uid }
        // Needs a title of mine and at least one friend in the group to invite.
        val canRead = state.library.isNotEmpty() && infoGroup.members.any { it.uid != me && it.uid in friendIds }
        GroupInfoDialog(
            infoGroup,
            me,
            state.friends,
            state.busy,
            onIntent,
            onReadTogether = if (canRead) {
                {
                    groupInfo = false
                    titleFor = ReadTogetherTarget.Group
                }
            } else {
                null
            },
        ) { groupInfo = false }
    }
    val goBack = {
        when {
            grid != null -> onIntent(FriendsIntent.CloseGrid)
            openGroup != null -> onIntent(FriendsIntent.CloseGroup)
            chat != null -> onIntent(FriendsIntent.CloseChat)
            state.selectedFriend != null -> onIntent(FriendsIntent.SelectFriend(null))
            else -> onBack()
        }
    }
    BackHandler(enabled = grid != null || chat != null || openGroup != null || state.selectedFriend != null) {
        goBack()
    }
    Scaffold(
        topBar = {
            val name = state.selectedFriend?.profile?.displayName
            AppBar(
                title = when {
                    openGroup != null ->
                        openGroup.group?.name
                            ?: state.groups.firstOrNull { it.groupId == openGroup.groupId }?.name
                            ?: stringResource(MR.strings.social_messages)
                    chat != null -> chatFriend?.displayName ?: stringResource(MR.strings.social_messages)
                    grid != null && name != null -> stringResource(
                        if (grid.kind ==
                            "library"
                        ) {
                            MR.strings.social_grid_title_library
                        } else {
                            MR.strings.social_grid_title_favorites
                        },
                        name,
                    )
                    name != null -> name
                    else -> stringResource(MR.strings.social_friends)
                },
                navigateUp = goBack,
                actions = {
                    if (openGroup?.group != null) {
                        TextButton(onClick = { groupInfo = true }) {
                            Text(stringResource(MR.strings.social_group_info))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (grid != null) {
            FriendLibraryGrid(
                grid = grid,
                localIndex = state.localIndex,
                contentPadding = padding,
                onLoad = { all -> onIntent(FriendsIntent.LoadGrid(all)) },
                onPick = { picked = it },
            )
            return@Scaffold
        }
        if (openGroup != null && state.account != null) {
            ShownOnScreen(SocialForeground.Target.Group(openGroup.groupId))
            GroupChatView(
                openGroup,
                state.account.uid,
                padding,
                state.blocks.toSet(),
                titles = state.library,
                onPickTitle = { picked = it },
                onRecommend = { onIntent(FriendsIntent.Recommend(it)) },
                onReact = { seq, emoji -> onIntent(FriendsIntent.React(seq, emoji)) },
            ) { text, replyTo -> onIntent(FriendsIntent.SendGroupMessage(text, replyTo)) }
            return@Scaffold
        }
        if (chat != null && state.account != null) {
            ShownOnScreen(SocialForeground.Target.Chat(chat.otherUid))
            ChatView(
                chat,
                state.account.uid,
                padding,
                titles = state.library,
                onPickTitle = { picked = it },
                onRecommend = { onIntent(FriendsIntent.Recommend(it)) },
                localIndex = state.localIndex,
                onReadTogether = { content ->
                    if (state.localIndex[content.contentKey] != null) {
                        onIntent(FriendsIntent.ReadTogether(content.contentKey, listOf(chat.otherUid)))
                    } else {
                        picked = content
                    }
                },
                otherName = chatFriend?.displayName.orEmpty(),
                onReact = { seq, emoji -> onIntent(FriendsIntent.React(seq, emoji)) },
            ) { text, replyTo -> onIntent(FriendsIntent.SendMessage(text, replyTo)) }
            return@Scaffold
        }
        // Signed in, on the main list: Friends / Chats / Me tabs. Signed out or on a profile: one list.
        val tabbed = state.enabled && state.account != null && state.selectedFriend == null
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tabbed) {
                val friendIds = state.friends.mapTo(mutableSetOf()) { it.uid }
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                    FriendsTab.entries.forEach { tab ->
                        val badge = when (tab) {
                            FriendsTab.FRIENDS -> FriendsTabs.pendingFriends(state)
                            FriendsTab.CHATS -> FriendsTabs.unreadChats(state.chats, state.groups, friendIds)
                            FriendsTab.ME -> state.readWishes.count { it.matched }
                        }
                        Tab(
                            selected = state.tab == tab,
                            onClick = { onIntent(FriendsIntent.SelectTab(tab)) },
                            text = {
                                TabText(
                                    text = stringResource(
                                        when (tab) {
                                            FriendsTab.FRIENDS -> MR.strings.social_tab_friends
                                            FriendsTab.CHATS -> MR.strings.social_tab_chats
                                            FriendsTab.ME -> MR.strings.social_tab_me
                                        },
                                    ),
                                    badgeCount = badge.takeIf { it > 0 },
                                )
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            PullRefresh(
                refreshing = state.friendsLoading,
                enabled = state.account != null,
                onRefresh = {
                    val friend = state.selectedFriend
                    if (friend != null) {
                        onIntent(FriendsIntent.SelectFriend(friend.profile.uid))
                    } else {
                        onIntent(FriendsIntent.Refresh)
                    }
                },
            ) {
                // Activity is a 2-minute lease; re-check the clock so "Reading now" drops once it lapses.
                val now by produceState(System.currentTimeMillis()) {
                    while (true) {
                        delay(15_000)
                        value = System.currentTimeMillis()
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    feedback(state.feedback)
                    if (!state.enabled) {
                        item { Text(stringResource(MR.strings.social_unconfigured)) }
                        return@LazyColumn
                    }
                    if (state.account == null) {
                        item { Text(stringResource(MR.strings.social_private_default)) }
                        item { AccountForm(state.busy, onIntent) }
                        return@LazyColumn
                    }
                    val friend = state.selectedFriend
                    if (friend != null) {
                        friendDetail(
                            friend,
                            state,
                            onIntent,
                            loadMedia,
                            onPick = { picked = it },
                            onReadTogether = if (state.library.isNotEmpty()) {
                                {
                                    val pinned = friend.activity?.takeIf { state.localIndex[it.contentKey] != null }
                                    titleFor = ReadTogetherTarget.Friend(friend.profile.uid, pinned)
                                }
                            } else {
                                null
                            },
                        )
                        return@LazyColumn
                    }
                    when (state.tab) {
                        FriendsTab.FRIENDS -> friendsTab(
                            state,
                            now,
                            onIntent,
                            loadMedia,
                            prefillHandle,
                            onSearch,
                            onBrowseSource,
                        )
                        FriendsTab.CHATS -> chatsTab(state, onIntent, loadMedia) { newGroup = true }
                        FriendsTab.ME -> meTab(
                            state,
                            onIntent,
                            loadMedia,
                            notificationPreference,
                            onReadTogether = { content, uid ->
                                onIntent(FriendsIntent.ReadTogether(content.contentKey, listOf(uid)))
                            },
                            onPick = { picked = it },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.feedback(feedback: SocialFeedback) {
    when (feedback) {
        SocialFeedback.NONE -> Unit
        SocialFeedback.ERROR -> item {
            Text(stringResource(MR.strings.social_error), color = MaterialTheme.colorScheme.error)
        }
        SocialFeedback.SUCCESS -> item { Text(stringResource(MR.strings.social_success)) }
        SocialFeedback.EMAIL_SENT -> item { Text(stringResource(MR.strings.social_email_sent)) }
        SocialFeedback.PENDING_RETRACTION -> item {
            Text(stringResource(MR.strings.social_retraction_pending))
        }
        SocialFeedback.ACCOUNT_DELETED -> item { Text(stringResource(MR.strings.social_account_deleted)) }
        SocialFeedback.MEDIA_REJECTED -> item {
            Text(stringResource(MR.strings.social_media_rejected), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Friends tab: what needs attention first (rooms, requests), then friends, ranking and adding friends. */
private fun LazyListScope.friendsTab(
    state: FriendsState,
    now: Long,
    onIntent: (FriendsIntent) -> Unit,
    loadMedia: ProfileMediaLoader,
    prefillHandle: String?,
    onSearch: (String) -> Unit,
    onBrowseSource: (Long, String) -> Unit,
) {
    val account = state.account ?: return
    if (state.rankingNotice) item { RankingNotice(onIntent) }
    if (state.rooms.isNotEmpty()) item { SectionTitle(stringResource(MR.strings.social_reading_rooms)) }
    items(state.rooms, key = { "room_${it.id}" }) { room ->
        RoomCard(room, state, onIntent, onSearch, onBrowseSource)
    }
    val openRooms = FriendList.openRooms(state.friendStatuses, state.rooms.mapTo(mutableSetOf()) { it.id }, now)
    if (openRooms.isNotEmpty()) item { SectionTitle(stringResource(MR.strings.social_open_rooms)) }
    items(openRooms, key = { "open_${it.roomId}" }) { card ->
        OpenRoomCardView(card, state, onIntent)
    }
    if (state.joinRequests.isNotEmpty()) {
        item { SectionTitle(stringResource(MR.strings.social_join_requests)) }
    }
    val hosting = state.rooms.any { it.hostUid == account.uid && it.state != "closed" && !it.full }
    items(state.joinRequests, key = { "join_${it.requesterUid}" }) { request ->
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    stringResource(MR.strings.reader_social_join_request, request.name.ifBlank { request.handle }),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!hosting) {
                    Text(
                        stringResource(MR.strings.social_join_open_chapter),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row {
                    if (hosting) {
                        Button(enabled = !state.busy, onClick = {
                            onIntent(FriendsIntent.AcceptJoin(request.requesterUid))
                        }) {
                            Text(stringResource(MR.strings.reader_social_join_accept))
                        }
                    }
                    TextButton(enabled = !state.busy, onClick = {
                        onIntent(FriendsIntent.DeclineJoin(request.requesterUid))
                    }) {
                        Text(stringResource(MR.strings.reader_social_join_decline))
                    }
                }
            }
        }
    }
    if (state.incoming.isNotEmpty()) {
        item(key = "incoming_title") { SectionTitle(stringResource(MR.strings.social_incoming_requests)) }
    }
    items(state.incoming, key = { "incoming_${it.id}" }) { request ->
        RequestRow(request.senderUid, request.senderHandle, request.senderDisplayName, state.busy, {
            onIntent(FriendsIntent.FriendAction("accept", request.senderUid))
        }, { onIntent(FriendsIntent.FriendAction("reject", request.senderUid)) }, incoming = true)
    }
    state.invite?.let { invite -> item(key = "invite") { InviteCard(invite, state.busy, onIntent, loadMedia) } }
    item { SectionTitle(stringResource(MR.strings.social_friends)) }
    if (state.friends.isEmpty()) {
        val empty = if (state.friendsLoading) MR.strings.social_loading_friends else MR.strings.social_no_friends
        item { Text(stringResource(empty)) }
        if (state.invite == null && state.friendsReady) {
            item(key = "friend_code") { FriendCodeField(state.busy || state.profile == null, onIntent) }
        }
    }
    items(FriendList.sort(state.friends, state.friendStatuses, now), key = { "friend_${it.uid}" }) { profile ->
        val nudgeable = profile.uid in state.streakAtRisk &&
            !SocialNudges.isCoolingDown(state.nudgedAt[profile.uid], now)
        FriendRow(profile, state.friendStatuses[profile.uid], now, onIntent, loadMedia, nudgeable)
    }
    item { RankingCard(state, onIntent, loadMedia) }
    item { FriendRequestForm(state.busy || state.profile == null, onIntent, prefillHandle) }
    state.profile?.let { profile -> item { ShareInviteButton(profile.handle) } }
    if (state.outgoing.isNotEmpty()) {
        item { SectionTitle(stringResource(MR.strings.social_outgoing_requests)) }
    }
    items(state.outgoing, key = { "outgoing_${it.id}" }) { request ->
        RequestRow(
            uid = request.recipientUid,
            handle = request.recipientHandle,
            displayName = request.recipientDisplayName,
            busy = state.busy,
            onAccept = {},
            onReject = { onIntent(FriendsIntent.FriendAction("cancel", request.recipientUid)) },
            incoming = false,
        )
    }
}

/** Chats tab: group chats and conversations with friends, unread first in each row's badge. */
private fun LazyListScope.chatsTab(
    state: FriendsState,
    onIntent: (FriendsIntent) -> Unit,
    loadMedia: ProfileMediaLoader,
    onNewGroup: () -> Unit,
) {
    val account = state.account ?: return
    val friendsById = state.friends.associateBy { it.uid }
    val chats = state.chats.filter { it.otherUid in friendsById }
    item(key = "messages_title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(stringResource(MR.strings.social_messages)) }
            TextButton(enabled = state.friends.isNotEmpty() && !state.busy, onClick = onNewGroup) {
                Text(stringResource(MR.strings.social_group_new))
            }
        }
    }
    if (chats.isEmpty() && state.groups.isEmpty()) {
        item { Text(stringResource(MR.strings.social_chats_empty), style = MaterialTheme.typography.bodySmall) }
    }
    items(state.groups, key = { "group_${it.groupId}" }) { summary ->
        GroupRow(summary) { onIntent(FriendsIntent.OpenGroup(summary.groupId)) }
    }
    items(chats, key = { "chat_${it.otherUid}" }) { summary ->
        ChatRow(summary, friendsById.getValue(summary.otherUid), account.uid, loadMedia) {
            onIntent(FriendsIntent.OpenChat(summary.otherUid))
        }
    }
}

/** Me tab: level, account and profile, notifications, privacy, my titles, read wishes, blocked users. */
private fun LazyListScope.meTab(
    state: FriendsState,
    onIntent: (FriendsIntent) -> Unit,
    loadMedia: ProfileMediaLoader,
    notificationPreference: (String) -> Preference<Boolean>?,
    onReadTogether: (SocialContent, String) -> Unit,
    onPick: (SocialContent) -> Unit,
) {
    val account = state.account ?: return
    item { XpCard(state.xp) }
    item {
        Text(account.email.orEmpty())
        if (!account.verified) {
            Text(stringResource(MR.strings.social_verification_needed))
            Row {
                TextButton(enabled = !state.busy, onClick = { onIntent(FriendsIntent.VerifyEmail) }) {
                    Text(stringResource(MR.strings.social_verify))
                }
                TextButton(enabled = !state.busy, onClick = { onIntent(FriendsIntent.RefreshAccount) }) {
                    Text(stringResource(MR.strings.social_refresh))
                }
            }
        }
        TextButton(enabled = !state.busy, onClick = { onIntent(FriendsIntent.SignOut) }) {
            Text(stringResource(MR.strings.social_sign_out))
        }
    }
    item { ProfileForm(state.profile, state.busy, onIntent) }
    state.profile?.let { profile -> item { ProfileStyleEditor(profile, state.busy, onIntent, loadMedia) } }
    item { NotificationSettings(notificationPreference) }
    item { SectionTitle(stringResource(MR.strings.social_privacy)) }
    item { Text(stringResource(MR.strings.social_share_summary)) }
    item { SharingControls(state.sharing, state.busy, onIntent) }
    ownTitles(state, onIntent)
    readWishes(state, onIntent, onReadTogether, onPick)
    if (state.blocks.isNotEmpty()) item { SectionTitle(stringResource(MR.strings.social_blocked)) }
    items(state.blocks, key = { "block_$it" }) { uid ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(uid.take(12), Modifier.weight(1f))
            TextButton(enabled = !state.busy, onClick = { onIntent(FriendsIntent.FriendAction("unblock", uid)) }) {
                Text(stringResource(MR.strings.social_unblock))
            }
        }
    }
    item {
        // Support: which Firebase service fails (e.g. only on mobile data).
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(enabled = !state.checkingConnection, onClick = { onIntent(FriendsIntent.CheckConnection) }) {
                Text(stringResource(MR.strings.social_check_connection))
            }
            if (state.checkingConnection) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.connectionCheck.forEach { probe ->
                val service = stringResource(
                    when (probe.service) {
                        ConnectionProbe.Service.AUTH -> MR.strings.social_check_auth
                        ConnectionProbe.Service.FIRESTORE -> MR.strings.social_check_firestore
                        ConnectionProbe.Service.FUNCTIONS -> MR.strings.social_check_functions
                        ConnectionProbe.Service.DATABASE -> MR.strings.social_check_database
                        ConnectionProbe.Service.STORAGE -> MR.strings.social_check_storage
                    },
                )
                val result = when (probe.outcome) {
                    ConnectionProbe.Outcome.OK -> stringResource(MR.strings.social_check_ok, probe.detail)
                    ConnectionProbe.Outcome.TIMEOUT -> stringResource(MR.strings.social_check_timeout)
                    ConnectionProbe.Outcome.ERROR -> probe.detail
                }
                Text("$service: $result", style = MaterialTheme.typography.bodySmall)
            }
            if (ConnectionDiagnosis.ipv6Suspected(state.connectionCheck)) {
                Card {
                    Text(
                        stringResource(MR.strings.social_check_ipv6_hint),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    item { DeleteAccountButton(state.busy, onIntent) }
    item {
        Text(
            stringResource(MR.strings.about_together_credit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** My library as friends may see it: publish, covers, then a compact searchable list (favorite / hide per title). */
private fun LazyListScope.ownTitles(state: FriendsState, onIntent: (FriendsIntent) -> Unit) {
    item { SectionTitle(stringResource(MR.strings.social_own_library)) }
    item { Text(stringResource(MR.strings.social_favorites_summary)) }
    item {
        Button(
            enabled = !state.busy && (state.sharing.library == "friends" || state.sharing.favorites == "friends"),
            onClick = { onIntent(FriendsIntent.PublishLibrary) },
        ) { Text(stringResource(MR.strings.social_publish)) }
        Text(stringResource(MR.strings.social_projection_summary))
        PrivacySwitch(stringResource(MR.strings.social_share_covers), state.shareCovers, !state.busy) {
            onIntent(FriendsIntent.ShareCovers(it))
        }
        Text(
            stringResource(MR.strings.social_share_covers_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item(key = "own_titles") { OwnTitlesSection(state, onIntent) }
    // Hidden titles no longer in the local library can still be restored.
    val orphanHidden = state.hiddenKeys - state.library.map { it.contentKey }.toSet()
    items(orphanHidden.toList(), key = { "hidden_$it" }) { key ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(MR.strings.social_hidden_unknown), Modifier.weight(1f))
            TextButton(enabled = !state.busy, onClick = { onIntent(FriendsIntent.RestoreHidden(key)) }) {
                Text(stringResource(MR.strings.social_restore))
            }
        }
    }
    item { Text(stringResource(MR.strings.social_unsupported_sources)) }
}

@Composable
private fun OwnTitlesSection(state: FriendsState, onIntent: (FriendsIntent) -> Unit) {
    var ownQuery by rememberSaveable { mutableStateOf("") }
    var ownFilter by rememberSaveable { mutableStateOf(OwnTitles.Filter.ALL) }
    var ownExpanded by rememberSaveable { mutableStateOf(false) }
    var choosingFavorites by rememberSaveable { mutableStateOf(false) }
    if (choosingFavorites) FavoritesPicker(state, onIntent) { choosingFavorites = false }
    val filtered = OwnTitles.filter(state.library, ownQuery, ownFilter, state.favoriteKeys, state.hiddenKeys)
    val shown = if (ownExpanded || ownQuery.isNotBlank()) filtered else filtered.take(OWN_PREVIEW)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OwnTitlesHeader(
            total = state.library.size,
            favorites = state.library.count { it.contentKey in state.favoriteKeys },
            hidden = state.library.count { it.contentKey in state.hiddenKeys },
            query = ownQuery,
            onQuery = { ownQuery = it },
            filter = ownFilter,
            onFilter = { ownFilter = it },
        )
        if (ownFilter == OwnTitles.Filter.FAVORITES && state.library.isNotEmpty()) {
            OutlinedButton(onClick = { choosingFavorites = true }) {
                Text(stringResource(MR.strings.social_favorites_pick))
            }
        }
        shown.forEach { content ->
            OwnTitleRow(
                content = content,
                favorite = content.contentKey in state.favoriteKeys,
                hidden = content.contentKey in state.hiddenKeys,
                busy = state.busy,
                onIntent = onIntent,
                sharing = state.sharing,
            )
        }
        if (filtered.size > shown.size || (ownExpanded && filtered.size > OWN_PREVIEW)) {
            TextButton(onClick = { ownExpanded = !ownExpanded }) {
                Text(
                    if (ownExpanded) {
                        stringResource(MR.strings.social_own_show_less)
                    } else {
                        stringResource(MR.strings.social_own_show_all, filtered.size)
                    },
                )
            }
        }
    }
}

/**
 * Social favorites by cover: my library titles (hidden ones aren't offered, like the heart in the list); a tap adds or
 * removes a favorite through [FriendsIntent.ToggleFavorite], which keeps the usual publishing rules.
 */
@Composable
private fun FavoritesPicker(state: FriendsState, onIntent: (FriendsIntent) -> Unit, onDismiss: () -> Unit) {
    val titles = remember(state.library, state.hiddenKeys) {
        state.library.filterNot { it.contentKey in state.hiddenKeys }
    }
    TitlePickerDialog(
        titles = titles,
        title = stringResource(MR.strings.social_favorites_pick_title),
        selected = state.favoriteKeys,
        busy = state.busy,
        header = {
            Text(
                stringResource(
                    MR.strings.social_favorites_pick_hint,
                    titles.count { it.contentKey in state.favoriteKeys },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onPick = { onIntent(FriendsIntent.ToggleFavorite(it.contentKey)) },
        onDismiss = onDismiss,
    )
}

/** "Let's read this": my private wishes; matches (we both want it) are highlighted with a Read together button. */
private fun LazyListScope.readWishes(
    state: FriendsState,
    onIntent: (FriendsIntent) -> Unit,
    onReadTogether: (SocialContent, String) -> Unit,
    onPick: (SocialContent) -> Unit,
) {
    item(key = "wishes_title") {
        Column {
            SectionTitle(stringResource(MR.strings.social_wishes))
            Text(
                stringResource(MR.strings.social_wishes_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (state.readWishes.isEmpty()) {
        item { Text(stringResource(MR.strings.social_wishes_empty), style = MaterialTheme.typography.bodySmall) }
    }
    val names = state.friends.associate { it.uid to it.displayName }
    items(state.readWishes, key = { "wish_${it.otherUid}_${it.contentKey}" }) { wish ->
        val content = wish.content
        val inLibrary = content != null && state.localIndex[content.contentKey] != null
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    content?.title ?: stringResource(MR.strings.social_wish_unknown),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (wish.matched) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = content != null) { content?.let(onPick) },
                )
                val name = names[wish.otherUid] ?: "?"
                Text(
                    stringResource(
                        if (wish.matched) MR.strings.social_wish_matched else MR.strings.social_wish_open,
                        name,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    if (wish.matched && content != null && inLibrary && wish.otherUid in names) {
                        Button(enabled = !state.busy, onClick = { onReadTogether(content, wish.otherUid) }) {
                            Text(stringResource(MR.strings.social_read_together))
                        }
                    }
                    if (content != null) {
                        TextButton(enabled = !state.busy, onClick = {
                            onIntent(FriendsIntent.ToggleReadWish(wish.otherUid, content))
                        }) {
                            Text(stringResource(MR.strings.social_wish_remove))
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.friendDetail(
    friend: SocialFriendDetails,
    state: FriendsState,
    onIntent: (FriendsIntent) -> Unit,
    loadMedia: ProfileMediaLoader,
    onPick: (SocialContent) -> Unit,
    onReadTogether: (() -> Unit)?,
) {
    item { ProfileHeader(friend.profile, loadMedia) }
    item {
        Text(
            when (friend.online) {
                true -> stringResource(MR.strings.social_online)
                false -> stringResource(MR.strings.social_offline)
                null -> stringResource(MR.strings.social_status_private)
            },
        )
        friend.lastSeen?.let {
            Text(stringResource(MR.strings.social_last_seen_relative, relativeTimeSpanString(it)))
        }
        friend.activity?.let { Text(stringResource(MR.strings.social_current_reading, it.title)) }
        friend.xp?.let { xp ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(xp.level)
                Text(stringResource(XpLevels.title(xp.level)), Modifier.padding(start = 8.dp))
            }
            BadgeRow(xp.badges)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onIntent(FriendsIntent.OpenChat(friend.profile.uid)) }) {
                Text(stringResource(MR.strings.social_message_friend))
            }
            if (onReadTogether != null) {
                OutlinedButton(enabled = !state.busy, onClick = onReadTogether) {
                    Text(stringResource(MR.strings.social_read_together))
                }
            }
        }
        val nudged = SocialNudges.isCoolingDown(state.nudgedAt[friend.profile.uid], System.currentTimeMillis())
        OutlinedButton(enabled = !state.busy && !nudged, onClick = {
            onIntent(FriendsIntent.Nudge(friend.profile.uid))
        }) {
            Text(stringResource(if (nudged) MR.strings.social_nudged else MR.strings.social_nudge))
        }
        if (friend.activity != null) {
            TextButton(enabled = !state.busy, onClick = { onIntent(FriendsIntent.AskToRead(friend.profile.uid)) }) {
                Text(stringResource(MR.strings.social_ask_to_read))
            }
        }
        Row {
            TextButton(enabled = !state.busy, onClick = {
                onIntent(FriendsIntent.FriendAction("remove", friend.profile.uid))
            }) {
                Text(stringResource(MR.strings.social_remove))
            }
            TextButton(enabled = !state.busy, onClick = {
                onIntent(FriendsIntent.FriendAction("block", friend.profile.uid))
            }) {
                Text(stringResource(MR.strings.social_block))
            }
            TextButton(enabled = !state.busy, onClick = {
                onIntent(FriendsIntent.SelectFriend(friend.profile.uid))
            }) {
                Text(stringResource(MR.strings.social_refresh))
            }
        }
    }
    if (friend.sharing.feed == "friends") {
        item { SectionTitle(stringResource(MR.strings.social_recent)) }
        if (friend.feed.isEmpty()) item { Text(stringResource(MR.strings.social_feed_empty)) }
        items(friend.feed, key = { "friend_feed_${it.kind}_${it.content.contentKey}_${it.createdAt}" }) { entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (entry.kind) {
                        SocialFeedItem.KIND_CHAPTER_READ -> stringResource(
                            MR.strings.social_feed_chapter_read,
                            entry.chapterLabel.orEmpty(),
                            entry.content.title,
                        )
                        else -> stringResource(MR.strings.social_feed_favorite_added, entry.content.title)
                    },
                    Modifier.weight(1f),
                )
                Text(relativeTimeSpanString(entry.createdAt), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    for ((kind, title) in listOf("library" to MR.strings.social_library, "favorites" to MR.strings.social_favorites)) {
        val shared = (if (kind == "library") friend.sharing.library else friend.sharing.favorites) == "friends"
        item(key = "friend_$kind") {
            if (shared) {
                FriendLibraryPreview(
                    title = stringResource(title),
                    items = if (kind == "library") friend.library else friend.favorites,
                    count = if (kind == "library") friend.libraryCount else friend.favoritesCount,
                    localIndex = state.localIndex,
                    onSeeAll = { onIntent(FriendsIntent.OpenGrid(kind)) },
                    onPick = onPick,
                )
            } else {
                Column {
                    SectionTitle(stringResource(title))
                    Text(stringResource(MR.strings.social_not_shared))
                }
            }
        }
    }
    item(key = "friend_reviews") { SectionTitle(stringResource(MR.strings.social_reviews)) }
    when {
        !friend.sharing.reviews -> item { Text(stringResource(MR.strings.social_reviews_private)) }
        friend.reviews.isEmpty() -> item { Text(stringResource(MR.strings.social_reviews_empty)) }
        else -> items(friend.reviews, key = { "friend_review_${it.workKey}" }) { review ->
            FriendReviewRow(review) { onPick(review.content) }
        }
    }
}

/** One-time notice: the global ranking starts on, so say so once and offer the switch right here. */
@Composable
private fun RankingNotice(onIntent: (FriendsIntent) -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(MR.strings.social_global_notice_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(MR.strings.social_global_notice), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { onIntent(FriendsIntent.DismissRankingNotice(optOut = false)) }) {
                    Text(stringResource(MR.strings.social_global_notice_ok))
                }
                TextButton(onClick = { onIntent(FriendsIntent.DismissRankingNotice(optOut = true)) }) {
                    Text(stringResource(MR.strings.social_global_notice_off))
                }
            }
        }
    }
}

@Composable
private fun RankingCard(state: FriendsState, onIntent: (FriendsIntent) -> Unit, loadMedia: ProfileMediaLoader) {
    var tab by remember { mutableStateOf(RankingTab.FRIENDS) }
    val global = tab == RankingTab.GLOBAL
    val weekly = tab == RankingTab.WEEK
    val allTime = tab == RankingTab.ALL_TIME
    var card by remember { mutableStateOf<RankingEntry?>(null) }
    val rows = when (tab) {
        RankingTab.FRIENDS -> state.ranking
        RankingTab.GLOBAL -> state.globalRanking
        RankingTab.WEEK -> state.weeklyRanking
        RankingTab.ALL_TIME -> state.allTimeRanking
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(
                    if (weekly || allTime) MR.strings.social_ranking_title else MR.strings.social_ranking_today,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = tab == RankingTab.FRIENDS,
                    onClick = { tab = RankingTab.FRIENDS },
                    label = { Text(stringResource(MR.strings.social_ranking_friends)) },
                )
                FilterChip(
                    selected = global,
                    onClick = { tab = RankingTab.GLOBAL },
                    label = { Text(stringResource(MR.strings.social_ranking_global)) },
                )
                FilterChip(
                    selected = weekly,
                    onClick = { tab = RankingTab.WEEK },
                    label = { Text(stringResource(MR.strings.social_ranking_week)) },
                )
                FilterChip(
                    selected = allTime,
                    onClick = { tab = RankingTab.ALL_TIME },
                    label = { Text(stringResource(MR.strings.social_ranking_all_time)) },
                )
            }
            if (rows.isEmpty()) {
                Text(stringResource(MR.strings.social_ranking_empty), style = MaterialTheme.typography.bodySmall)
            }
            val you = stringResource(MR.strings.reader_social_you)
            val score: @Composable (RankingEntry) -> String = { entry ->
                when {
                    weekly -> stringResource(MR.strings.social_ranking_xp, entry.weekXp)
                    allTime -> stringResource(MR.strings.social_ranking_xp_chapters, entry.totalXp, entry.chapters)
                    else -> stringResource(MR.strings.social_ranking_chapters, entry.chapters)
                }
            }
            val open: (RankingEntry) -> Unit = { entry ->
                if (entry.friend) onIntent(FriendsIntent.SelectFriend(entry.uid)) else card = entry
            }
            if (rows.isNotEmpty()) {
                RankingPodium(
                    top = rows.take(3),
                    profileOf = { it.profile(state) },
                    score = score,
                    meLabel = you,
                    load = loadMedia,
                    onClick = open,
                )
            }
            rows.drop(3).forEachIndexed { index, entry ->
                val detail = listOfNotNull(
                    entry.handle?.let { "@$it" },
                    stringResource(MR.strings.social_streak, entry.streak).takeIf { entry.streak >= 2 && !allTime },
                ).joinToString("  ").ifBlank { null }
                RankingRow(
                    position = index + 4,
                    entry = entry,
                    profile = entry.profile(state),
                    name = if (entry.me) you else entry.name,
                    detail = detail,
                    score = score(entry),
                    load = loadMedia,
                    onClick = if (entry.me) null else ({ open(entry) }),
                )
            }
            val hint = when {
                (global || allTime) && !(state.sharing.globalRanking && !state.sharing.invisible) ->
                    MR.strings.social_global_hidden_hint
                weekly -> MR.strings.social_ranking_week_hint
                allTime -> MR.strings.social_ranking_all_time_hint
                !global && state.sharing.stats != "friends" -> MR.strings.social_ranking_join_hint
                else -> null
            }
            hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
    card?.let { entry ->
        val requested = state.outgoing.any { it.recipientUid == entry.uid }
        AlertDialog(
            onDismissRequest = { card = null },
            icon = { ProfileAvatar(entry.profile(state), loadMedia, 56.dp) },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val profile = entry.profile(state)
                    if (profile.banner != null) ProfileBanner(profile, loadMedia)
                    entry.handle?.let { Text("@$it") }
                    entry.level?.let {
                        Text(stringResource(MR.strings.social_friend_level, it, stringResource(XpLevels.title(it))))
                    }
                    if (entry.totalXp > 0) {
                        Text(stringResource(MR.strings.social_public_card_total, entry.chapters))
                    } else {
                        Text(stringResource(MR.strings.social_public_card_today, entry.chapters))
                        if (entry.streak >= 2) Text(stringResource(MR.strings.social_public_card_streak, entry.streak))
                    }
                    Text(
                        stringResource(MR.strings.social_public_card_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                val handle = entry.handle
                TextButton(
                    enabled = !requested && handle != null && !state.busy && state.profile != null,
                    onClick = {
                        if (handle != null) onIntent(FriendsIntent.RequestFriend(handle))
                        card = null
                    },
                ) {
                    Text(
                        stringResource(if (requested) MR.strings.social_request_sent else MR.strings.social_add_friend),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { card = null }) { Text(stringResource(MR.strings.action_close)) } },
        )
    }
}

private enum class RankingTab { FRIENDS, GLOBAL, WEEK, ALL_TIME }

private fun RankingEntry.asProfile() =
    SocialProfile(uid, handle.orEmpty(), name, accent = accent, avatar = avatar, banner = banner)

/** My own or a friend's full profile (with pictures) when known, otherwise what the ranking board carries. */
private fun RankingEntry.profile(state: FriendsState): SocialProfile =
    (if (me) state.profile else state.friends.firstOrNull { it.uid == uid }) ?: asProfile()

@Composable
private fun FriendRow(
    profile: SocialProfile,
    status: FriendStatus?,
    now: Long,
    onIntent: (FriendsIntent) -> Unit,
    loadMedia: ProfileMediaLoader,
    nudgeable: Boolean = false,
) {
    // With a banner: it fills the card behind a dark fade, and the text turns light.
    val banner = profile.banner != null
    Card(onClick = { onIntent(FriendsIntent.SelectFriend(profile.uid)) }) {
        Box {
            if (banner) {
                ProfileBannerFill(profile, loadMedia, Modifier.matchParentSize())
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.3f)),
                            ),
                        ),
                )
            }
            CompositionLocalProvider(
                LocalContentColor provides if (banner) Color.White else LocalContentColor.current,
            ) {
                FriendRowContent(profile, status, now, loadMedia, banner, nudgeable) {
                    onIntent(FriendsIntent.Nudge(profile.uid))
                }
            }
        }
    }
}

@Composable
private fun FriendRowContent(
    profile: SocialProfile,
    status: FriendStatus?,
    now: Long,
    loadMedia: ProfileMediaLoader,
    onBanner: Boolean,
    nudgeable: Boolean,
    onNudge: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            ProfileAvatar(profile, loadMedia, 44.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (status?.online == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
            Text("@${profile.handle}", style = MaterialTheme.typography.bodySmall)
            val reading = status?.readingAt(now)
            when {
                reading != null -> Text(
                    stringResource(MR.strings.social_reading_now, reading.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onBanner) {
                        MaterialTheme.colorScheme.inversePrimary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                status?.online == true -> Text(
                    stringResource(MR.strings.social_online),
                    style = MaterialTheme.typography.bodySmall,
                )
                status?.lastSeen != null -> Text(
                    stringResource(MR.strings.social_last_seen_relative, relativeTimeSpanString(status.lastSeen)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (nudgeable) {
            val nudgeLabel = stringResource(MR.strings.social_nudge_at_risk, profile.displayName)
            IconButton(onClick = onNudge) {
                Text(
                    "👋",
                    Modifier.semantics { contentDescription = nudgeLabel },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

/** A friend's open room: “Alex is reading X”, the people count, who may see me, and Join. */
@Composable
private fun OpenRoomCardView(card: OpenRoomCard, state: FriendsState, onIntent: (FriendsIntent) -> Unit) {
    val host = state.friends.firstOrNull { it.uid == card.hostUid }?.displayName
        ?: stringResource(MR.strings.social_request_account, "")
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(MR.strings.social_open_room_title, host, card.title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(MR.strings.social_room_people, card.memberCount, card.maxMembers),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(MR.strings.social_open_room_consent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(enabled = !state.busy, onClick = { onIntent(FriendsIntent.DropIn(card)) }) {
                Text(stringResource(MR.strings.social_join))
            }
        }
    }
}

@Composable
private fun RoomCard(
    room: SocialRoom,
    state: FriendsState,
    onIntent: (FriendsIntent) -> Unit,
    onSearch: (String) -> Unit,
    onBrowseSource: (Long, String) -> Unit,
) {
    val account = state.account ?: return
    val invitedMe = room.isInvited(account.uid)
    val otherName = if (invitedMe) {
        room.nameOf(room.hostUid) ?: state.friends.firstOrNull { it.uid == room.hostUid }?.displayName
    } else {
        FriendList.companions(room, account.uid, state.friends, stringResource(MR.strings.social_and))
    } ?: stringResource(MR.strings.social_request_account, "")
    val selected = room.id == state.selectedRoomId
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(
                    if (invitedMe) MR.strings.social_room_invited_by else MR.strings.social_room_with,
                    otherName,
                    room.content.title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            if (room.participants.size > 2) {
                Text(
                    stringResource(MR.strings.social_room_people, room.participants.size, room.maxMembers),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (invitedMe && room.participants.size > 2) {
                // Group rooms show people who may not be my friends: say who before joining.
                Text(
                    stringResource(
                        MR.strings.social_room_join_consent,
                        room.others(account.uid).joinToString { it.name.ifBlank { "?" } },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(
                    if (room.state ==
                        "invited"
                    ) {
                        MR.strings.social_room_invited
                    } else {
                        MR.strings.social_room_active
                    },
                ),
            )
            val inLibrary = state.localIndex[room.content.contentKey] != null
            // Not in my library, but the host's source is installed here: join and open it in one tap.
            val oneStep = !inLibrary && room.id in state.importableRooms
            Text(
                stringResource(
                    when {
                        selected && oneStep -> MR.strings.social_room_one_step
                        selected && !inLibrary -> MR.strings.social_room_not_in_library
                        selected -> MR.strings.social_room_selected
                        oneStep -> MR.strings.social_room_one_step
                        else -> MR.strings.social_room_open_matching
                    },
                ),
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            Row {
                if (!selected) {
                    Button(enabled = !state.busy, onClick = { onIntent(FriendsIntent.JoinRoom(room)) }) {
                        Text(
                            stringResource(
                                when {
                                    oneStep -> MR.strings.social_room_join_read
                                    invitedMe -> MR.strings.social_join
                                    else -> MR.strings.social_select_room
                                },
                            ),
                        )
                    }
                } else if (inLibrary || oneStep) {
                    Button(enabled = !state.busy, onClick = { onIntent(FriendsIntent.OpenRoom(room)) }) {
                        Text(
                            stringResource(
                                if (oneStep) MR.strings.social_room_join_read else MR.strings.social_room_open,
                            ),
                        )
                    }
                } else {
                    RoomSourceAction(state, room, onIntent, onSearch, onBrowseSource)
                }
                TextButton(enabled = !state.busy, onClick = { onIntent(FriendsIntent.CloseRoom(room)) }) {
                    // Only the host ends the room for everyone; anyone else just leaves it.
                    Text(
                        stringResource(
                            if (room.hostUid ==
                                account.uid
                            ) {
                                MR.strings.social_close_room
                            } else {
                                MR.strings.reader_social_leave
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteAccountButton(busy: Boolean, onIntent: (FriendsIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, onClick = { open = true }) {
        Text(stringResource(MR.strings.social_delete_account), color = MaterialTheme.colorScheme.error)
    }
    if (!open) return
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(MR.strings.social_delete_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(MR.strings.social_delete_account_summary))
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(stringResource(MR.strings.social_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    confirmation,
                    { confirmation = it },
                    label = { Text(stringResource(MR.strings.social_delete_account_type)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && confirmation == "DELETE",
                onClick = {
                    onIntent(FriendsIntent.DeleteAccount(password))
                    open = false
                },
            ) {
                Text(stringResource(MR.strings.social_delete_account_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

/** Avatar/banner (PNG, JPEG, WebP or GIF), bio and accent colour. Visible only to accepted friends. */
@Composable
private fun ProfileStyleEditor(
    profile: SocialProfile,
    busy: Boolean,
    onIntent: (FriendsIntent) -> Unit,
    loadMedia: ProfileMediaLoader,
) {
    var bio by remember(profile) { mutableStateOf(profile.bio) }
    var accent by remember(profile) { mutableIntStateOf(profile.accent) }
    var pickingKind by remember { mutableStateOf(ProfileStyle.AVATAR) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onIntent(FriendsIntent.PickMedia(pickingKind, uri))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(MR.strings.social_profile_style))
        ProfileHeader(profile.copy(bio = bio, accent = accent), loadMedia)
        Text(stringResource(MR.strings.social_profile_media_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ProfileStyle.AVATAR to MR.strings.social_change_avatar,
                ProfileStyle.BANNER to MR.strings.social_change_banner,
            )
                .forEach { (kind, label) ->
                    TextButton(enabled = !busy, onClick = {
                        pickingKind = kind
                        picker.launch("image/*")
                    }) { Text(stringResource(label)) }
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (profile.avatar != null) {
                TextButton(enabled = !busy, onClick = { onIntent(FriendsIntent.RemoveMedia(ProfileStyle.AVATAR)) }) {
                    Text(stringResource(MR.strings.social_remove_avatar))
                }
            }
            if (profile.banner != null) {
                TextButton(enabled = !busy, onClick = { onIntent(FriendsIntent.RemoveMedia(ProfileStyle.BANNER)) }) {
                    Text(stringResource(MR.strings.social_remove_banner))
                }
            }
        }
        OutlinedTextField(
            bio,
            { bio = it.replace("\n", " ").take(160) },
            label = { Text(stringResource(MR.strings.social_bio)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileStyle.ACCENTS.forEachIndexed { index, color ->
                Box(
                    Modifier
                        .size(if (index == accent) 34.dp else 28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { accent = index },
                )
            }
        }
        Button(
            enabled = !busy && (bio.trim() != profile.bio || accent != profile.accent),
            onClick = { onIntent(FriendsIntent.SaveStyle(bio, accent)) },
        ) { Text(stringResource(MR.strings.social_save_style)) }
    }
}

@Composable
private fun ShareInviteButton(handle: String) {
    val context = LocalContext.current
    val title = stringResource(MR.strings.social_share_invite)
    TextButton(onClick = {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, FriendInvite.shareText(handle, BuildConfig.SOCIAL_WEB_URL))
        context.startActivity(Intent.createChooser(send, title))
    }) { Text(title) }
}

@Composable
private fun AccountForm(busy: Boolean, onIntent: (FriendsIntent) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            email,
            {
                email = it
            },
            label = {
                Text(stringResource(MR.strings.social_email))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            {
                password = it
            },
            label = {
                Text(stringResource(MR.strings.social_password))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
                onIntent(FriendsIntent.SignIn(email, password, false))
                password = ""
            }) { Text(stringResource(MR.strings.social_sign_in)) }
            Button(enabled = !busy && email.isNotBlank() && password.length >= 6, onClick = {
                onIntent(FriendsIntent.SignIn(email, password, true))
                password = ""
            }) { Text(stringResource(MR.strings.social_register)) }
        }
        TextButton(enabled = !busy && email.isNotBlank(), onClick = { onIntent(FriendsIntent.ResetPassword(email)) }) {
            Text(stringResource(MR.strings.social_reset_password))
        }
    }
}

@Composable
private fun ProfileForm(profile: SocialProfile?, busy: Boolean, onIntent: (FriendsIntent) -> Unit) {
    var handle by remember(profile) { mutableStateOf(profile?.handle.orEmpty()) }
    var displayName by remember(profile) { mutableStateOf(profile?.displayName.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(MR.strings.social_profile))
        OutlinedTextField(handle, {
            handle = it.lowercase().take(24)
        }, label = {
            Text(stringResource(MR.strings.social_handle))
        }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(displayName, {
            displayName = it.take(40)
        }, label = {
            Text(stringResource(MR.strings.social_display_name))
        }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && handle.matches(Regex("[a-z0-9_]{3,24}")) && displayName.isNotBlank(), onClick = {
            onIntent(FriendsIntent.SaveProfile(handle, displayName))
        }) { Text(stringResource(MR.strings.social_save_profile)) }
    }
}

/** An invite's profile card: nothing is sent until "Send friend request" is tapped. */
@Composable
private fun InviteCard(
    profile: SocialProfile,
    busy: Boolean,
    onIntent: (FriendsIntent) -> Unit,
    loadMedia: ProfileMediaLoader,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(MR.strings.social_invite_title), style = MaterialTheme.typography.titleSmall)
            ProfileHeader(profile, loadMedia)
            Row {
                Button(enabled = !busy, onClick = { onIntent(FriendsIntent.SendInvite) }) {
                    Text(stringResource(MR.strings.social_send_request))
                }
                TextButton(enabled = !busy, onClick = { onIntent(FriendsIntent.DismissInvite) }) {
                    Text(stringResource(MR.strings.social_invite_dismiss))
                }
            }
        }
    }
}

/** "Got an invite?": takes the friend code from the web invite page (or a link or plain handle). */
@Composable
private fun FriendCodeField(disabled: Boolean, onIntent: (FriendsIntent) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val handle = FriendInvite.parseTyped(code)
    Column {
        Text(stringResource(MR.strings.social_friend_code_title), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(code, { code = it.take(80) }, label = {
            Text(stringResource(MR.strings.social_friend_code_hint))
        }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !disabled && handle != null, onClick = {
            handle?.let { onIntent(FriendsIntent.LookupInvite(it)) }
            code = ""
        }) { Text(stringResource(MR.strings.social_friend_code_lookup)) }
    }
}

@Composable
private fun FriendRequestForm(disabled: Boolean, onIntent: (FriendsIntent) -> Unit, prefill: String? = null) {
    var handle by remember(prefill) { mutableStateOf(prefill.orEmpty()) }
    OutlinedTextField(handle, {
        handle = it.lowercase().take(24)
    }, label = {
        Text(stringResource(MR.strings.social_friend_handle))
    }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(enabled = !disabled && handle.matches(Regex("[a-z0-9_]{3,24}")), onClick = {
        onIntent(FriendsIntent.RequestFriend(handle))
        handle = ""
    }) { Text(stringResource(MR.strings.social_send_request)) }
}

@Composable
private fun RequestRow(
    uid: String,
    handle: String?,
    displayName: String?,
    busy: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    incoming: Boolean,
) {
    Column {
        Text(displayName ?: stringResource(MR.strings.social_request_account, uid.take(12)))
        handle?.let { Text("@$it") }
        Row {
            if (incoming) {
                TextButton(enabled = !busy, onClick = onAccept) {
                    Text(stringResource(MR.strings.social_accept))
                }
            }
            TextButton(enabled = !busy, onClick = onReject) {
                Text(stringResource(if (incoming) MR.strings.social_reject else MR.strings.social_cancel_request))
            }
        }
    }
}

@Composable
private fun SharingControls(settings: SocialSharing, busy: Boolean, onIntent: (FriendsIntent) -> Unit) {
    val update = { value: SocialSharing -> onIntent(FriendsIntent.ChangeSharing(value)) }
    PrivacySwitch(stringResource(MR.strings.social_library), settings.library == "friends", !busy) {
        update(settings.copy(library = visibility(it)))
    }
    // Part of the library: only meaningful (and only sent) while the library is shared.
    PrivacySwitch(
        stringResource(MR.strings.social_share_progress),
        settings.progress,
        !busy && settings.library == "friends",
    ) {
        update(settings.copy(progress = it))
    }
    PrivacySwitch(stringResource(MR.strings.social_favorites), settings.favorites == "friends", !busy) {
        update(settings.copy(favorites = visibility(it)))
    }
    PrivacySwitch(stringResource(MR.strings.social_activity), settings.activity == "friends", !busy) {
        update(settings.copy(activity = visibility(it)))
    }
    PrivacySwitch(stringResource(MR.strings.social_feed), settings.feed == "friends", !busy) {
        update(settings.copy(feed = visibility(it)))
    }
    PrivacySwitch(stringResource(MR.strings.social_stats), settings.stats == "friends", !busy) {
        update(settings.copy(stats = visibility(it)))
    }
    PrivacySwitch(stringResource(MR.strings.social_global_ranking), settings.globalRanking, !busy) {
        update(settings.copy(globalRanking = it))
    }
    PrivacySwitch(
        stringResource(MR.strings.social_ranking_media),
        settings.rankingMedia,
        !busy && settings.globalRanking && !settings.invisible,
    ) {
        update(settings.copy(rankingMedia = it))
    }
    Text(
        stringResource(MR.strings.social_global_ranking_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrivacySwitch(stringResource(MR.strings.social_reviews), settings.reviews, !busy) {
        update(settings.copy(reviews = it))
    }
    Text(
        stringResource(MR.strings.social_reviews_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrivacySwitch(stringResource(MR.strings.social_presence), settings.presence == "friends", !busy) {
        update(settings.copy(presence = visibility(it)))
    }
    PrivacySwitch(stringResource(MR.strings.social_last_seen), settings.lastSeen == "friends", !busy) {
        update(settings.copy(lastSeen = visibility(it)))
    }
    // Invisible mode hides instead of sharing, so it sits apart from the sharing switches.
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    PrivacySwitch(stringResource(MR.strings.social_invisible), settings.invisible, !busy) {
        update(settings.copy(invisible = it))
    }
    Text(
        stringResource(MR.strings.social_invisible_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun visibility(shared: Boolean) = if (shared) "friends" else "private"

@Composable
private fun PrivacySwitch(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, not just the switch thumb.
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/** Not in my library: search the host's source, install its extension, trust it, or say which one I need. */
@Composable
private fun RoomSourceAction(
    state: FriendsState,
    room: SocialRoom,
    onIntent: (FriendsIntent) -> Unit,
    onSearch: (String) -> Unit,
    onBrowseSource: (Long, String) -> Unit,
) {
    val installing = state.installing
    when (val guide = state.roomGuide) {
        is RoomSourceGuide.Installed -> Button(onClick = { onBrowseSource(guide.sourceId, room.content.title) }) {
            Text(stringResource(MR.strings.social_room_search_in, guide.name))
        }
        is RoomSourceGuide.Installable -> Column {
            Text(
                stringResource(MR.strings.social_room_needs_extension, guide.name, guide.lang.uppercase()),
                style = MaterialTheme.typography.bodySmall,
            )
            val busy = installing != null && !installing.isCompleted()
            Button(enabled = !busy, onClick = { onIntent(FriendsIntent.InstallRoomSource) }) {
                Text(
                    if (busy) {
                        stringResource(MR.strings.social_room_installing)
                    } else {
                        stringResource(MR.strings.social_room_install_join, guide.name)
                    },
                )
            }
            (installing as? eu.kanade.tachiyomi.extension.model.InstallStep.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        is RoomSourceGuide.Untrusted -> Column {
            Text(
                stringResource(MR.strings.social_room_trust_hint, guide.name),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                onIntent(FriendsIntent.TrustRoomSource)
            }) { Text(stringResource(MR.strings.social_room_trust)) }
        }
        is RoomSourceGuide.Missing -> Column {
            Text(
                stringResource(MR.strings.social_room_missing_extension, guide.name, guide.lang.uppercase()),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { onSearch(room.content.title) }) { Text(stringResource(MR.strings.social_room_find)) }
        }
        RoomSourceGuide.Unknown, null -> Button(onClick = { onSearch(room.content.title) }) {
            Text(stringResource(MR.strings.social_room_find))
        }
    }
}

/** Which pushes this phone shows, plus the Android 13+ permission when it's missing. */
@Composable
private fun NotificationSettings(preference: (String) -> Preference<Boolean>?) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Column {
        SectionTitle(stringResource(MR.strings.social_notifications))
        if (!granted) {
            Text(stringResource(MR.strings.social_notifications_permission), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(MR.strings.social_notifications_allow))
            }
        }
        listOf(
            SocialPushMessage.ROOM_INVITE to MR.strings.social_notify_room_invite,
            SocialPushMessage.JOIN_REQUEST to MR.strings.social_notify_join_request,
            SocialPushMessage.MESSAGE to MR.strings.social_notify_message,
            SocialPushMessage.GROUP_MESSAGE to MR.strings.social_notify_group_message,
            SocialPushMessage.GROUP_ADDED to MR.strings.social_notify_group_added,
            SocialPushMessage.FRIEND_REQUEST to MR.strings.social_notify_friend_request,
            SocialPushMessage.FRIEND_ACCEPTED to MR.strings.social_notify_friend_accepted,
            SocialPushMessage.READ_MATCH to MR.strings.social_notify_read_match,
            SocialPushMessage.NUDGE to MR.strings.social_notify_nudge,
        ).forEach { (kind, label) ->
            val pref = preference(kind) ?: return@forEach
            val on by pref.collectAsState()
            PrivacySwitch(stringResource(label), on, enabled = true) { pref.set(it) }
        }
        preference(SocialNotifications.LOCK_SCREEN)?.let { pref ->
            val on by pref.collectAsState()
            PrivacySwitch(stringResource(MR.strings.social_notify_hide_text), on, enabled = true) { pref.set(it) }
            Text(
                stringResource(MR.strings.social_notify_hide_text_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (BuildConfig.SOCIAL_ENVIRONMENT != "production") {
            Text(stringResource(MR.strings.social_notifications_emulator), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Counts, search and All / Favorites / Hidden chips above my titles. */
@Composable
private fun OwnTitlesHeader(
    total: Int,
    favorites: Int,
    hidden: Int,
    query: String,
    onQuery: (String) -> Unit,
    filter: OwnTitles.Filter,
    onFilter: (OwnTitles.Filter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(MR.strings.social_own_counts, total, favorites, hidden),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (total > OWN_PREVIEW) {
            OutlinedTextField(
                value = query,
                onValueChange = { onQuery(it.take(100)) },
                placeholder = { Text(stringResource(MR.strings.social_grid_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                OwnTitles.Filter.ALL to MR.strings.social_own_all,
                OwnTitles.Filter.FAVORITES to MR.strings.social_favorites,
                OwnTitles.Filter.HIDDEN to MR.strings.social_own_hidden,
            ).forEach { (value, label) ->
                FilterChip(selected = filter == value, onClick = {
                    onFilter(value)
                }, label = { Text(stringResource(label)) })
            }
        }
    }
}

/** One title on one line: heart marks a favorite, the eye hides it from friends (or shows it again). */
@Composable
private fun OwnTitleRow(
    content: SocialContent,
    favorite: Boolean,
    hidden: Boolean,
    busy: Boolean,
    sharing: SocialSharing,
    onIntent: (FriendsIntent) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            content.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(enabled = !busy && !hidden, onClick = {
            onIntent(FriendsIntent.ToggleFavorite(content.contentKey))
        }) {
            Icon(
                if (favorite) MaterialSymbols.RoundedFilled.Favorite else MaterialSymbols.Rounded.Favorite,
                contentDescription = stringResource(MR.strings.social_favorite),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            enabled = !busy,
            onClick = {
                onIntent(
                    if (hidden) {
                        FriendsIntent.RestoreHidden(content.contentKey)
                    } else {
                        FriendsIntent.ChangeSharing(sharing, content.contentKey)
                    },
                )
            },
        ) {
            Icon(
                if (hidden) MaterialSymbols.Rounded.VisibilityOff else MaterialSymbols.Rounded.Visibility,
                contentDescription = stringResource(if (hidden) MR.strings.social_restore else MR.strings.social_hide),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

/** Titles shown before "Show all" in my own list. */
private const val OWN_PREVIEW = 8
