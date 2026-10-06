package mihon.feature.social

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * How far someone got in a title of their shared library: chapters read, chapters known and whether all are read.
 * A library entry carries it only once its owner opened at least one chapter (Berti's decision: that counts as
 * "read"), so `read` can be 0.
 */
data class SocialProgress(val read: Int, val total: Int, val finished: Boolean) {
    fun payload(): Map<String, Any> = mapOf("read" to read, "total" to total, "finished" to finished)

    companion object {
        private const val MAX = 100_000

        /** From Mihon's library counts; null when no chapter was opened yet ([lastRead] is the history's last read). */
        fun of(readCount: Long, totalChapters: Long, lastRead: Long): SocialProgress? {
            if (readCount <= 0 && lastRead <= 0) return null
            val total = totalChapters.coerceIn(0, MAX.toLong()).toInt()
            val read = readCount.coerceIn(0, total.toLong()).toInt()
            return SocialProgress(read, total, finished = total > 0 && read == total)
        }

        fun parse(value: Any?): SocialProgress? {
            val data = value as? Map<*, *> ?: return null
            val read = (data["read"] as? Number)?.toInt() ?: return null
            val total = (data["total"] as? Number)?.toInt() ?: return null
            if (read !in 0..total || total > MAX) return null
            return SocialProgress(read, total, data["finished"] == true && total > 0 && read == total)
        }
    }
}

/** One entry of a shared library or favorites list, with the owner's progress when they share it. */
data class SharedEntry(val content: SocialContent, val progress: SocialProgress? = null)

/** A rating (1–5 stars) and optional review text, stored per work at reviews/{uid}/items/{workKey}. */
data class SocialReview(
    val workKey: String,
    val content: SocialContent,
    val rating: Int,
    val text: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val MAX_TEXT = 1_000
        const val MAX_LINES = 30

        fun parse(data: Map<String, Any?>, millis: (Any?) -> Long?): SocialReview? {
            val key = data["workKey"] as? String ?: return null
            val rating = (data["rating"] as? Number)?.toInt()?.takeIf { it in 1..5 } ?: return null

            @Suppress("UNCHECKED_CAST")
            val content = (data["content"] as? Map<String, Any?>)?.let { runCatching { it.content() }.getOrNull() }
                ?: return null
            return SocialReview(
                workKey = key,
                content = content,
                rating = rating,
                text = (data["text"] as? String)?.take(MAX_TEXT)?.takeIf { it.isNotBlank() },
                createdAt = millis(data["createdAt"]) ?: 0,
                updatedAt = millis(data["updatedAt"]) ?: 0,
            )
        }

        /** What the server will store: trimmed, at most [MAX_TEXT] characters and [MAX_LINES] lines; blank means none. */
        fun cleanText(text: String): String? = text.replace("\r\n", "\n")
            .filter { it == '\n' || !it.isISOControl() }
            .lines().take(MAX_LINES).joinToString("\n")
            .take(MAX_TEXT).trim()
            .takeIf { it.isNotEmpty() }

        fun stars(rating: Int): String = "★".repeat(rating.coerceIn(0, 5)) + "☆".repeat(5 - rating.coerceIn(0, 5))
    }
}

/**
 * What one friend shares that "friends who read this" needs: started titles and reviews, with the friend's sharing
 * settings they were read under ([sharing]).
 */
data class FriendShelf(
    val profile: SocialProfile,
    val entries: List<SharedEntry> = emptyList(),
    val reviews: List<SocialReview> = emptyList(),
    val sharing: SocialSharing = SocialSharing(),
) {
    val isEmpty: Boolean get() = entries.isEmpty() && reviews.isEmpty()

    /**
     * What the friend's [current] settings still allow of this shelf: started titles only while they share their
     * library, with progress, in the same generation (every privacy change bumps it); reviews only while shared (a
     * friend's copy is already off while they're invisible).
     */
    fun allowedBy(current: SocialSharing): FriendShelf = copy(
        entries = entries.takeIf {
            current.library == "friends" && current.progress && current.generation == sharing.generation
        }.orEmpty(),
        reviews = reviews.takeIf { current.reviews && !current.invisible }.orEmpty(),
        sharing = current,
    )
}

