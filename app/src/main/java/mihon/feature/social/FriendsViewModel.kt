package mihon.feature.social

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.feature.social.reader.PendingRoomInvite
import mihon.feature.social.reader.ReadTogetherStart
import mihon.feature.social.reader.ReadingSessionStore
import mihon.feature.social.reader.RoomDestination
import mihon.feature.social.reader.RoomDestinations
import mihon.feature.social.reader.RoomTitleImporter
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover

enum class SocialFeedback { NONE, SUCCESS, EMAIL_SENT, ERROR, PENDING_RETRACTION, ACCOUNT_DELETED, MEDIA_REJECTED }

data class FriendsState(
    /** Results of "Check connection" (service → OK or the error). */
    val connectionCheck: List<ConnectionProbe> = emptyList(),
    val checkingConnection: Boolean = false,
    val enabled: Boolean = false,
    val account: SocialAccount? = null,
    val profile: SocialProfile? = null,
    val sharing: SocialSharing = SocialSharing(),
    val friends: List<SocialProfile> = emptyList(),
    val incoming: List<SocialRequest> = emptyList(),
    val outgoing: List<SocialRequest> = emptyList(),
    val blocks: List<String> = emptyList(),
    val library: List<SocialContent> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    val hiddenKeys: Set<String> = emptySet(),
    val rooms: List<SocialRoom> = emptyList(),
    val selectedFriend: SocialFriendDetails? = null,
    val busy: Boolean = false,
    val feedback: SocialFeedback = SocialFeedback.NONE,
    val friendStatuses: Map<String, FriendStatus> = emptyMap(),
    val friendsLoading: Boolean = false,
    /** The friend list has loaded at least once for this account (gates the one-time invite clipboard check). */
    val friendsReady: Boolean = false,
    /** Someone whose invite (link, friend code or clipboard) I opened: shown with a one-tap friend request. */
    val invite: SocialProfile? = null,
    val selectedRoomId: String? = null,
    /** Shared content key → local manga id, for opening a friend's title in my own library. */
    val localIndex: Map<String, Long> = emptyMap(),
    /** Shared content key → my library's cover, for the title pickers. */
    val covers: Map<String, MangaCover> = emptyMap(),
    /** A joined room's title to open once (reader at the host's chapter, or the title page). */
    val roomJump: RoomDestination? = null,
    /** Today's ranking among me and friends who share stats. */
    val ranking: List<RankingEntry> = emptyList(),
    /** This week's XP among me and friends who share stats. */
    val weeklyRanking: List<RankingEntry> = emptyList(),
    val allTimeRanking: List<RankingEntry> = emptyList(),
    /** Today's global ranking (everyone who didn't opt out). */
    val globalRanking: List<RankingEntry> = emptyList(),
    /** One-time notice that the global ranking is on by default, with a way to turn it off. */
    val rankingNotice: Boolean = false,
    /** A friend's full library or favorites grid, when open. */
    val grid: FriendGrid? = null,
    /** Include public https covers when publishing my library (a device setting). */
    val shareCovers: Boolean = true,
    /** My conversations (most recent first) and the one open on screen. */
    val chats: List<ChatSummary> = emptyList(),
    val chat: OpenChat? = null,
    /** My group chats and the one open on screen. */
    val groups: List<GroupSummary> = emptyList(),
    val group: OpenGroup? = null,
    /** My XP and level. */
    val xp: SocialXp? = null,
    /** Friends asking to read along with me. */
    val joinRequests: List<JoinRequest> = emptyList(),
    /** For the selected room's title when it isn't in my library: which extension I need (roadmap F9). */
    val roomGuide: RoomSourceGuide? = null,
    /** Installing the room's extension: null when idle, otherwise the step. */
    val installing: eu.kanade.tachiyomi.extension.model.InstallStep? = null,
    /** After installing: open this source's search for the room's title once. */
    val sourceJump: Pair<Long, String>? = null,
    /** Rooms whose title I can open in one step (host shared its URL, the source is installed here). */
    val importableRooms: Set<String> = emptySet(),
    /** The Friends / Chats / Me tab on screen. */
    val tab: FriendsTab = FriendsTab.FRIENDS,
    /** My "let's read this" wishes, matches first. */
    val readWishes: List<ReadWish> = emptyList(),
    /** My installed sources (ID → name), to look for a friend's title in the source they read it on. */
    val installedSources: Map<Long, String> = emptyMap(),
    /** Friends I nudged in the last 24 h (uid to time), remembered on this phone. */
    val nudgedAt: Map<String, Long> = emptyMap(),
    /** Friends whose reading streak is at risk and whose stats I can read (nudge icon in the list). */
    val streakAtRisk: Set<String> = emptySet(),
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class FriendsViewModel(
    private val context: Context,
    private val repository: SocialRepository,
    private val preferences: PreferenceStore,
    private val getFavorites: GetFavorites,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val incognito: GetIncognitoState,
    private val sessions: ReadingSessionStore,
    private val push: SocialPushRegistration,
    private val notificationPreferences: SocialNotificationPreferences,
    private val sourceManager: tachiyomi.domain.source.service.SourceManager,
    private val extensionManager: eu.kanade.tachiyomi.extension.ExtensionManager,
    private val coverCache: eu.kanade.tachiyomi.data.cache.CoverCache,
    private val getTracks: tachiyomi.domain.track.interactor.GetTracks,
    private val roomTitleImporter: RoomTitleImporter,
    private val getLibraryManga: tachiyomi.domain.manga.interactor.GetLibraryManga,
    private val friendReaders: FriendReaders,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FriendsState(enabled = repository.enabled))
    val state = mutableState.asStateFlow()
    private var publishing: Job? = null
    private var friendObservation: Job? = null
    private var localManga: List<Manga> = emptyList()
    private val refreshRequests = MutableStateFlow(0)
    private val shareCoversPreference = preferences.getBoolean("social_share_covers", true)
    private val inviteClipboardChecked = preferences.getBoolean(
        Preference.appStateKey("social_invite_clipboard_checked"),
        false,
    )
    private var gridJob: Job? = null
    private var gridCursor: DocumentSnapshot? = null
    private var gridWantsAll = false
    private var chatJob: Job? = null
    private var groupJob: Job? = null

    // Started explicitly by the Friends screen's lifecycle effect, cancelled when it leaves.
    suspend fun observe() {
        try {
            observeAccount()
        } finally {
            // The open group or chat listens only while the Friends screen is observed.
            closeGroup()
            closeChat()
        }
    }

    private suspend fun observeAccount() = coroutineScope {
        try {
            repository.authChanges().collectLatest { account ->
                val previousUid = mutableState.value.account?.uid
                val deleted = account == null && mutableState.value.feedback == SocialFeedback.ACCOUNT_DELETED
                publishing?.cancel()
                groupJob?.cancel()
                friendObservation?.cancel()
                localManga = emptyList()
                mutableState.value = FriendsState(
                    enabled = repository.enabled,
                    account = account,
                    tab = mutableState.value.tab,
                    feedback = when {
                        deleted -> SocialFeedback.ACCOUNT_DELETED
                        repository.presenceCleanupPending -> SocialFeedback.PENDING_RETRACTION
                        else -> SocialFeedback.NONE
                    },
                )
                if (previousUid != account?.uid && (previousUid != null || account == null)) sessions.clear()
                if (previousUid != null && previousUid != account?.uid) friendReaders.forget()
                if (account == null) return@collectLatest
                // My titles come from the local library, so they show even if the first server call fails (right
                // after signing in it can, before the account is fully set up).
                localManga = getFavorites.await()
                showLocalLibrary(account.uid)
                try {
                    val self = repository.getSelf()
                    update(account.uid) { it.copy(profile = self.profile, sharing = self.settings) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    update(account.uid) { it.copy(feedback = SocialFeedback.ERROR) }
                }
                coroutineScope {
                    launch { refreshLibraryProgress(account.uid) }
                    launch {
                        extensionManager.loadedExtensionsFlow.collect { loaded ->
                            val sources = sourceManager.getAll().associate { it.id to it.name } +
                                loaded.flatMap { e -> e.sources.map { it.id to it.name } }
                            update(account.uid) { it.copy(installedSources = sources) }
                        }
                    }
                    launch {
                        repository.watchSettings(account.uid).safe(account.uid).collect { settings ->
                            if (settings.generation != mutableState.value.sharing.generation) publishing?.cancel()
                            update(account.uid) { it.copy(sharing = settings) }
                        }
                    }
                    launch {
                        repository.watchHidden(account.uid).safe(account.uid).collect { keys ->
                            publishing?.cancel()
                            update(account.uid) { it.copy(hiddenKeys = keys) }
                        }
                    }
                    launch {
                        combine(
                            rankingNoticePreference(account.uid).changes(),
                            repository.watchSettings(account.uid).safe(account.uid),
                        ) { seen, settings -> !seen && settings.globalRanking && !settings.invisible }
                            .collect { show -> update(account.uid) { it.copy(rankingNotice = show) } }
                    }
                    launch {
                        shareCoversPreference.changes().collect { on ->
                            update(account.uid) { it.copy(shareCovers = on) }
                        }
                    }
                    launch {
                        repository.watchJoinRequests(account.uid).safe(account.uid) {
                            it.copy(joinRequests = emptyList())
                        }
                            .collect { requests -> update(account.uid) { it.copy(joinRequests = requests) } }
                    }
                    launch {
                        repository.watchChats(account.uid).safe(account.uid) { it.copy(chats = emptyList()) }
                            .collect { chats -> update(account.uid) { it.copy(chats = chats) } }
                    }
                    launch {
                        repository.watchGroups(account.uid).safe(account.uid) { it.copy(groups = emptyList()) }
                            .collect { groups -> update(account.uid) { it.copy(groups = groups) } }
                    }
                    launch {
                        // Owner-only; a backend without read wishes just leaves the list empty.
                        repository.watchReadWishes(account.uid)
                            .catch { error ->
                                if (error is CancellationException) throw error
                                emit(emptyList())
                            }
                            .collect { wishes -> update(account.uid) { it.copy(readWishes = wishes) } }
                    }
                    launch {
                        // Which room titles open in one step: URL shared and the source installed here.
                        combine(
                            state.map { s ->
                                s.rooms.map { it.id to it.content } to s.localIndex
                            }.distinctUntilChanged(),
                            extensionManager.loadedExtensionsFlow,
                        ) { (rooms, index), loaded ->
                            val installed = loaded.flatMap { e -> e.sources.map { it.id } }.toSet() +
                                sourceManager.getAll().map { it.id }
                            rooms.filter { (_, content) ->
                                index[content.contentKey] == null &&
                                    ReadTogetherStart.canImport(content.source, installed)
                            }.mapTo(mutableSetOf()) { it.first }
                        }.collect { ids -> update(account.uid) { it.copy(importableRooms = ids) } }
                    }
                    launch {
                        repository.watchXp(account.uid).safe(account.uid) { it.copy(xp = null) }
                            .collect { xp ->
                                update(account.uid) { state ->
                                    // My weekly row follows my XP live; friends' rows refresh with the ranking.
                                    val me = RankingEntry(
                                        account.uid,
                                        state.profile?.displayName ?: "",
                                        chapters = xp?.weekChapters(xpWeek()) ?: 0,
                                        pages = 0,
                                        me = true,
                                        level = xp?.level,
                                        weekXp = xp?.weekXp(xpWeek()) ?: 0,
                                    )
                                    state.copy(
                                        xp = xp,
                                        weeklyRanking = FriendList.weekly(state.weeklyRanking.filterNot { it.me } + me),
                                    )
                                }
                            }
                    }
                    launch {
                        favoritePreference(account.uid).changes().collect { keys ->
                            update(account.uid) { it.copy(favoriteKeys = keys) }
                        }
                    }
                    launch {
                        sessions.selectedRoomId.collect { id -> update(account.uid) { it.copy(selectedRoomId = id) } }
                    }
                    launch {
                        // Re-decide whenever the selected room, my library or my extensions change.
                        combine(
                            state.map { s -> s.rooms.firstOrNull { it.id == s.selectedRoomId } to s.localIndex }
                                .distinctUntilChanged(),
                            extensionManager.loadedExtensionsFlow,
                            extensionManager.notLoadedExtensionsFlow,
                            extensionManager.availableExtensionsFlow,
                        ) { (room, index), loaded, notLoaded, available ->
                            if (room == null || index[room.content.contentKey] != null) {
                                null
                            } else {
                                RoomSourceGuide.decide(
                                    source = room.content.source,
                                    installedSourceIds = loaded.flatMap { e -> e.sources.map { it.id } }.toSet() +
                                        sourceManager.getAll().map { it.id },
                                    untrustedPackages = notLoaded.map { it.pkgName }.toSet(),
                                    available = available.map { e ->
                                        RoomSourceGuide.Companion.Listing(e.pkgName, e.sources.map { it.id }.toSet())
                                    },
                                )
                            }
                        }.collect { guide -> update(account.uid) { it.copy(roomGuide = guide) } }
                    }
                    launch {
                        combine(repository.watchAccepted(account.uid), refreshRequests) { ids, _ -> ids }
                            .safe(account.uid) {
                                it.copy(friends = emptyList(), selectedFriend = null, friendStatuses = emptyMap())
                            }.collectLatest { ids ->
                                friendReaders.retain(ids)
                                val selectedUid = mutableState.value.selectedFriend?.profile?.uid
                                if (selectedUid != null && selectedUid !in ids) friendObservation?.cancel()
                                // A room I host goes on; a room hosted by someone no longer my friend is gone for me.
                                val revokedRooms = mutableState.value.rooms.filter {
                                    it.hostUid != account.uid && it.hostUid !in ids
                                }
                                if (revokedRooms.any { it.id == sessions.selectedRoomId.value }) sessions.clear()
                                if (mutableState.value.grid?.uid?.let { it !in ids } == true) closeGrid()
                                if (mutableState.value.chat?.otherUid?.let { it !in ids } == true) closeChat()
                                update(account.uid) {
                                    it.copy(
                                        selectedFriend = it.selectedFriend?.takeIf { details ->
                                            details.profile.uid in ids
                                        },
                                        rooms = it.rooms.filterNot { room -> room in revokedRooms },
                                        // Revoked friends disappear immediately; the rest stay visible while reloading.
                                        friends = it.friends.filter { friend -> friend.uid in ids },
                                        friendStatuses = it.friendStatuses.filterKeys { uid -> uid in ids },
                                        friendsLoading = true,
                                    )
                                }
                                val profiles = ids.map { repository.getProfile(it) }
                                update(account.uid) { current ->
                                    current.copy(
                                        friends = profiles,
                                        selectedFriend = current.selectedFriend?.takeIf { it.profile.uid in ids },
                                        friendsLoading = false,
                                        friendsReady = true,
                                    )
                                }
                                loadRanking(account.uid)
                                // Only what each friend shares is readable; denied reads simply stay null.
                                coroutineScope {
                                    ids.take(MAX_WATCHED_FRIENDS).forEach { uid ->
                                        launch { watchStatus(account.uid, uid) }
                                    }
                                }
                            }
                    }
                    launch {
                        repository.watchRequests(account.uid, true)
                            .safe(account.uid) { it.copy(incoming = emptyList()) }
                            .collect { requests -> update(account.uid) { it.copy(incoming = requests) } }
                    }
                    launch {
                        repository.watchRequests(account.uid, false)
                            .safe(account.uid) { it.copy(outgoing = emptyList()) }
                            .collect { requests -> update(account.uid) { it.copy(outgoing = requests) } }
                    }
                    launch {
                        repository.watchBlocks(account.uid).safe(account.uid).collect { blocks ->
                            update(account.uid) { it.copy(blocks = blocks) }
                        }
                    }
                    launch {
                        repository.watchInvites(account.uid).safe(account.uid) { it.copy(rooms = emptyList()) }
                            .collectLatest { invites ->
                                update(account.uid) { it.copy(rooms = emptyList()) }
                                coroutineScope {
                                    invites.forEach { invite ->
                                        launch {
                                            repository.watchRoom(invite.id)
                                                // An ended or expired room just disappears; it is not an error.
                                                .catch { if (it is SocialRoomUnavailable) emit(null) else throw it }
                                                .safe(account.uid) { current ->
                                                    current.copy(rooms = current.rooms.filterNot { it.id == invite.id })
                                                }.collect { room ->
                                                    update(account.uid) { current ->
                                                        current.copy(
                                                            rooms = current.rooms.filterNot { it.id == invite.id } +
                                                                listOfNotNull(room),
                                                        )
                                                    }
                                                }
                                        }
                                    }
                                }
                            }
                    }
                    launch {
                        while (true) {
                            delay(1_000)
                            update(account.uid) { current ->
                                current.copy(
                                    rooms = current.rooms.filter { it.leaseExpiresAt > System.currentTimeMillis() },
                                    selectedFriend = current.selectedFriend?.let { details ->
                                        if ((details.activityExpiresAt ?: 0) <= System.currentTimeMillis()) {
                                            details.copy(activity = null, activityExpiresAt = null)
                                        } else {
                                            details
                                        }
                                    },
                                )
                            }
                        }
                    }
                    launch {
                        // Today's ranking: cheap single-document reads, refreshed every minute while visible.
                        while (true) {
                            loadRanking(account.uid)
                            delay(60_000)
                        }
                    }
                    launch {
                        repository.projectionPermit.collect { permitted ->
                            if (!permitted) publishing?.cancel()
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update {
                it.copy(
                    sharing = SocialSharing(),
                    friends = emptyList(),
                    incoming = emptyList(),
                    outgoing = emptyList(),
                    rooms = emptyList(),
                    selectedFriend = null,
                    feedback = SocialFeedback.ERROR,
                )
            }
        } finally {
            publishing?.cancel()
            friendObservation?.cancel()
        }
    }

    fun signIn(email: String, password: String, register: Boolean) = action {
        if (register) repository.register(email, password) else repository.signIn(email, password)
    }

    fun resetPassword(email: String) = action(SocialFeedback.EMAIL_SENT) { repository.resetPassword(email) }

    fun verifyEmail() = action(SocialFeedback.EMAIL_SENT) { repository.verifyEmail() }

    fun refreshAccount() = action { repository.refreshAccount() }

    fun signOut() = action {
        publishing?.cancel()
        friendObservation?.cancel()
        sessions.clear()
        try {
            withContext(NonCancellable) {
                repository.currentUid?.let { uid -> withTimeoutOrNull(3_000) { push.unregister(uid) } }
                repository.stopPresenceBeforeSignOut()
                withTimeoutOrNull(5_000) {
                    try {
                        val current = repository.getSelf().settings
                        val fenced = repository.updateSharing(current)
                        repository.publishActivity(fenced.generation, null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Expiry and the server generation remain fallback protection.
                    }
                }
            }
        } finally {
            mutableState.value = FriendsState(
                enabled = repository.enabled,
                feedback = if (repository.presenceCleanupPending) {
                    SocialFeedback.PENDING_RETRACTION
                } else {
                    SocialFeedback.NONE
                },
            )
            repository.signOut()
        }
    }

    suspend fun loadMedia(uid: String, kind: String, version: Long): ByteArray? = repository.loadProfileMedia(
        uid,
        kind,
        version,
    )

    fun saveStyle(bio: String, accent: Int) = action {
        val profile = repository.updateProfileStyle(bio.trim().take(160), accent, "keep", "keep")
        mutableState.update { it.copy(profile = profile) }
    }

    /** Validates type and size locally (the server and Storage rules check again), uploads, then publishes. */
    fun pickMedia(kind: String, uri: Uri) = viewModelScope.launch {
        val current = mutableState.value.profile ?: return@launch
        val resolver = context.contentResolver
        val type = resolver.getType(uri)
        val max = ProfileStyle.maxBytes(kind)
        val bytes = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { input -> input.readAtMost(max + 1) }
        }
        if (type !in ProfileStyle.CONTENT_TYPES || bytes == null || bytes.isEmpty() || bytes.size > max) {
            mutableState.update { it.copy(feedback = SocialFeedback.MEDIA_REJECTED) }
            return@launch
        }
        action {
            repository.uploadProfileMedia(kind, bytes, checkNotNull(type))
            val profile = repository.updateProfileStyle(
                current.bio,
                current.accent,
                if (kind == ProfileStyle.AVATAR) "set" else "keep",
                if (kind == ProfileStyle.BANNER) "set" else "keep",
            )
            mutableState.update { it.copy(profile = profile) }
        }
    }

    fun removeMedia(kind: String) = action {
        val current = checkNotNull(mutableState.value.profile)
        val profile = repository.updateProfileStyle(
            current.bio,
            current.accent,
            if (kind == ProfileStyle.AVATAR) "remove" else "keep",
            if (kind == ProfileStyle.BANNER) "remove" else "keep",
        )
        mutableState.update { it.copy(profile = profile) }
        repository.deleteProfileMedia(kind)
    }
    fun deleteAccount(password: String) = action(SocialFeedback.ACCOUNT_DELETED) {
        publishing?.cancel()
        friendObservation?.cancel()
        sessions.clear()
        repository.deleteAccount(password)
        mutableState.value = FriendsState(enabled = repository.enabled, feedback = SocialFeedback.ACCOUNT_DELETED)
    }
    fun saveProfile(handle: String, displayName: String) = action {
        val profile = repository.updateProfile(handle, displayName)
        mutableState.update { it.copy(profile = profile) }
    }

    fun requestFriend(handle: String) = action {
        val profile = repository.lookupHandle(handle)
        repository.friendAction("request", profile.uid)
    }

    /**
     * Shows [handle]'s profile card for an invite; nothing is sent until [sendInvite]. My own handle, a current friend
     * or a pending request are skipped.
     */
    fun lookupInvite(handle: String) = action {
        val current = mutableState.value
        if (handle == current.profile?.handle || current.outgoing.any { it.recipientHandle == handle }) return@action
        val profile = repository.lookupHandle(handle)
        if (profile.uid == repository.currentUid || current.friends.any { it.uid == profile.uid }) return@action
        mutableState.update { it.copy(invite = profile) }
    }

    fun sendInvite() = action {
        val invite = mutableState.value.invite ?: return@action
        repository.friendAction("request", invite.uid, viaInvite = true)
        mutableState.update { it.copy(invite = null) }
    }

    fun dismissInvite() = mutableState.update { it.copy(invite = null) }

    /**
     * First signed-in visit with no friends: looks once, ever, for a friend code on the clipboard (copied on the web
     * invite page before installing). Android shows its own paste notice; anything else on the clipboard is ignored.
     */
    fun checkClipboardInvite() {
        if (inviteClipboardChecked.get()) return
        inviteClipboardChecked.set(true)
        val text = runCatching {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        FriendInvite.parseCode(text)?.let(::lookupInvite)
    }

    fun friendAction(action: String, uid: String) = action {
        repository.friendAction(action, uid)
        friendObservation?.cancel()
        mutableState.update { it.copy(selectedFriend = null) }
    }

    fun changeSharing(settings: SocialSharing, hideKey: String? = null) = action {
        publishing?.cancel()
        val updated = repository.updateSharing(settings, hideKey)
        mutableState.update { it.copy(sharing = updated) }
    }

    fun toggleFavorite(contentKey: String) = action {
        val uid = checkNotNull(repository.currentUid)
        val preference = favoritePreference(uid)
        val old = preference.get()
        val added = contentKey !in old
        preference.set(if (added) old + contentKey else old - contentKey)
        if (added) publishFavoriteAdded(contentKey)
        if (!added && mutableState.value.sharing.favorites == "friends") {
            repository.publishEntries(
                "favorites",
                mutableState.value.sharing.generation,
                emptyList(),
                listOf(contentKey),
            )
        }
    }

    /** Best-effort feed entry; same eligibility as publishing (shared, visible, not hidden, source not incognito). */
    private suspend fun publishFavoriteAdded(contentKey: String) {
        val state = mutableState.value
        val sharing = state.sharing
        if (sharing.feed != "friends" || sharing.invisible || contentKey in state.hiddenKeys) return
        val manga = localManga.firstOrNull { SocialIdentity.content(it)?.contentKey == contentKey } ?: return
        val content = SocialIdentity.content(manga) ?: return
        if (incognito.await(manga.source)) return
        try {
            repository.publishFeed(sharing.generation, SocialFeedItem.KIND_FAVORITE_ADDED, content, null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A missed history entry is acceptable; the favorite itself is already saved.
            logcat(LogPriority.WARN, error) { "Social feed favorite_added not published" }
        }
    }

    /**
     * Publishes my shared library and favorites: library entries carry my progress in titles I opened; only entries
     * that changed are written. [quiet] is the periodic progress refresh: nothing shows on screen, failures are logged.
     */
    fun publishLibrary(quiet: Boolean = false) {
        publishing?.cancel()
        val work: suspend () -> Unit = {
            val initial = mutableState.value
            val uid = checkNotNull(initial.account).uid
            localManga = getFavorites.await()
            showLocalLibrary(uid)
            val covers = shareCoversPreference.get()
            val sharesTitles = initial.sharing.library == "friends" || initial.sharing.favorites == "friends"
            val eligible = localManga.filterNot { incognito.await(it.source) }
                .mapNotNull { manga ->
                    SocialIdentity.content(manga)?.let {
                        val tracked = trackedId(manga.id)
                        val content = it.copy(canonicalProvider = tracked?.first, canonicalId = tracked?.second)
                        if (covers) content.copy(coverUrl = SocialIdentity.publicCover(manga.thumbnailUrl)) else content
                    }?.let { it to manga }
                }
                .filterNot { (content) -> content.contentKey in initial.hiddenKeys }
                .let { titles ->
                    if (!covers || !sharesTitles) return@let titles.map { it.first }
                    // Only titles friends can actually see get a thumbnail: favorites alone when only those are shared.
                    val shown = if (initial.sharing.library == "friends") {
                        titles
                    } else {
                        titles.filter { (content) -> content.contentKey in initial.favoriteKeys }
                    }
                    val thumbs = withThumbnails(uid, shown).associateBy { it.contentKey }
                    titles.map { (content) -> thumbs[content.contentKey] ?: content }
                }
            // "Share reading progress" off: entries go without it (the server drops it too).
            val progress = if (initial.sharing.library == "friends" && initial.sharing.progress) {
                libraryProgress()
            } else {
                emptyMap()
            }
            for (kind in listOf("library", "favorites")) {
                val sharing = mutableState.value.sharing
                check(sharing.generation == initial.sharing.generation && repository.currentUid == uid)
                if ((if (kind == "library") sharing.library else sharing.favorites) != "friends") continue
                val chosen = if (kind == "library") {
                    eligible
                } else {
                    eligible.filter { it.contentKey in initial.favoriteKeys }
                }
                val existing = repository.loadEntries(uid, kind, sharing.generation)
                val wanted = if (kind == "library") progress else emptyMap()
                val published = existing.associateBy { it.content.contentKey }
                repository.publishEntries(
                    kind,
                    sharing.generation,
                    chosen.filter { published[it.contentKey] != SharedEntry(it, wanted[it.contentKey]) },
                    (published.keys - chosen.map { it.contentKey }.toSet()).toList(),
                    wanted,
                )
            }
            librarySyncPreference(uid).set("${initial.sharing.generation}:${System.currentTimeMillis()}")
        }
        publishing = if (quiet) {
            viewModelScope.launch {
                try {
                    work()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) { "Social library refresh failed" }
                }
            }
        } else {
            action(block = work)
        }
    }

    /**
     * Progress in my library titles that I opened (content key → progress). Never for titles from incognito sources;
     * Mihon's history doesn't say which chapters were read in global incognito, so the quiet refresh skips that time.
     */
    private suspend fun libraryProgress(): Map<String, SocialProgress> {
        val incognitoSources = mutableMapOf<Long, Boolean>()
        return getLibraryManga.await().mapNotNull { item ->
            if (incognitoSources.getOrPut(item.manga.source) { incognito.await(item.manga.source) }) {
                return@mapNotNull null
            }
            val key = SocialIdentity.content(item.manga)?.contentKey ?: return@mapNotNull null
            SocialProgress.of(item.readCount, item.totalChapters, item.lastRead)?.let { key to it }
        }.toMap()
    }

    /** "generation:millis" of my last library publish, for the periodic progress refresh. */
    private fun librarySyncPreference(uid: String): Preference<String> = preferences.getString(
        Preference.appStateKey("social_library_sync_${SocialIdentity.key(uid)}"),
        "",
    )

    /**
     * Friends see my progress only as of my last publish, so it's refreshed quietly at most every
     * [LIBRARY_REFRESH_MS], and only within the generation I last published in: after a privacy change, sharing
     * again stays a deliberate Publish.
     */
    private suspend fun refreshLibraryProgress(uid: String) {
        val sharing = state.first { it.sharing.generation > 0 }.sharing
        if (sharing.library != "friends" || repository.currentUid != uid) return
        // Global incognito: nothing read now may reach friends (and every title counts as incognito).
        if (incognito.await(null)) return
        val (generation, at) = librarySyncPreference(uid).get().split(':').mapNotNull { it.toLongOrNull() }
            .takeIf { it.size == 2 } ?: return
        if (generation != sharing.generation || System.currentTimeMillis() - at < LIBRARY_REFRESH_MS) return
        withTimeoutOrNull(PERMIT_WAIT_MS) { repository.projectionPermit.first { it } } ?: return
        if (publishing?.isActive != true) publishLibrary(quiet = true)
    }

    fun selectFriend(uid: String?) {
        friendObservation?.cancel()
        closeGrid()
        mutableState.update { it.copy(selectedFriend = null) }
        if (uid == null) return
        friendObservation = viewModelScope.launch {
            try {
                val details = repository.getFriend(uid)
                // Title pages drop at once what this friend no longer shares.
                friendReaders.onFriendSharing(details)
                mutableState.update { it.copy(selectedFriend = details) }
                coroutineScope {
                    launch { loadPreview(uid, details.sharing) }
                    if (details.sharing.stats == "friends") {
                        launch { repository.loadXp(uid)?.let { xp -> detail(uid) { it.copy(xp = xp) } } }
                    }
                    if (details.sharing.reviews) {
                        launch {
                            // Denied (just turned off) or an older backend: the section stays empty.
                            val reviews = try {
                                repository.loadReviews(uid)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                emptyList()
                            }
                            detail(uid) { it.copy(reviews = reviews) }
                        }
                    }
                    launch {
                        var ticks = 0
                        while (true) {
                            delay(15_000)
                            val fresh = repository.getFriend(uid)
                            if (fresh.sharing != details.sharing) {
                                friendReaders.onFriendSharing(fresh)
                                selectFriend(uid)
                                return@launch
                            }
                            // The library preview is a few reads; refresh it every 5 minutes, not every 15 s.
                            if (++ticks % PREVIEW_REFRESH_TICKS == 0) loadPreview(uid, fresh.sharing)
                            if (fresh.sharing.activity == "friends") {
                                val activity = try {
                                    repository.getActivity(uid)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    null
                                }
                                detail(uid) {
                                    it.copy(activity = activity?.first, activityExpiresAt = activity?.second)
                                }
                            }
                        }
                    }
                    if (details.sharing.feed == "friends") {
                        launch {
                            // Bounded (20) and rarely changing: refresh with the detail's periodic loop.
                            while (true) {
                                val feed = try {
                                    repository.loadFeed(uid, details.sharing.generation)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    emptyList()
                                }
                                detail(uid) { it.copy(feed = feed) }
                                delay(60_000)
                            }
                        }
                    }
                    if (details.sharing.presence == "friends") {
                        launch {
                            repository.watchPresence(uid).catch { detail(uid) { it.copy(online = null) } }
                                .collect { online -> detail(uid) { it.copy(online = online) } }
                        }
                    }
                    if (details.sharing.lastSeen == "friends") {
                        launch {
                            repository.watchLastSeen(uid).catch { detail(uid) { it.copy(lastSeen = null) } }
                                .collect { time -> detail(uid) { it.copy(lastSeen = time) } }
                        }
                    }
                    if (details.sharing.activity == "friends") {
                        launch {
                            repository.watchActivity(uid)
                                .catch { detail(uid) { it.copy(activity = null, activityExpiresAt = null) } }
                                .collect { activity ->
                                    detail(uid) {
                                        it.copy(activity = activity?.first, activityExpiresAt = activity?.second)
                                    }
                                }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(selectedFriend = null, feedback = SocialFeedback.ERROR) }
            }
        }
    }

    /** The profile shows the first titles A–Z and the total; the full list loads only in the grid. */
    private suspend fun loadPreview(uid: String, sharing: SocialSharing) {
        for (kind in listOf("library", "favorites")) {
            if ((if (kind == "library") sharing.library else sharing.favorites) != "friends") continue
            val (items, _) = repository.loadEntriesPage(uid, kind, sharing.generation, null, PREVIEW_SIZE)
            val count = try {
                repository.countEntries(uid, kind, sharing.generation)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            detail(uid) {
                if (kind == "library") {
                    it.copy(library = items, libraryCount = count)
                } else {
                    it.copy(favorites = items, favoritesCount = count)
                }
            }
        }
    }

    /** Opens a friend's library or favorites as a grid and loads its first page. */
    fun openGrid(kind: String) {
        val friend = mutableState.value.selectedFriend ?: return
        closeGrid()
        val total = if (kind == "library") friend.libraryCount else friend.favoritesCount
        mutableState.update {
            it.copy(grid = FriendGrid(friend.profile.uid, kind, friend.sharing.generation, total = total))
        }
        loadGrid(all = false)
    }

    /** Next page as the grid scrolls; [all] loads the rest (searching or filtering needs every title). */
    fun loadGrid(all: Boolean) {
        val grid = mutableState.value.grid ?: return
        if (all) gridWantsAll = true
        if (grid.loading || grid.complete) return
        gridJob = viewModelScope.launch {
            setGrid(grid) { it.copy(loading = true, failed = false) }
            try {
                do {
                    val current = mutableState.value.grid ?: return@launch
                    val (items, next) = repository.loadEntriesPage(
                        current.uid,
                        current.kind,
                        current.generation,
                        gridCursor,
                        GRID_PAGE_SIZE,
                    )
                    gridCursor = next
                    setGrid(current) { it.copy(items = it.items + items, complete = next == null) }
                } while (gridWantsAll && next != null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                setGrid(grid) { it.copy(failed = true) }
            } finally {
                setGrid(grid) { it.copy(loading = false) }
            }
        }
    }

    fun closeGrid() {
        gridJob?.cancel()
        gridJob = null
        gridCursor = null
        gridWantsAll = false
        mutableState.update { it.copy(grid = null) }
    }

    private fun setGrid(target: FriendGrid, transform: (FriendGrid) -> FriendGrid) = mutableState.update {
        val grid = it.grid
        if (grid != null && grid.uid == target.uid && grid.kind == target.kind) it.copy(grid = transform(grid)) else it
    }

    fun notificationPreference(kind: String) = if (kind == SocialNotifications.LOCK_SCREEN) {
        notificationPreferences.hideOnLockScreen()
    } else {
        notificationPreferences.enabled(kind)
    }

    /**
     * Installs the room's extension from my repos (Android shows its usual install prompt), then opens that source's
     * search for the room's title. Trust, if Mihon asks for it, stays a separate tap.
     */
    fun installRoomSource() {
        val guide = mutableState.value.roomGuide as? RoomSourceGuide.Installable ?: return
        val room = mutableState.value.rooms.firstOrNull { it.id == mutableState.value.selectedRoomId } ?: return
        viewModelScope.launch {
            val extension = extensionManager.availableExtensionsFlow.value.firstOrNull { it.pkgName == guide.pkgName }
                ?: return@launch
            try {
                extensionManager.installExtension(extension).collect { step ->
                    mutableState.update { it.copy(installing = step) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(installing = eu.kanade.tachiyomi.extension.model.InstallStep.Error.from(error))
                }
                return@launch
            }
            mutableState.update { it.copy(installing = null) }
            room.content.source?.let { source ->
                if (sourceManager.get(source.id) != null) {
                    // With the host's title URL, go straight to reading; otherwise search the new source.
                    val destination = roomTitleImporter.import(room.content, room.position?.chapterKey)
                    mutableState.update {
                        if (destination != null) {
                            it.copy(roomJump = destination)
                        } else {
                            it.copy(sourceJump = source.id to room.content.title)
                        }
                    }
                }
            }
        }
    }

    fun trustRoomSource() {
        val guide = mutableState.value.roomGuide as? RoomSourceGuide.Untrusted ?: return
        viewModelScope.launch {
            extensionManager.notLoadedExtensionsFlow.first().firstOrNull { it.pkgName == guide.pkgName }
                ?.let(extensionManager::trust)
        }
    }

    fun consumeSourceJump() = mutableState.update { it.copy(sourceJump = null) }

    /**
     * Opens a conversation with a friend. Messages are watched once the conversation exists (its first message
     * creates it), and my unread count is cleared whenever new messages arrive while it's open.
     */
    fun openChat(otherUid: String) {
        closeChat()
        val me = repository.currentUid ?: return
        mutableState.update { it.copy(chat = OpenChat(otherUid)) }
        chatJob = viewModelScope.launch {
            launch {
                state.map { s -> s.chats.firstOrNull { it.otherUid == otherUid }?.chatId }
                    .distinctUntilChanged()
                    .collectLatest { chatId ->
                        if (chatId == null) {
                            setChat(otherUid) { it.copy(loaded = true) }
                            return@collectLatest
                        }
                        repository.watchChatMessages(chatId)
                            .catch { setChat(otherUid) { it.copy(messages = emptyList(), loaded = true) } }
                            .collect { messages -> setChat(otherUid) { it.copy(messages = messages, loaded = true) } }
                    }
            }
            state.map { s -> s.chats.firstOrNull { it.otherUid == otherUid }?.unread ?: 0 }
                .distinctUntilChanged()
                .collect { unread ->
                    if (unread > 0 && repository.currentUid == me) {
                        try {
                            repository.markChatRead(otherUid)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            logcat(LogPriority.WARN, error) { "Social chat not marked read" }
                        }
                    }
                }
        }
    }

    fun closeChat() {
        chatJob?.cancel()
        chatJob = null
        mutableState.update { it.copy(chat = null) }
    }

    fun sendMessage(text: String, content: SocialContent? = null, replyTo: Long? = null) {
        val chat = mutableState.value.chat ?: return
        val body = text.replace('\n', ' ').trim().take(500).ifEmpty { null }
        if (body == null && content == null) return
        viewModelScope.launch {
            setChat(chat.otherUid) { it.copy(sending = true, failed = false) }
            try {
                repository.sendMessage(chat.otherUid, body, content, replyTo)
                setChat(chat.otherUid) { it.copy(sent = it.sent + 1) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                setChat(chat.otherUid) { it.copy(failed = true) }
            } finally {
                setChat(chat.otherUid) { it.copy(sending = false) }
            }
        }
    }

    /**
     * Opens a group chat: watches the group (null once I'm not a member any more) and its messages from when I
     * joined; clears my unread count while it's open.
     */
    fun openGroup(groupId: String) {
        closeChat()
        closeGroup()
        val me = repository.currentUid ?: return
        mutableState.update { it.copy(group = OpenGroup(groupId)) }
        groupJob = viewModelScope.launch {
            launch {
                repository.watchGroup(groupId)
                    .catch { emit(null) }
                    .collectLatest { group ->
                        if (group == null || group.member(me) == null) {
                            // Revoked: drop what was held, not just the header.
                            setGroup(groupId) {
                                it.copy(group = null, messages = emptyList(), gone = true, loaded = true)
                            }
                            return@collectLatest
                        }
                        setGroup(groupId) { it.copy(group = group, gone = false) }
                    }
            }
            launch {
                state.map { s -> s.group?.group?.member(me)?.since }
                    .distinctUntilChanged()
                    .collectLatest { since ->
                        if (since == null) return@collectLatest
                        repository.watchGroupMessages(groupId, since)
                            .catch { setGroup(groupId) { it.copy(loaded = true) } }
                            .collect { messages -> setGroup(groupId) { it.copy(messages = messages, loaded = true) } }
                    }
            }
            state.map { s -> s.groups.firstOrNull { it.groupId == groupId }?.unread ?: 0 }
                .distinctUntilChanged()
                .collect { unread ->
                    if (unread > 0 && repository.currentUid == me) {
                        try {
                            repository.markGroupRead(groupId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            logcat(LogPriority.WARN, error) { "Social group not marked read" }
                        }
                    }
                }
        }
    }

    fun closeGroup() {
        groupJob?.cancel()
        groupJob = null
        mutableState.update { it.copy(group = null) }
    }

    fun sendGroupMessage(text: String, content: SocialContent? = null, replyTo: Long? = null) {
        val group = mutableState.value.group ?: return
        val body = text.replace('\n', ' ').trim().take(500).ifEmpty { null }
        if (body == null && content == null) return
        viewModelScope.launch {
            setGroup(group.groupId) { it.copy(sending = true, failed = false) }
            try {
                repository.sendGroupMessage(group.groupId, body, content, replyTo)
                setGroup(group.groupId) { it.copy(sent = it.sent + 1) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                setGroup(group.groupId) { it.copy(failed = true) }
            } finally {
                setGroup(group.groupId) { it.copy(sending = false) }
            }
        }
    }

    /**
     * Toggles my reaction to message [seq] in the open group or chat. Shown at once; the next snapshot brings the
     * server's state. A failed call puts the previous reactions back, unless a newer snapshot already replaced what
     * was shown.
     */
    fun react(seq: Long, emoji: String) {
        val me = repository.currentUid ?: return
        if (!ReactionEmoji.isValid(emoji)) return
        // The group wins when both are set, as on screen.
        val groupId = mutableState.value.group?.groupId
        val otherUid = if (groupId == null) mutableState.value.chat?.otherUid ?: return else null
        val before = if (groupId != null) {
            mutableState.value.group?.messages?.firstOrNull { it.seq == seq }?.reactions
        } else {
            mutableState.value.chat?.messages?.firstOrNull { it.seq == seq }?.reactions
        } ?: return
        val shown = ChatReactions.toggle(before, me, emoji)

        // [expected]: replace only reactions that still look like this (null: always).
        fun applies(messageSeq: Long, current: Map<String, String>, expected: Map<String, String>?) =
            messageSeq == seq && (expected == null || current == expected)
        fun show(reactions: Map<String, String>, expected: Map<String, String>? = null) {
            if (groupId != null) {
                setGroup(groupId) { open ->
                    open.copy(
                        messages = open.messages.map {
                            if (applies(it.seq, it.reactions, expected)) it.copy(reactions = reactions) else it
                        },
                    )
                }
            } else if (otherUid != null) {
                setChat(otherUid) { open ->
                    open.copy(
                        messages = open.messages.map {
                            if (applies(it.seq, it.reactions, expected)) it.copy(reactions = reactions) else it
                        },
                    )
                }
            }
        }
        show(shown)
        viewModelScope.launch {
            try {
                if (groupId != null) {
                    repository.reactInGroup(groupId, seq, emoji)
                } else if (otherUid != null) {
                    repository.reactToMessage(otherUid, seq, emoji)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Social reaction not sent" }
                // No error line: that's for messages that didn't go out; the reaction just goes back.
                show(before, expected = shown)
            }
        }
    }

    fun createGroup(name: String, uids: List<String>) {
        val clean = name.replace('\n', ' ').trim().take(40)
        if (clean.isEmpty() || uids.isEmpty()) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            try {
                val groupId = repository.createGroup(clean, uids.distinct().take(GROUP_MAX_INVITES))
                openGroup(groupId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Social group not created" }
                mutableState.update { it.copy(feedback = SocialFeedback.ERROR) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun groupAction(block: suspend (String) -> Unit) {
        val groupId = mutableState.value.group?.groupId ?: return
        action(SocialFeedback.NONE) { block(groupId) }
    }

    private fun setGroup(groupId: String, transform: (OpenGroup) -> OpenGroup) = mutableState.update {
        val group = it.group
        if (group != null && group.groupId == groupId) it.copy(group = transform(group)) else it
    }

    private fun setChat(otherUid: String, transform: (OpenChat) -> OpenChat) = mutableState.update {
        val chat = it.chat
        if (chat != null && chat.otherUid == otherUid) it.copy(chat = transform(chat)) else it
    }

    /**
     * The exact AniList (or MyAnimeList) entry when the title is tracked there, so the web can show its cover
     * without guessing by name. Only the public ID is shared, never the tracking progress.
     */
    private suspend fun trackedId(mangaId: Long): Pair<String, String>? {
        val tracks = getTracks.await(mangaId).filter { it.remoteId > 0 }
        tracks.firstOrNull { it.trackerId == TRACKER_ANILIST }?.let { return "anilist" to it.remoteId.toString() }
        tracks.firstOrNull { it.trackerId == TRACKER_MAL }?.let { return "mal" to it.remoteId.toString() }
        return null
    }

    private fun thumbPreference(uid: String): Preference<Set<String>> = preferences.getStringSet(
        Preference.appStateKey("social_thumbs_${SocialIdentity.key(uid)}"),
    )

    /**
     * Uploads a small copy of each title's cached cover that isn't up there yet (the web can't load many sources'
     * covers itself) and flags the titles that have one. Titles without a cached cover just go without.
     */
    private suspend fun withThumbnails(
        uid: String,
        titles: List<Pair<SocialContent, tachiyomi.domain.manga.model.Manga>>,
    ): List<SocialContent> = coroutineScope {
        val done = thumbPreference(uid).get()
        val doneKeys = done.map { it.substringBefore(':') }.toSet()
        val permits = Semaphore(THUMB_UPLOADS)
        // Titles no longer shared (hidden, removed, unfavorited, incognito source) lose their thumbnail.
        val wanted = titles.map { it.first.contentKey }.toSet()
        val stale = done.filter { it.substringBefore(':') !in wanted }
        stale.forEach { repository.deleteCoverThumb(it.substringBefore(':')) }
        if (stale.isNotEmpty()) thumbPreference(uid).set(done - stale.toSet())
        val results = titles.map { (content, manga) ->
            async {
                val marker = CoverThumbnails.marker(content.contentKey, manga.thumbnailUrl)
                if (marker in done) return@async content.copy(thumb = true) to null
                val file = coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.isFile }
                    ?: return@async content.copy(thumb = content.contentKey in doneKeys) to null
                val uploaded = permits.withPermit {
                    try {
                        val jpeg =
                            withContext(Dispatchers.IO) { CoverThumbnails.encode(file) } ?: return@withPermit false
                        repository.uploadCoverThumb(content.contentKey, jpeg)
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Cover thumbnail not uploaded" }
                        false
                    }
                }
                content.copy(thumb = uploaded || content.contentKey in doneKeys) to marker.takeIf { uploaded }
            }
        }.awaitAll()
        val added = results.mapNotNull { it.second }
        if (added.isNotEmpty()) {
            val replaced = added.map { it.substringBefore(':') }.toSet()
            val kept = thumbPreference(uid).get()
            thumbPreference(uid).set(kept.filterNot { it.substringBefore(':') in replaced }.toSet() + added)
        }
        results.map { it.first }
    }

    /** Device setting; republishes so friends see (or stop seeing) covers right away. */
    fun setShareCovers(on: Boolean) {
        shareCoversPreference.set(on)
        val uid = repository.currentUid
        if (!on && uid != null) {
            // Stop means stop: the uploaded thumbnails go too.
            viewModelScope.launch {
                val uploaded = thumbPreference(uid).get()
                uploaded.forEach { repository.deleteCoverThumb(it.substringBefore(':')) }
                thumbPreference(uid).set(emptySet())
            }
        }
        val sharing = mutableState.value.sharing
        if (sharing.library == "friends" || sharing.favorites == "friends") publishLibrary()
    }

    private suspend fun watchStatus(actor: String, uid: String) = coroutineScope {
        fun status(transform: (FriendStatus) -> FriendStatus) = update(actor) {
            it.copy(friendStatuses = it.friendStatuses + (uid to transform(it.friendStatuses[uid] ?: FriendStatus())))
        }
        launch {
            repository.watchPresence(uid).catch { emit(null) }.collect { online -> status { it.copy(online = online) } }
        }
        launch {
            repository.watchLastSeen(uid).catch { emit(null) }.collect { time -> status { it.copy(lastSeen = time) } }
        }
        launch {
            repository.watchActivity(uid).catch { emit(null) }.collect { activity ->
                status { it.copy(reading = activity?.first, readingExpiresAt = activity?.second) }
            }
        }
        launch {
            // Readable only while they share activity with me and let friends drop in; denied reads mean none.
            repository.watchOpenRoom(uid).catch { emit(null) }.collect { card -> status { it.copy(openRoom = card) } }
        }
    }

    /** [localManga]'s projections and lookup tables, hashed on a background thread rather than in a state update. */
    private suspend fun showLocalLibrary(uid: String) {
        val tables = withContext(Dispatchers.Default) { FriendList.tables(localManga) }
        update(uid) { it.copy(library = tables.library, localIndex = tables.localIndex, covers = tables.covers) }
    }

    fun refresh() {
        refreshRequests.update { it + 1 }
        repository.currentUid?.let { uid -> viewModelScope.launch { loadRanking(uid) } }
        viewModelScope.launch {
            val uid = repository.currentUid ?: return@launch
            try {
                localManga = getFavorites.await()
                showLocalLibrary(uid)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                update(uid) { it.copy(feedback = SocialFeedback.ERROR) }
            }
        }
    }

    private suspend fun loadRanking(uid: String) {
        val day = FriendList.statsDay()
        val state = mutableState.value
        // My own counters are always readable to me; friends only see them when stats are shared.
        val me = repository.loadDailyStats(uid, day)
        val entries = buildList {
            me?.let {
                add(
                    RankingEntry(
                        uid,
                        state.profile?.displayName ?: "",
                        it.chapters,
                        it.pages,
                        me = true,
                        streak = it.streak,
                    ),
                )
            }
            state.friends.take(MAX_WATCHED_FRIENDS).forEach { friend ->
                repository.loadDailyStats(friend.uid, day)?.let {
                    add(
                        RankingEntry(
                            friend.uid,
                            friend.displayName,
                            it.chapters,
                            it.pages,
                            me = false,
                            streak = it.streak,
                            handle = friend.handle,
                            accent = friend.accent,
                            friend = true,
                        ),
                    )
                }
            }
        }
        val global = try {
            FriendList.global(
                repository.loadLeaderboard(day),
                me = uid,
                friends = state.friends.mapTo(mutableSetOf()) { it.uid },
                blocked = state.blocks.toSet(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Global ranking unavailable" }
            mutableState.value.globalRanking
        }
        val allTime = try {
            FriendList.allTime(
                repository.loadLeaderboard(LeaderboardEntry.ALL_TIME),
                me = uid,
                friends = state.friends.mapTo(mutableSetOf()) { it.uid },
                blocked = state.blocks.toSet(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "All-time ranking unavailable" }
            mutableState.value.allTimeRanking
        }
        // Weekly XP: mine is watched already; friends' XP docs are readable while they share stats.
        val week = xpWeek()
        val atRisk = mutableSetOf<String>()
        val weekly = buildList {
            val mine = mutableState.value.xp
            add(
                RankingEntry(
                    uid,
                    state.profile?.displayName ?: "",
                    chapters = mine?.weekChapters(week) ?: 0,
                    pages = 0,
                    me = true,
                    level = mine?.level,
                    weekXp = mine?.weekXp(week) ?: 0,
                ),
            )
            state.friends.take(MAX_WATCHED_FRIENDS).forEach { friend ->
                repository.loadXp(friend.uid)?.let { xp ->
                    if (SocialNudges.streakAtRisk(xp)) atRisk += friend.uid
                    add(
                        RankingEntry(
                            friend.uid,
                            friend.displayName,
                            chapters = xp.weekChapters(week),
                            pages = 0,
                            me = false,
                            handle = friend.handle,
                            accent = friend.accent,
                            friend = true,
                            level = xp.level,
                            weekXp = xp.weekXp(week),
                        ),
                    )
                }
            }
        }
        update(uid) {
            it.copy(
                ranking = FriendList.rank(entries),
                globalRanking = global,
                weeklyRanking = FriendList.weekly(weekly),
                allTimeRanking = allTime,
                streakAtRisk = atRisk,
                nudgedAt = SocialNudges.decode(nudgePreference(uid).get(), System.currentTimeMillis()),
            )
        }
    }

    /** "Got it" on the global ranking notice; [optOut] also turns the ranking off. */
    fun dismissRankingNotice(optOut: Boolean) {
        val uid = repository.currentUid ?: return
        rankingNoticePreference(uid).set(true)
        if (optOut) changeSharing(mutableState.value.sharing.copy(globalRanking = false))
    }

    /** “Join” on a friend's open room: drop in without an invite (the card showed who may see me first). */
    fun dropIn(card: OpenRoomCard) = action {
        val joined = repository.roomAction("join", roomId = card.roomId)
        sessions.select(joined.id)
        openRoomTitle(joined)
    }

    fun joinRoom(room: SocialRoom) = action {
        val me = repository.currentUid
        val selected = if (me != null && room.isInvited(me)) {
            repository.roomAction("join", roomId = room.id)
        } else {
            checkNotNull(repository.getRoom(room.id))
        }
        sessions.select(selected.id)
        openRoomTitle(selected)
    }

    /**
     * Opens the room's title at the host's chapter: from my library, or in one step from the host's source when it's
     * installed here (without adding it to the library). The screen shows the source guide otherwise.
     */
    private suspend fun openRoomTitle(room: SocialRoom): Boolean {
        if (localManga.isEmpty()) localManga = getFavorites.await()
        val destination = RoomDestinations.resolve(room.content, room.position?.chapterKey, localManga) {
            getChaptersByMangaId.await(it)
        } ?: roomTitleImporter.import(room.content, room.position?.chapterKey) ?: return false
        mutableState.update { it.copy(roomJump = destination) }
        return true
    }

    /**
     * "Read together" from outside the reader: opens my copy of [contentKey] at its next unread chapter and leaves a
     * pending invite; the reader starts the room once the chapter has loaded (with the first friend, then the rest).
     */
    fun startReadTogether(contentKey: String, friendUids: List<String>) = action(SocialFeedback.NONE) {
        val me = checkNotNull(repository.currentUid)
        val friends = mutableState.value.friends.mapTo(mutableSetOf()) { it.uid }
        if (localManga.isEmpty()) localManga = getFavorites.await()
        val manga = checkNotNull(localManga.firstOrNull { SocialIdentity.content(it)?.contentKey == contentKey })
        check(!incognito.await(manga.source)) { "Incognito source" }
        val pending = PendingRoomInvite(contentKey, friendUids, System.currentTimeMillis())
        check(pending.targets(friends, me).isNotEmpty()) { "Choose an accepted friend" }
        val chapter = checkNotNull(ReadTogetherStart.nextChapter(getChaptersByMangaId.await(manga.id)))
        sessions.setPending(pending)
        mutableState.update { it.copy(roomJump = RoomDestination(manga.id, chapter.id)) }
    }

    /** "Read together" in a group: my friends among its members (up to five) are invited. */
    fun groupReadTogether(contentKey: String) {
        val me = repository.currentUid ?: return
        val group = mutableState.value.group?.group ?: return
        val friends = mutableState.value.friends.mapTo(mutableSetOf()) { it.uid }
        val uids = group.members.map { it.uid }.filter { it != me && it in friends }
        startReadTogether(contentKey, uids.take(PendingRoomInvite.MAX_INVITES))
    }

    /** "I'd read this with you": adds or removes my private wish; a match is announced by the server. */
    fun toggleReadWish(otherUid: String, content: SocialContent) = action(SocialFeedback.NONE) {
        val wished = mutableState.value.readWishes.any {
            it.otherUid == otherUid && it.contentKey == content.contentKey
        }
        repository.readWish(if (wished) "remove" else "add", otherUid, content.copy(source = null))
    }

    /** Sends a nudge; remembered for 24 h on this phone so the button stays "Nudged". */
    fun nudge(friendUid: String) {
        val me = repository.currentUid ?: return
        val now = System.currentTimeMillis()
        if (SocialNudges.isCoolingDown(mutableState.value.nudgedAt[friendUid], now)) return
        action(SocialFeedback.NONE) {
            repository.nudge(friendUid)
            val nudges = SocialNudges.decode(nudgePreference(me).get(), now) + (friendUid to now)
            nudgePreference(me).set(SocialNudges.encode(nudges))
            update(me) { it.copy(nudgedAt = nudges) }
        }
    }

    private fun nudgePreference(uid: String): Preference<Set<String>> = preferences.getStringSet(
        Preference.appStateKey("social_nudges_${SocialIdentity.key(uid)}"),
        emptySet(),
    )

    fun selectTab(tab: FriendsTab) = mutableState.update { it.copy(tab = tab) }

    /** A notification's target: waits for the account, then switches tab and opens the chat, group, room or profile. */
    fun openRoute(route: FriendsRoute) {
        viewModelScope.launch {
            val uid = withTimeoutOrNull(ROUTE_WAIT_MS) { state.first { it.account != null } }?.account?.uid
                ?: return@launch
            selectTab(route.tab)
            when (route) {
                is FriendsRoute.Chat -> openChat(route.uid)
                is FriendsRoute.Group -> openGroup(route.groupId)
                is FriendsRoute.Profile -> selectFriend(route.uid)
                is FriendsRoute.Room -> if (route.join) {
                    val room = withTimeoutOrNull(ROUTE_WAIT_MS) {
                        state.first { s -> s.rooms.any { it.id == route.roomId } }
                    }?.rooms?.firstOrNull { it.id == route.roomId }
                    if (room != null && repository.currentUid == uid) joinRoom(room)
                }
                FriendsRoute.Requests, FriendsRoute.Home -> Unit
            }
        }
    }

    fun openRoom(room: SocialRoom) = action(SocialFeedback.NONE) {
        check(openRoomTitle(room)) { "Room title unavailable" }
    }

    fun consumeRoomJump() = mutableState.update { it.copy(roomJump = null) }

    fun closeRoom(room: SocialRoom) = action {
        repository.roomAction("close", roomId = room.id)
        sessions.clear(room.id)
    }

    fun dispatch(intent: FriendsIntent) {
        when (intent) {
            is FriendsIntent.SignIn -> signIn(intent.email, intent.password, intent.register)
            is FriendsIntent.ResetPassword -> resetPassword(intent.email)
            FriendsIntent.VerifyEmail -> verifyEmail()
            FriendsIntent.RefreshAccount -> refreshAccount()
            FriendsIntent.SignOut -> signOut()
            is FriendsIntent.SaveProfile -> saveProfile(intent.handle, intent.displayName)
            is FriendsIntent.RequestFriend -> requestFriend(intent.handle)
            is FriendsIntent.LookupInvite -> lookupInvite(intent.handle)
            FriendsIntent.SendInvite -> sendInvite()
            FriendsIntent.DismissInvite -> dismissInvite()
            is FriendsIntent.FriendAction -> friendAction(intent.action, intent.uid)
            is FriendsIntent.ChangeSharing -> changeSharing(intent.settings, intent.hideKey)
            is FriendsIntent.ToggleFavorite -> toggleFavorite(intent.key)
            FriendsIntent.PublishLibrary -> publishLibrary()
            is FriendsIntent.SelectFriend -> selectFriend(intent.uid)
            is FriendsIntent.JoinRoom -> joinRoom(intent.room)
            is FriendsIntent.DropIn -> dropIn(intent.card)
            is FriendsIntent.OpenRoom -> openRoom(intent.room)
            is FriendsIntent.CloseRoom -> closeRoom(intent.room)
            FriendsIntent.Refresh -> refresh()
            is FriendsIntent.DeleteAccount -> deleteAccount(intent.password)
            FriendsIntent.CheckConnection -> viewModelScope.launch {
                mutableState.update { it.copy(connectionCheck = emptyList(), checkingConnection = true) }
                val results = try {
                    repository.connectionCheck()
                } finally {
                    mutableState.update { it.copy(checkingConnection = false) }
                }
                mutableState.update { it.copy(connectionCheck = results) }
            }
            is FriendsIntent.SaveStyle -> saveStyle(intent.bio, intent.accent)
            is FriendsIntent.PickMedia -> pickMedia(intent.kind, intent.uri)
            is FriendsIntent.RemoveMedia -> removeMedia(intent.kind)
            is FriendsIntent.RestoreHidden -> action { repository.unhideTitle(intent.key) }
            is FriendsIntent.DismissRankingNotice -> dismissRankingNotice(intent.optOut)
            is FriendsIntent.OpenGrid -> openGrid(intent.kind)
            is FriendsIntent.LoadGrid -> loadGrid(intent.all)
            FriendsIntent.CloseGrid -> closeGrid()
            FriendsIntent.InstallRoomSource -> installRoomSource()
            FriendsIntent.TrustRoomSource -> trustRoomSource()
            is FriendsIntent.AskToRead -> action { repository.askToReadAlong(intent.uid) }
            is FriendsIntent.DeclineJoin -> action { repository.declineJoinRequest(intent.uid) }
            is FriendsIntent.AcceptJoin -> action {
                // From here only into a room I already host; otherwise the request pops up in the reader.
                val me = checkNotNull(repository.currentUid)
                val room =
                    checkNotNull(mutableState.value.rooms.firstOrNull { it.hostUid == me && it.state != "closed" })
                repository.inviteToRoom(room.id, intent.uid)
            }
            is FriendsIntent.OpenChat -> openChat(intent.uid)
            FriendsIntent.CloseChat -> closeChat()
            is FriendsIntent.SendMessage -> sendMessage(intent.text, replyTo = intent.replyTo)
            is FriendsIntent.React -> react(intent.seq, intent.emoji)
            is FriendsIntent.ShareCovers -> setShareCovers(intent.on)
            is FriendsIntent.Recommend -> {
                // Add the public cover link, as for shared libraries (when "Share covers" is on).
                val manga = localManga.firstOrNull {
                    SocialIdentity.content(it)?.contentKey == intent.content.contentKey
                }
                val content = if (shareCoversPreference.get() && manga != null) {
                    intent.content.copy(coverUrl = SocialIdentity.publicCover(manga.thumbnailUrl))
                } else {
                    intent.content
                }
                if (mutableState.value.group != null) sendGroupMessage("", content) else sendMessage("", content)
            }
            is FriendsIntent.OpenGroup -> openGroup(intent.groupId)
            FriendsIntent.CloseGroup -> closeGroup()
            is FriendsIntent.SendGroupMessage -> sendGroupMessage(intent.text, replyTo = intent.replyTo)
            is FriendsIntent.CreateGroup -> createGroup(intent.name, intent.uids)
            is FriendsIntent.AddToGroup -> groupAction { repository.addToGroup(it, intent.uids) }
            is FriendsIntent.RemoveFromGroup -> groupAction { repository.removeFromGroup(it, intent.uid) }
            is FriendsIntent.RenameGroup -> groupAction { repository.renameGroup(it, intent.name.trim().take(40)) }
            FriendsIntent.LeaveGroup -> groupAction {
                repository.leaveGroup(it)
                closeGroup()
            }
            is FriendsIntent.SelectTab -> selectTab(intent.tab)
            is FriendsIntent.ReadTogether -> startReadTogether(intent.contentKey, intent.uids)
            is FriendsIntent.GroupReadTogether -> groupReadTogether(intent.contentKey)
            is FriendsIntent.Nudge -> nudge(intent.uid)
            is FriendsIntent.ToggleReadWish -> toggleReadWish(intent.otherUid, intent.content)
        }
    }

    private fun rankingNoticePreference(uid: String): Preference<Boolean> = preferences.getBoolean(
        Preference.appStateKey("social_ranking_notice_${SocialIdentity.key(uid)}"),
        false,
    )

    private fun favoritePreference(uid: String): Preference<Set<String>> = preferences.getStringSet(
        Preference.appStateKey("social_favorites_${SocialIdentity.key(uid)}"),
    )

    private fun action(
        success: SocialFeedback = SocialFeedback.SUCCESS,
        block: suspend () -> Unit,
    ): Job = viewModelScope.launch {
        val actor = repository.currentUid
        mutableState.update { it.copy(busy = true, feedback = SocialFeedback.NONE) }
        try {
            block()
            if (actor == null || actor == repository.currentUid) mutableState.update { it.copy(feedback = success) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (actor == null || actor == repository.currentUid) {
                mutableState.update { it.copy(feedback = SocialFeedback.ERROR) }
            }
        } finally {
            if (actor == null || actor == repository.currentUid) mutableState.update { it.copy(busy = false) }
        }
    }

    private fun update(uid: String, transform: (FriendsState) -> FriendsState) = mutableState.update {
        if (it.account?.uid == uid && repository.currentUid == uid) transform(it) else it
    }

    private fun detail(uid: String, transform: (SocialFriendDetails) -> SocialFriendDetails) = mutableState.update {
        if (it.selectedFriend?.profile?.uid == uid) it.copy(selectedFriend = transform(it.selectedFriend)) else it
    }

    private fun <T> Flow<T>.safe(
        uid: String,
        clear: (FriendsState) -> FriendsState = { it },
    ): Flow<T> = catch { error ->
        if (error is CancellationException) throw error
        update(uid) { clear(it).copy(feedback = SocialFeedback.ERROR) }
    }
}

private const val MAX_WATCHED_FRIENDS = 50
private const val PREVIEW_SIZE = 12L
private const val PREVIEW_REFRESH_TICKS = 20
private const val GRID_PAGE_SIZE = 60L

/** How long a notification's target waits for the account and its rooms to load. */
private const val ROUTE_WAIT_MS = 15_000L

/** Reads at most [limit] bytes (API-level independent `readNBytes`). */
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read <= 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** A new group holds me plus up to 19 friends (backend GROUP_MAX_MEMBERS = 20). */
private const val GROUP_MAX_INVITES = 19

/** Parallel thumbnail uploads while sharing the library. */
private const val THUMB_UPLOADS = 4

/** Mihon's tracker IDs (TrackerManager): MyAnimeList 1, AniList 2. */
private const val TRACKER_MAL = 1L
private const val TRACKER_ANILIST = 2L

/** Quiet library refresh (progress): at most every 6 hours; wait this long for the projection permit. */
private const val LIBRARY_REFRESH_MS = 6 * 3_600_000L
private const val PERMIT_WAIT_MS = 30_000L
