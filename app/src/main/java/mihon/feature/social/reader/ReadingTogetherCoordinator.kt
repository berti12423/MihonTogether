package mihon.feature.social.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.feature.social.JoinRequest
import mihon.feature.social.RoomMessage
import mihon.feature.social.SocialContent
import mihon.feature.social.SocialFeedItem
import mihon.feature.social.SocialPosition
import mihon.feature.social.SocialProfile
import mihon.feature.social.SocialRepository
import mihon.feature.social.SocialRoom
import mihon.feature.social.SocialRoomUnavailable
import mihon.feature.social.SocialSharing
import mihon.feature.social.SocialSource
import tachiyomi.core.common.util.system.logcat
import kotlin.math.floor

enum class ReaderSocialStatus {
    SIGNED_OUT,
    PRIVATE,

    /** Like [PRIVATE], but caused only by the invisible-mode setting, so the UI can name the fix. */
    INVISIBLE,
    READY,
    INVITED,
    ACTIVE,
    WAITING,
    EXPIRED,
    REVOKED,
    MISMATCH,

    /** In a room but reading another title than the room's: chat and reactions keep working. */
    ELSEWHERE,
    CONFIRM_MAPPING,
    LOADING,
    ERROR,
    RETRACTION_PENDING,
}

val ReaderSocialStatus.isPaused: Boolean
    get() = this == ReaderSocialStatus.PRIVATE || this == ReaderSocialStatus.INVISIBLE

data class ReaderSocialState(
    val foreground: Boolean = false,
    val signedIn: Boolean = false,
    val status: ReaderSocialStatus = ReaderSocialStatus.SIGNED_OUT,
    val room: SocialRoom? = null,
    val local: LocalReading? = null,
    val friends: List<SocialProfile> = emptyList(),
    val following: Boolean = false,
    val mappingConfirmed: Boolean = false,
    val error: String? = null,
    val selectedRoomId: String? = null,
    val isHost: Boolean = false,
    val busy: Boolean = false,
    val selfUid: String? = null,
    val messages: List<RoomMessage> = emptyList(),
    val chatError: Boolean = false,
    /** Guest on the room's title but not following: offer to read along. */
    val followOffer: Boolean = false,
    /** The reader chose to only chat and react in this room, without following. */
    val chatOnly: Boolean = false,
    /** Friends asking to read along with me (shown as a prompt while I read). */
    val joinRequests: List<JoinRequest> = emptyList(),
    /** Ready check: each member's latest ready/wait since the host's last chapter change (uid → kind). */
    val signals: Map<String, String> = emptyMap(),
    /** “Let friends drop in” for the next room I start (rooms I host carry their own `open` flag). */
    val dropInOnCreate: Boolean = false,
)

/**
 * The ready check as it stands: each member's latest "ready" or "wait" sent after [floorSeq] (the newest message
 * when the host last changed chapter) and within [maxAgeMs].
 */
object RoomSignals {
    const val MAX_AGE_MS = 15 * 60_000L

    fun current(
        messages: List<RoomMessage>,
        floorSeq: Long,
        now: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): Map<String, String> =
        messages.filter { it.kind in RoomMessage.SIGNALS && it.seq > floorSeq && now - it.createdAt <= maxAgeMs }
            .sortedBy { it.seq }
            .associate { it.senderUid to it.kind }
}

/**
 * A room message to pop on screen: [mine] pops from the right edge, everyone else's from the left with
 * [senderName] on it. [event] is a server room event (joined, left, removed, host) about [senderName].
 */
data class RoomPopup(
    val seq: Long,
    val emoji: String?,
    val text: String?,
    val mine: Boolean,
    val senderName: String? = null,
    val event: String? = null,
)

data class RemoteReaderMove(
    val roomId: String,
    val epoch: Long,
    val sequence: Long,
    val chapterId: Long,
    val pageIndex: Int,
    /** Set when the host reads in long-strip mode: follow the scroll instead of jumping to [pageIndex]. */
    val stripPosition: Double? = null,
)

/** Long-strip positions are shared in steps of 1/200 page. */
private const val STRIP_STEPS = 200.0

/**
 * At most one host position write per interval; the follower extrapolates and glides between them (StripFollower).
 * Firestore sustains about one write per second per document, so this stays near that rather than going faster.
 */
private const val POSITION_WRITE_INTERVAL_MS = 500L

/**
 * The reader ViewModel owns intents; Activity STARTED owns runForeground and all listeners/writers.
 * Render state survives recreation. Buffered remote moves have one Activity consumer and are revalidated.
 */