/** A friend who read (or reviewed) a title: how far they got and what they thought. */
data class FriendReading(val profile: SocialProfile, val progress: SocialProgress?, val review: SocialReview?)

object FriendReadings {
    /**
     * Friends who opened at least one chapter of [mine] (an entry with progress) or reviewed it, matched with
     * [TitleMatch]. Finished first, then most chapters read, then reviewers, then by name.
     */
    fun collect(mine: TitleMatch.Work, shelves: List<FriendShelf>): List<FriendReading> = shelves.mapNotNull { shelf ->
        val entry = shelf.entries.filter {
            it.progress != null &&
                TitleMatch.match(mine, TitleMatch.Work.of(it.content)) != null
        }
            .maxByOrNull { it.progress!!.read }
        val review = shelf.reviews.filter { TitleMatch.match(mine, TitleMatch.Work.of(it.content)) != null }
            .maxByOrNull { it.updatedAt }
        if (entry == null && review == null) null else FriendReading(shelf.profile, entry?.progress, review)
    }.sortedWith(
        compareByDescending<FriendReading> { it.progress?.finished == true }
            .thenByDescending { it.progress?.read ?: -1 }
            .thenByDescending { it.review != null }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.profile.displayName },
    )

    /** My own review of [mine], if any: the newest of my reviews that match it. */
    fun mine(mine: TitleMatch.Work, reviews: List<SocialReview>): SocialReview? =
        reviews.filter { TitleMatch.match(mine, TitleMatch.Work.of(it.content)) != null }.maxByOrNull { it.updatedAt }

    /**
     * Whether a new rating of [mine] replaces my [old] review (which matched the page) under another work key: only
     * when it's surely the same work, i.e. the same source entry or the same tracker ID. A match by title alone may be
     * a different work, so both reviews stay.
     */
    fun replaces(mine: TitleMatch.Work, old: SocialReview): Boolean =
        when (TitleMatch.match(mine, TitleMatch.Work.of(old.content))) {
            TitleMatch.Reason.SOURCE_WORK, TitleMatch.Reason.TRACKER -> true
            TitleMatch.Reason.TITLE, null -> false
        }
}

/** What [FriendShelves] reads: [SocialRepository] in the app, a fake in tests. */
interface FriendShelfSource {
    val currentUid: String?
    suspend fun friendIds(): List<String>
    suspend fun friend(uid: String): SocialFriendDetails
    suspend fun startedEntries(uid: String, generation: Long): List<SharedEntry>
    suspend fun reviews(uid: String): List<SocialReview>
    fun acceptedForRevocation(uid: String): Flow<Set<String>?>
}

/**
 * Friends' started titles and reviews, loaded once per signed-in session when a title page first needs them (never per
 * title) and kept for [MAX_AGE_MS]. While a title page watches [cache], the snapshot reloads by itself once it gets
 * that old, and coming back to the app reloads it too ([onForeground]). Only what each friend shares is read: started
 * library entries while they share their library with progress (a query that skips entries without progress), reviews
 * while their reviews switch is on.
 *
 * A friend's settings already known (from the previous load, or from their profile on the Friends screen via
 * [onFriendSharing]) are reused instead of a `socialGetFriend` call; when the rules deny a read under them (sharing
 * turned off, a new generation), the current settings are asked for once. Narrower settings seen on a profile drop the
 * held data at once. My accepted edges are watched while a snapshot is held: a removed or blocked friend's data goes
 * at once ([retain]); signing out or another account starts over. Loads run in this class's own scope, so leaving a
 * title page doesn't cancel and restart them.
 */
open class FriendShelves internal constructor(
    private val source: FriendShelfSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Snapshot(
        val uid: String,
        val loadedAt: Long,
        val friends: List<FriendShelf>,
        val mine: List<SocialReview>,
    )

    /** A friend's profile and settings as last seen, and when. */
    private data class Known(val profile: SocialProfile, val sharing: SocialSharing, val at: Long)

    private val lock = Any()
    private var loading: Deferred<Snapshot?>? = null
    private var loadingUid: String? = null
    private var revocation: Job? = null
    private var refresher: Job? = null
    private val mutableCache = MutableStateFlow<Snapshot?>(null)
    private val known = ConcurrentHashMap<String, Known>()

    /** What's held now (null before the first load, after signing out or [forget]); title pages observe it. */
    val cache: StateFlow<Snapshot?> = mutableCache.asStateFlow()

    /** The current snapshot, loading it when missing, stale or for another account; null when signed out. */
    suspend fun snapshot(): Snapshot? {
        val uid = source.currentUid ?: return null.also { forget() }
        fresh(uid)?.let { return it }
        return start(uid).await()
    }

    /** Keeps only these friends (my accepted edges changed: a removed friend's data goes right away). */
    fun retain(friendUids: Collection<String>) {
        val keep = friendUids.toSet()
        known.keys.retainAll(keep)
        mutableCache.update { current -> current?.copy(friends = current.friends.filter { it.profile.uid in keep }) }
    }

    /**
     * A friend's current settings, seen elsewhere (their profile on the Friends screen): what they no longer share
     * goes at once; anything they started sharing shows with the next load.
     */
    fun onFriendSharing(details: SocialFriendDetails) {
        val uid = details.profile.uid
        known[uid] = Known(details.profile, details.sharing, clock())
        mutableCache.update { current ->
            current?.copy(
                friends = current.friends.mapNotNull { shelf ->
                    if (shelf.profile.uid != uid) shelf else shelf.allowedBy(details.sharing).takeUnless { it.isEmpty }
                },
            )
        }
    }

    /** The app came back to the foreground: friends may have changed what they share in the meantime. */
    fun onForeground() {
        val held = mutableCache.value ?: return
        if (clock() - held.loadedAt < FOREGROUND_RELOAD_MS) return
        if (mutableCache.subscriptionCount.value > 0 && source.currentUid == held.uid) {
            start(held.uid)
        } else {
            // Nobody looks right now: the next title page loads afresh.
            mutableCache.update { current -> current?.copy(loadedAt = 0) }
        }
    }

    fun forget() {
        synchronized(lock) {
            loading?.cancel()
            loading = null
            loadingUid = null
            revocation?.cancel()
            revocation = null
            refresher?.cancel()
            refresher = null
        }
        known.clear()
        mutableCache.value = null
    }

    /** After I set or deleted a review: my list changes without reloading friends. */
    fun updateMine(uid: String, transform: (List<SocialReview>) -> List<SocialReview>) {
        mutableCache.update { current ->
            if (current != null && current.uid == uid) current.copy(mine = transform(current.mine)) else current
        }
    }

    private fun fresh(uid: String): Snapshot? =
        mutableCache.value?.takeIf { it.uid == uid && clock() - it.loadedAt < MAX_AGE_MS }

    /** One load at a time per account, owned by [scope]: callers only wait for it. */
    private fun start(uid: String): Deferred<Snapshot?> = synchronized(lock) {
        loading?.takeIf { it.isActive && loadingUid == uid }?.let { return it }
        scope.async {
            val loaded = load(uid)
            if (source.currentUid != uid) return@async null
            mutableCache.value = loaded
            synchronized(lock) {
                if (revocation?.isActive != true) watchRevocation(uid)
                if (refresher?.isActive != true) refresher = scope.launch { refreshWhileWatched(uid) }
            }
            loaded
        }.also {
            loading = it
            loadingUid = uid
        }
    }

    /**
     * While a title page watches, the snapshot never gets much older than [MAX_AGE_MS]. Stops once nobody watches;
     * the next [snapshot] or [onForeground] starts it again.
     */
    private suspend fun refreshWhileWatched(uid: String) {
        while (true) {
            val held = mutableCache.value?.takeIf { it.uid == uid } ?: return
            delay((held.loadedAt + MAX_AGE_MS - clock()).coerceAtLeast(MIN_RELOAD_GAP_MS))
            if (source.currentUid != uid || mutableCache.subscriptionCount.value == 0) return
            if (fresh(uid) == null) {
                try {
                    start(uid).await()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) { "Friends who read: reload failed" }
                }
            }
        }
    }

    private fun watchRevocation(uid: String) {
        revocation = scope.launch {
            try {
                // Null while only cached data is known: an offline blip isn't mistaken for a removal.
                source.acceptedForRevocation(uid).collect { ids -> if (ids != null) retain(ids) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Signed out or the account changed (the listener closes): nothing may be kept.
                mutableCache.update { current -> current?.takeIf { it.uid != uid } }
            }
        }
    }

    private suspend fun load(uid: String): Snapshot = coroutineScope {
        val mine = async { safe(emptyList()) { source.reviews(uid) } }
        val ids = source.friendIds().take(MAX_FRIENDS)
        known.keys.retainAll(ids.toSet())
        val permits = Semaphore(PARALLEL_FRIENDS)
        val friends = ids.map { friendUid ->
            async { permits.withPermit { safe(null) { shelf(friendUid) } } }
        }.awaitAll().filterNotNull()
        Snapshot(uid, clock(), friends, mine.await())
    }

    /**
     * One friend's shelf. Known settings are tried first; when a read under them is denied (sharing off, a new
     * generation) or fails, the current settings are asked for once and the reads repeated under them.
     */
    private suspend fun shelf(friendUid: String): FriendShelf? {
        known[friendUid]?.takeIf { clock() - it.at < KNOWN_SHARING_MS }?.let { remembered ->
            try {
                return read(remembered.profile, remembered.sharing)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Denied or offline: ask for the current settings below.
            }
        }
        val details = source.friend(friendUid)
        known[friendUid] = Known(details.profile, details.sharing, clock())
        return read(details.profile, details.sharing)
    }

    /** Reads what [sharing] allows (throws when a read is denied); null when there's nothing to show. */
    private suspend fun read(profile: SocialProfile, sharing: SocialSharing): FriendShelf? = coroutineScope {
        val entries = async {
            if (sharing.library == "friends" && sharing.progress) {
                source.startedEntries(profile.uid, sharing.generation).take(MAX_STARTED)
            } else {
                emptyList()
            }
        }
        val reviews = async {
            if (sharing.reviews && !sharing.invisible) source.reviews(profile.uid).take(MAX_REVIEWS) else emptyList()
        }
        FriendShelf(profile, entries.await(), reviews.await(), sharing).takeUnless { it.isEmpty }
    }

    private suspend fun <T> safe(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Denied (sharing just turned off), offline or an older backend: that part simply stays empty.
        logcat(LogPriority.WARN, error) { "Friends who read: part not loaded" }
        fallback
    }

    companion object {
        /** A friend who stops sharing is gone from an open title page within about this long (at once from a profile). */
        const val MAX_AGE_MS = 5 * 60_000L

        /** Coming back to the app reloads a snapshot older than this. */
        const val FOREGROUND_RELOAD_MS = 60_000L

        /** Known settings are reused at most this long, so things a friend starts sharing show up eventually. */
        const val KNOWN_SHARING_MS = 30 * 60_000L

        /** Per friend: at most this many started titles and reviews are kept (the queries stop there too). */
        const val MAX_STARTED = 500
        const val MAX_REVIEWS = 100
        private const val MIN_RELOAD_GAP_MS = 30_000L
        private const val MAX_FRIENDS = 100
        private const val PARALLEL_FRIENDS = 4
    }
}

/** The app's [FriendShelves]: reads through [SocialRepository] and reloads when the app returns to the foreground. */
@Inject
@SingleIn(AppScope::class)
class FriendReaders(repository: SocialRepository) : FriendShelves(
    source = RepositoryShelfSource(repository),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    init {
        // ProcessLifecycleOwner wants the main thread; FriendReaders may be created on any thread.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) onForeground() },
            )
        }
    }
}

private class RepositoryShelfSource(private val repository: SocialRepository) : FriendShelfSource {
    override val currentUid: String? get() = repository.currentUid
    override suspend fun friendIds() = repository.loadFriendIds()
    override suspend fun friend(uid: String) = repository.getFriend(uid)
    override suspend fun startedEntries(uid: String, generation: Long) = repository.loadStartedEntries(uid, generation)
    override suspend fun reviews(uid: String) = repository.loadReviews(uid)
    override fun acceptedForRevocation(uid: String) = repository.watchAcceptedForRevocation(uid)
}