class ReadingTogetherCoordinator(
    private val repository: SocialRepository,
    private val sessions: ReadingSessionStore,
    /** Names the source behind a manga's source ID for room invites (null for unknown or private sources). */
    private val describeSource: suspend (Long) -> SocialSource? = { null },
    private val loadMatchedChapter: suspend (SocialContent, String) -> LocalReading?,
) {
    private val mutableState = MutableStateFlow(ReaderSocialState())
    val state = mutableState.asStateFlow()
    private val local = MutableStateFlow<LocalReading?>(null)
    private val incognito = MutableStateFlow(true)
    private val mapping = MutableStateFlow<String?>(null)
    private val follow = MutableStateFlow(false)
    private val chatOnly = MutableStateFlow(false)

    /** Edition (source + edition) whose page order the reader confirmed; covers every chapter of it. */
    private var consentedEdition: String? = null
    private val moves = Channel<RemoteReaderMove>(Channel.CONFLATED)
    val remoteMoves = moves.receiveAsFlow()
    private val popupEvents = MutableSharedFlow<RoomPopup>(extraBufferCapacity = 16)
    val popups = popupEvents.asSharedFlow()
    private val levelUpEvents = MutableSharedFlow<mihon.feature.social.XpReward>(extraBufferCapacity = 4)

    /** A finished chapter took me to a new level. */
    val levelUps = levelUpEvents.asSharedFlow()
    private val echo = RemotePageEcho()
    private var settings: SocialSharing? = null
    private var hiddenKeys: Set<String>? = null
    private var actor: String? = null

    fun setIncognito(value: Boolean) {
        incognito.value = value
        if (value) stopLocally(ReaderSocialStatus.PRIVATE)
    }

    fun pageSelected(selected: LocalReading) {
        val previous = local.value
        // A page change in long-strip mode keeps the latest scroll position; stripScrolled refines it.
        val strip = previous?.position?.stripPosition
            ?.takeIf { previous.position.chapterKey == selected.position.chapterKey }
        val reading = if (strip != null && selected.position.stripPosition == null) {
            selected.copy(position = selected.position.copy(stripPosition = strip))
        } else {
            selected
        }
        if (previous?.position?.pageMappingKey != reading.position.pageMappingKey) {
            mapping.value = reading.position.pageMappingKey.takeIf { consentedEdition == editionOf(reading.content) }
        }
        local.value = reading
        mutableState.update {
            it.copy(local = reading, mappingConfirmed = mapping.value == reading.position.pageMappingKey)
        }
        // Guest movement never writes room position. This additionally fences the viewer callback.
        if (echo.consume(reading.position.chapterKey, reading.position.pageIndex)) return
    }

    /**
     * Long-strip scroll of the current chapter, in pages from its start to the top of the screen. Quantized to
     * [STRIP_STEPS] per page so a scroll only changes the position (and a host write) every few pixels' worth.
     */
    fun stripScrolled(chapterId: Long, value: Double) {
        val current = local.value ?: return
        if (current.chapterId != chapterId) return
        val quantized = (floor(value * STRIP_STEPS) / STRIP_STEPS).coerceIn(
            0.0,
            current.position.pageCount - 1.0 / STRIP_STEPS,
        )
        if (current.position.stripPosition == quantized) return
        val next = current.copy(position = current.position.copy(stripPosition = quantized))
        local.value = next
        mutableState.update { it.copy(local = next) }
    }

    /** Leaving long-strip mode: page turns alone describe the position again. */
    fun stripEnded() {
        val current = local.value ?: return
        if (current.position.stripPosition == null) return
        val next = current.copy(position = current.position.copy(stripPosition = null))
        local.value = next
        mutableState.update { it.copy(local = next) }
    }

    fun pageUnavailable() {
        local.value = null
        mapping.value = null
        stopLocally(ReaderSocialStatus.MISMATCH)
        mutableState.update { it.copy(local = null, mappingConfirmed = false) }
    }

    fun confirmPageOrder() {
        val current = local.value ?: return
        consentedEdition = editionOf(current.content)
        mapping.value = current.position.pageMappingKey
        mutableState.update { it.copy(mappingConfirmed = true, error = null) }
    }

    /** One tap from the follow prompt: confirms this edition's page order and starts following. */
    fun acceptFollow() {
        confirmPageOrder()
        setChatOnly(false)
        setFollowing(true)
    }

    /** Stay in the room for chat and reactions only; reading position is not followed. */
    fun setChatOnly(value: Boolean) {
        if (value) setFollowing(false)
        chatOnly.value = value
        mutableState.update { it.copy(chatOnly = value, followOffer = it.followOffer && !value) }
    }

    fun setFollowing(value: Boolean) {
        // Render state first: on Main.immediate the room collector can emit a move inline from follow.value.
        mutableState.update { it.copy(following = value, error = null) }
        follow.value = value
    }

    fun pause() {
        mutableState.update { it.copy(foreground = false) }
        stopLocally(if (state.value.signedIn) ReaderSocialStatus.WAITING else ReaderSocialStatus.SIGNED_OUT)
    }

    fun consumeMove(move: RemoteReaderMove): Boolean {
        val current = state.value
        val room = current.room ?: return false
        val reading = current.local ?: return false
        if (!current.foreground || incognito.value || !follow.value ||
            room.id != move.roomId || room.epoch != move.epoch || room.sequence != move.sequence ||
            reading.chapterId != move.chapterId || room.leaseExpiresAt <= System.currentTimeMillis() ||
            !ReadingCompatibility.canFollow(reading, room, mapping.value)
        ) {
            return false
        }
        echo.arm(reading.position.chapterKey, move.pageIndex)
        return true
    }

    /**
     * Best-effort social side effects of finishing a chapter in the reader: a feed entry (if the feed is shared and
     * the title isn't hidden) and today's ranking counters (if stats are shared with friends, or for the global ranking
     * unless opted out). Nothing happens while invisible or on an incognito source. Failures are dropped (no outbox), like activity.
     */
    suspend fun publishChapterRead(reading: LocalReading) {
        val consent = settings
        val hidden = hiddenKeys
        val base = consent != null && hidden != null && actor != null && actor == repository.currentUid &&
            !incognito.value && !consent.invisible
        val feed = base && consent?.feed == "friends" && hidden?.contains(reading.content.contentKey) == false
        // Recorded whenever the reader isn't private: stats and the ranking need consent (checked by the backend),
        // XP is counted for me either way.
        val stats = base
        logcat(LogPriority.DEBUG) {
            "Social chapter_read feed=$feed stats=$stats settings=${consent != null} hidden=${hidden != null} " +
                "incognito=${incognito.value} invisible=${consent?.invisible}"
        }
        if (consent == null) return
        if (feed) {
            bestEffort("feed chapter_read") {
                repository.publishFeed(
                    consent.generation,
                    SocialFeedItem.KIND_CHAPTER_READ,
                    reading.content,
                    reading.position.chapterLabel,
                )
            }
        }
        if (stats) {
            bestEffort("daily stats") {
                // Reading together earns a bonus when I'm in a room with someone else who joined.
                val room = state.value.room?.takeIf { room ->
                    room.members.values.count { it.state == mihon.feature.social.RoomMember.JOINED } >= 2
                }
                val reward = repository.recordChapter(consent.generation, reading.position.pageCount, room?.id)
                if (reward != null && reward.notable) {
                    levelUpEvents.tryEmit(reward)
                }
            }
        }
    }

    private suspend fun bestEffort(what: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A missed history entry or count is acceptable; never retry stale consent.
            logcat(LogPriority.WARN, error) { "Social $what not published" }
        }
    }

    /** Ready check: tell the room I'm ready for the next chapter, or ask them to wait. */
    suspend fun sendSignal(kind: String) {
        val roomId = sessions.selectedRoomId.value ?: return
        val current = state.value
        if (current.room?.state != "active" || !current.foreground || kind !in RoomMessage.SIGNALS) return
        mutableState.update { it.copy(chatError = false) }
        try {
            repository.sendRoomSignal(roomId, kind)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Room signal not sent" }
            mutableState.update { it.copy(chatError = true) }
        }
    }

    /** Sends a chat line or a reaction to the selected live room. Failures (e.g. rate limits) only flag the chat. */
    suspend fun sendChat(text: String?, emoji: String?) {
        val roomId = sessions.selectedRoomId.value ?: return
        val current = state.value
        if (current.room?.state != "active" || !current.foreground) return
        val body = text?.trim()?.take(RoomMessage.MAX_TEXT)?.takeIf { it.isNotEmpty() }
        if (body == null && emoji == null) return
        mutableState.update { it.copy(chatError = false) }
        try {
            repository.sendRoomMessage(roomId, if (emoji == null) body else null, emoji)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update { it.copy(chatError = true) }
        }
    }
    suspend fun invite(friendUid: String) {
        perform {
            val reading = checkNotNull(local.value) { "Wait for a supported chapter to load" }
            check(state.value.foreground && !incognito.value && !state.value.status.isPaused)
            // Inviting from this chapter is the host's explicit page-order consent for this edition.
            confirmPageOrder()
            check(state.value.friends.any { it.uid == friendUid }) { "Choose an accepted friend" }
            val current = sessions.selectedRoomId.value
            if (current != null) {
                // Already in a room: the host adds this friend to it (up to six people).
                val room = checkNotNull(state.value.room) { "Wait for the room to load" }
                check(room.hostUid == actor) { "Only the host can invite" }
                repository.inviteToRoom(current, friendUid)
                return@perform
            }
            val room = repository.roomAction(
                "create",
                otherUid = friendUid,
                sessionId = sessions.writerSessionId,
                content = roomContent(reading),
                position = reading.position,
                open = true.takeIf { state.value.dropInOnCreate },
            )
            sessions.select(room.id)
        }
    }

    /** The room's title as shared: the content plus which source (and title URL) friends need to open it. */
    private suspend fun roomContent(reading: LocalReading): SocialContent =
        reading.content.copy(source = reading.sourceId?.let { describeSource(it) }?.copy(url = reading.mangaUrl))

    /**
     * "Read together" picked outside the reader: once a supported chapter of the picked title is loaded, start a room
     * with the first friend and invite the rest (same create/invite paths and page-order consent as inviting here).
     */
    private suspend fun startPendingRoom(uid: String) {
        val pending = sessions.pendingInvite.first { it != null } ?: return
        if (pending.expired(System.currentTimeMillis())) {
            sessions.takePending(pending)
            return
        }
        val reading = local.first { it?.content?.contentKey == pending.contentKey } ?: return
        // Friends load right after sign-in; give them a moment before deciding who can be invited.
        val friends = withTimeoutOrNull(FRIENDS_WAIT_MS) {
            state.first { current -> current.friends.any { it.uid in pending.friendUids } }
        }?.friends.orEmpty().mapTo(mutableSetOf()) { it.uid }
        val targets = pending.targets(friends, uid)
        if (!sessions.takePending(pending) || targets.isEmpty()) return
        perform {
            check(state.value.foreground && !incognito.value && !state.value.status.isPaused)
            val current = local.value?.takeIf { it.content.contentKey == reading.content.contentKey }
                ?: error("Wait for a supported chapter to load")
            // Starting from this chapter is the host's explicit page-order consent for this edition.
            confirmPageOrder()
            val existing = sessions.selectedRoomId.value
            check(existing == null || state.value.room?.hostUid == uid) { "Only the host can invite" }
            val roomId = if (existing != null) {
                existing
            } else {
                repository.roomAction(
                    "create",
                    otherUid = targets.first(),
                    sessionId = sessions.writerSessionId,
                    content = roomContent(current),
                    position = current.position,
                    open = true.takeIf { state.value.dropInOnCreate },
                ).id.also(sessions::select)
            }
            val rest = if (existing != null) targets else targets.drop(1)
            rest.forEach { friendUid ->
                try {
                    repository.inviteToRoom(roomId, friendUid)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // One friend failing (full room, ended friendship) doesn't stop the others.
                    logcat(LogPriority.WARN, error) { "Room invite failed" }
                }
            }
        }
    }

    /** Accepting "ask to read along": invites them (into my room, or a new one from this chapter). */
    suspend fun acceptJoinRequest(requesterUid: String) {
        mutableState.update {
            it.copy(joinRequests = it.joinRequests.filterNot { r -> r.requesterUid == requesterUid })
        }
        invite(requesterUid)
    }

    suspend fun declineJoinRequest(requesterUid: String) {
        mutableState.update {
            it.copy(joinRequests = it.joinRequests.filterNot { r -> r.requesterUid == requesterUid })
        }
        perform { repository.declineJoinRequest(requesterUid) }
    }

    /**
     * “Let friends drop in”: friends who see my activity can join my room without an invite. Changes the room I host,
     * or the choice for the next room I start. The backend refuses it while my activity isn't shared with friends.
     */
    suspend fun setDropIn(value: Boolean) {
        val roomId = sessions.selectedRoomId.value
        val room = state.value.room
        if (roomId == null || room == null || room.hostUid != actor) {
            mutableState.update { it.copy(dropInOnCreate = value) }
            return
        }
        perform {
            check(state.value.foreground && !incognito.value && !state.value.status.isPaused)
            repository.setRoomOpen(roomId, value)
        }
    }

    /** Host only: removes someone from the room. */
    suspend fun kick(memberUid: String) {
        val roomId = sessions.selectedRoomId.value ?: return
        perform { repository.removeFromRoom(roomId, memberUid) }
    }

    suspend fun leave() {
        val roomId = sessions.selectedRoomId.value ?: return
        stopLocally(ReaderSocialStatus.READY)
        sessions.clear(roomId)
        mutableState.update { it.copy(selectedRoomId = null) }
        performRetraction { repository.roomAction("leave", roomId = roomId, sessionId = sessions.writerSessionId) }
    }

    suspend fun finish() {
        pause()
        leave()
        retractActivity()
    }

    suspend fun runForeground() {
        mutableState.update { it.copy(foreground = true) }
        try {
            repository.authChanges().collectLatest { account ->
                if (actor != null && actor != account?.uid) {
                    sessions.clear()
                    mapping.value = null
                    mutableState.update { it.copy(mappingConfirmed = false, selectedRoomId = null) }
                }
                actor = account?.uid
                mutableState.update { it.copy(selfUid = account?.uid) }
                settings = null
                hiddenKeys = null
                stopLocally(if (account == null) ReaderSocialStatus.SIGNED_OUT else ReaderSocialStatus.WAITING)
                mutableState.update { it.copy(signedIn = account != null, friends = emptyList()) }
                if (account != null) {
                    try {
                        observeAccount(account.uid)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        stopLocally(ReaderSocialStatus.ERROR, error.message)
                    }
                }
            }
        } finally {
            stopLocally(if (state.value.signedIn) ReaderSocialStatus.WAITING else ReaderSocialStatus.SIGNED_OUT)
            mutableState.update { it.copy(foreground = false) }
            retractActivity()
        }
    }

    private suspend fun observeAccount(uid: String) = coroutineScope {
        val sharing = MutableStateFlow<SocialSharing?>(null)
        val hidden = MutableStateFlow<Set<String>?>(null)
        val accepted = MutableStateFlow<Set<String>?>(null)
        val self = repository.getSelf()
        settings = self.settings
        sharing.value = self.settings
        var privacyRetracted = false
        var hadReading = false
        launch {
            repository.watchSettings(uid).collect {
                settings = it
                sharing.value = it
            }
        }
        launch {
            repository.watchHidden(uid).collect {
                hiddenKeys = it
                hidden.value = it
            }
        }
        launch { repository.watchAcceptedForRevocation(uid).collect { accepted.value = it } }
        launch { repository.watchFriends().collect { friends -> mutableState.update { it.copy(friends = friends) } } }
        launch {
            repository.watchJoinRequests(uid)
                .catch { emit(emptyList()) }
                .collect { requests -> mutableState.update { it.copy(joinRequests = requests) } }
        }
        combine(
            sharing,
            hidden,
            incognito,
            local.map { it?.content }.distinctUntilChanged(),
        ) { consent, hiddenKeys, private, content ->
            ReaderPermission(consent, hiddenKeys, private, content)
        }.distinctUntilChanged().collectLatest { permission ->
            if (permission.settings == null || permission.hidden == null) {
                stopLocally(ReaderSocialStatus.WAITING)
                return@collectLatest
            }
            if (permission.content == null && !hadReading) {
                stopLocally(ReaderSocialStatus.WAITING)
                return@collectLatest
            }
            if (permission.private || permission.content == null ||
                permission.content.contentKey in permission.hidden || permission.settings.invisible
            ) {
                stopLocally(
                    when {
                        permission.content == null -> ReaderSocialStatus.MISMATCH
                        !permission.private && permission.content.contentKey !in permission.hidden ->
                            ReaderSocialStatus.INVISIBLE
                        else -> ReaderSocialStatus.PRIVATE
                    },
                )
                val id = sessions.selectedRoomId.value
                sessions.clear()
                mutableState.update { it.copy(selectedRoomId = null) }
                if (!privacyRetracted) {
                    privacyRetracted = true
                    // Revokes a cancelled callable even if its server mutation finishes later.
                    performRetraction { settings = repository.updateSharing(permission.settings) }
                }
                retractActivity()
                if (id != null) {
                    performRetraction {
                        repository.roomAction("leave", roomId = id, sessionId = sessions.writerSessionId)
                    }
                }
                return@collectLatest
            }
            hadReading = true
            privacyRetracted = false
            mutableState.update { it.copy(status = ReaderSocialStatus.READY, error = null) }
            coroutineScope {
                launch { startPendingRoom(uid) }
                if (permission.settings.activity == "friends") {
                    launch {
                        publishActivity(permission.settings.generation)
                    }
                }
                sessions.selectedRoomId.collectLatest { roomId ->
                    mutableState.update { it.copy(selectedRoomId = roomId) }
                    if (roomId == null) {
                        // Keep why the room ended visible; the friend list still allows a new invite.
                        val ended = state.value.status.takeIf {
                            it == ReaderSocialStatus.EXPIRED || it == ReaderSocialStatus.REVOKED
                        }
                        stopLocally(ended ?: ReaderSocialStatus.READY)
                        setChatOnly(false)
                    } else {
                        observeRoom(roomId, uid, accepted)
                    }
                }
            }
        }
    }

    private suspend fun publishActivity(generation: Long) {
        local.map {
            it?.let { reading -> Triple(reading.content, reading.position.chapterKey, reading.position.chapterLabel) }
        }
            .distinctUntilChanged().collectLatest { activity ->
                if (activity == null) return@collectLatest
                while (true) {
                    try {
                        repository.publishActivity(generation, activity.first, activity.second, activity.third)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        mutableState.update { it.copy(error = error.message) }
                        // A later chapter/lifecycle intent is required; no offline outbox/retry.
                        return@collectLatest
                    }
                    delay(60_000)
                }
            }
    }

    private suspend fun observeRoom(roomId: String, uid: String, accepted: StateFlow<Set<String>?>) = coroutineScope {
        val roomState = MutableStateFlow<SocialRoom?>(null)
        var sequence: RoomSequence? = null
        var epoch: Long? = null
        var lastOffered: SocialPosition? = null
        val writer = LatestPositionWriter(minIntervalMs = POSITION_WRITE_INTERVAL_MS)
        // Highest sequence this host wrote; the listener can lag behind our own latest write.
        var written = -1L
        var wasFollowing = false
        // The epoch whose host lease this device holds (claimed with a heartbeat after becoming host).
        var claimedEpoch: Long? = null
        try {
            launch {
                combine(
                    repository.watchRoom(roomId).catch { error ->
                        if (error !is SocialRoomUnavailable) throw error
                        // Lease expiry or revoked access: end the room quietly instead of surfacing a rules error.
                        roomState.value = null
                        stopLocally(ReaderSocialStatus.EXPIRED)
                        sessions.clear(roomId)
                        mutableState.update { it.copy(selectedRoomId = null) }
                    },
                    accepted,
                ) { room, edges -> room to edges }
                    .collect { (room, edges) ->
                        // Members need the host's friendship; the host's own room goes on whatever happens.
                        if (room != null && edges != null && room.hostUid != uid && room.hostUid !in edges) {
                            roomState.value = null
                            stopLocally(ReaderSocialStatus.REVOKED)
                            sessions.clear(roomId)
                            mutableState.update { it.copy(selectedRoomId = null) }
                        } else if (room == null || edges == null) {
                            roomState.value = null
                            stopLocally(ReaderSocialStatus.WAITING)
                        } else {
                            if (epoch != room.epoch) {
                                // First snapshot, or a host hand-off: the new host's positions start a new sequence.
                                epoch = room.epoch
                                sequence = RoomSequence(room.epoch)
                                written = -1L
                                lastOffered = null
                            }
                            if (room.hostUid == uid && claimedEpoch != room.epoch) {
                                claimedEpoch = room.epoch
                                // Became host (or just created the room): hold the lease with this device's session.
                                launch {
                                    try {
                                        repository.roomAction(
                                            "heartbeat",
                                            roomId = roomId,
                                            sessionId = sessions.writerSessionId,
                                        )
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        claimedEpoch = null
                                        logcat(LogPriority.WARN, error) { "Room lease claim failed" }
                                    }
                                }
                            }
                            roomState.value = room
                            mutableState.update {
                                it.copy(
                                    room = room,
                                    isHost = room.hostUid == uid,
                                    status = when {
                                        room.state == "invited" -> ReaderSocialStatus.INVITED
                                        local.value?.let {
                                            !ReadingCompatibility.sameEdition(it.content, room.content)
                                        } == true -> ReaderSocialStatus.ELSEWHERE
                                        // Position snapshots don't clear a pending consent or chapter load.
                                        it.status == ReaderSocialStatus.CONFIRM_MAPPING ||
                                            it.status == ReaderSocialStatus.LOADING -> it.status
                                        else -> ReaderSocialStatus.ACTIVE
                                    },
                                )
                            }
                        }
                    }
            }
            launch { publishMyPlace(roomId, uid, roomState) }
            launch {
                // A new chapter starts a new ready check: older signals stop counting.
                var chapter: String? = null
                var floor = -1L
                combine(roomState, state.map { it.messages }.distinctUntilChanged()) { room, messages ->
                    room to
                        messages
                }
                    .collect { (room, messages) ->
                        val key = room?.position?.chapterKey
                        if (key != chapter) {
                            if (chapter != null) floor = messages.maxOfOrNull { it.seq } ?: floor
                            chapter = key
                        }
                        val signals = RoomSignals.current(messages, floor, System.currentTimeMillis())
                        if (signals != state.value.signals) mutableState.update { it.copy(signals = signals) }
                    }
            }
            launch {
                val cursor = RoomChatCursor()
                repository.watchRoomMessages(roomId)
                    .catch { emit(emptyList()) } // the room listener above owns ending the room
                    .collect { messages ->
                        mutableState.update { it.copy(messages = messages) }
                        cursor.fresh(messages).forEach {
                            val event = it.kind.takeIf { kind ->
                                kind in RoomMessage.EVENTS ||
                                    kind in RoomMessage.SIGNALS
                            }
                            popupEvents.tryEmit(
                                RoomPopup(
                                    seq = it.seq,
                                    emoji = it.emoji,
                                    text = it.text,
                                    mine = it.senderUid == uid && event == null,
                                    senderName = roomState.value?.nameOf(it.senderUid)
                                        ?: state.value.friends.firstOrNull { f -> f.uid == it.senderUid }?.displayName,
                                    event = event,
                                ),
                            )
                        }
                    }
            }
            launch {
                writer.run(
                    publish = { position ->
                        val room = roomState.value
                        check(room != null && room.state == "active" && room.hostUid == uid)
                        check(state.value.foreground && mapping.value == position.pageMappingKey && !incognito.value)
                        val next = maxOf(room.sequence, written) + 1
                        repository.publishPosition(roomId, sessions.writerSessionId, position, next)
                        written = next
                    },
                    onFailure = { error ->
                        written = -1L // resync from the server's sequence
                        roomState.value = null
                        stopLocally(ReaderSocialStatus.ERROR, error.message)
                        // A fresh server read is reconciliation, never replay of the failed turn.
                        val fresh = repository.getRoom(roomId)
                        if (fresh != null && fresh.epoch == epoch) roomState.value = fresh
                    },
                )
            }
            launch {
                while (true) {
                    delay(30_000)
                    val room = roomState.value ?: continue
                    if (room.leaseExpiresAt <= System.currentTimeMillis()) {
                        roomState.value = null
                        stopLocally(ReaderSocialStatus.EXPIRED)
                        sessions.clear(roomId)
                    } else if (room.hostUid == uid) {
                        try {
                            repository.roomAction("heartbeat", roomId = roomId, sessionId = sessions.writerSessionId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            roomState.value = null
                            stopLocally(ReaderSocialStatus.ERROR, error.message)
                        }
                    }
                }
            }
            combine(local, mapping, follow, roomState, chatOnly) { reading, consent, following, room, justChat ->
                ReaderRoomInput(reading, consent, following, room, justChat)
            }.collectLatest { input ->
                if (input.following && !wasFollowing) epoch?.let { sequence = RoomSequence(it) }
                wasFollowing = input.following
                val room = input.room ?: return@collectLatest
                val reading = input.reading ?: return@collectLatest
                if (!ReadingCompatibility.sameEdition(reading.content, room.content)) {
                    // Reading something else: keep the room for chat and reactions, never move the viewer.
                    while (moves.tryReceive().isSuccess) { /* discard stale viewer commands */ }
                    mutableState.update { it.copy(status = ReaderSocialStatus.ELSEWHERE, followOffer = false) }
                    return@collectLatest
                }
                if (room.state != "active") return@collectLatest
                mutableState.update {
                    it.copy(
                        followOffer = room.hostUid != uid && !input.following && !input.chatOnly,
                        status = if (it.status ==
                            ReaderSocialStatus.ELSEWHERE
                        ) {
                            ReaderSocialStatus.ACTIVE
                        } else {
                            it.status
                        },
                    )
                }
                if (room.hostUid == uid) {
                    if (input.consent == reading.position.pageMappingKey) {
                        if (lastOffered != reading.position && room.position != reading.position) {
                            lastOffered = reading.position
                            writer.offer(reading.position)
                        }
                    } else {
                        mutableState.update { it.copy(status = ReaderSocialStatus.CONFIRM_MAPPING) }
                    }
                } else if (input.following) {
                    val target = room.position ?: return@collectLatest
                    if (reading.position.chapterKey != target.chapterKey) {
                        mutableState.update { it.copy(status = ReaderSocialStatus.LOADING) }
                        val matched = loadMatchedChapter(room.content, target.chapterKey)
                        if (matched == null) {
                            // The host's chapter isn't available here: stop following but keep the room's chat.
                            follow.value = false
                            mutableState.update { it.copy(status = ReaderSocialStatus.MISMATCH, following = false) }
                        } else {
                            pageSelected(matched)
                        }
                        return@collectLatest
                    }
                    if (!ReadingCompatibility.canFollow(reading, room, input.consent)) {
                        mutableState.update { it.copy(status = ReaderSocialStatus.CONFIRM_MAPPING) }
                        return@collectLatest
                    }
                    if (sequence?.accept(room) == true) {
                        mutableState.update { it.copy(status = ReaderSocialStatus.ACTIVE) }
                        moves.trySend(
                            RemoteReaderMove(
                                roomId,
                                room.epoch,
                                room.sequence,
                                reading.chapterId,
                                target.pageIndex,
                                target.stripPosition,
                            ),
                        )
                    }
                }
            }
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            stopLocally(ReaderSocialStatus.ERROR, error.message)
        } finally {
            mutableState.update { it.copy(room = null, messages = emptyList(), chatError = false) }
        }
    }

    /**
     * Room positions: as a member (not the host, whose position the room already carries) I share my chapter and page
     * with the room, at most once every [ROOM_POSITION_GAP_MS], latest place wins. Reading another title clears it.
     * Privacy changes end the room for me (observeAccount), which stops this with it.
     */
    private suspend fun publishMyPlace(
        roomId: String,
        uid: String,
        roomState: StateFlow<SocialRoom?>,
    ): Unit = coroutineScope {
        val target = combine(local, roomState) { reading, room ->
            room?.takeIf { it.hostUid != uid }?.let { MemberPlace.of(reading, it, uid) }
        }.distinctUntilChanged().stateIn(this)
        var sent: MemberPlace? = null
        var lastAt = 0L
        target.collect {
            val wait = lastAt + ROOM_POSITION_GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val place = target.value
            // Nothing shared yet needs no clear.
            if (place == null || place == sent || (sent == null && place == MemberPlace.CLEARED)) return@collect
            if (incognito.value || !state.value.foreground) return@collect
            try {
                repository.publishMemberPage(roomId, place.chapterKey, place.pageIndex)
                sent = place
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Best effort, like activity: the next page turn tries again.
                logcat(LogPriority.DEBUG, error) { "Room position not shared" }
            }
            lastAt = System.currentTimeMillis()
        }
    }

    private fun stopLocally(status: ReaderSocialStatus, error: String? = null) {
        follow.value = false
        while (moves.tryReceive().isSuccess) { /* discard stale viewer commands */ }
        mutableState.update {
            it.copy(room = null, following = false, followOffer = false, status = status, error = error)
        }
    }

    private suspend fun retractActivity() {
        val consent = settings ?: return
        if (actor != repository.currentUid) return
        performRetraction { repository.publishActivity(consent.generation, null) }
    }

    private suspend fun performRetraction(action: suspend () -> Unit) {
        withContext(NonCancellable) {
            val completed = withTimeoutOrNull(5_000) {
                try {
                    action()
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            } ?: false
            if (!completed) mutableState.update { it.copy(status = ReaderSocialStatus.RETRACTION_PENDING) }
        }
    }

    private suspend fun perform(action: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true) }
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(status = ReaderSocialStatus.ERROR, error = error.message) }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    private data class ReaderPermission(
        val settings: SocialSharing?,
        val hidden: Set<String>?,
        val private: Boolean,
        val content: SocialContent?,
    )
    private data class ReaderRoomInput(
        val reading: LocalReading?,
        val consent: String?,
        val following: Boolean,
        val room: SocialRoom?,
        val chatOnly: Boolean,
    )
}

private fun editionOf(content: SocialContent) = "${content.sourceKey}|${content.editionKey}"

/** How long a pending "Read together" waits for my friend list after the reader starts. */
private const val FRIENDS_WAIT_MS = 15_000L
