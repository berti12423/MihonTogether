package mihon.feature.social

import android.util.LruCache
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.StorageMetadata
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Inject
@SingleIn(AppScope::class)
class SocialRepository(private val firebase: SocialFirebase, private val emulatorCallables: SocialEmulatorCallables) {
    private val positionGate = CompletionOwnedGate()
    private val activityGate = CompletionOwnedGate()
    private val entriesGate = CompletionOwnedGate()
    private val presenceBlockedUid = AtomicReference<String?>(null)
    private val activePresence = AtomicReference<PresenceRegistration?>(null)
    private val mutablePresencePaused = MutableStateFlow(false)
    val presencePaused = mutablePresencePaused.asStateFlow()

    @Volatile var presenceCleanupPending = false
        private set
    private val mutableProjectionPermit = MutableStateFlow(false)
    val projectionPermit = mutableProjectionPermit.asStateFlow()
    val enabled: Boolean get() = firebase.enabled
    val currentUid: String? get() = if (enabled) firebase.auth.currentUser?.uid else null

    fun authChanges(): Flow<SocialAccount?> = if (!enabled) {
        flowOf(null)
    } else {
        callbackFlow {
            val listener = FirebaseAuth.IdTokenListener { auth ->
                val user = auth.currentUser
                trySend(user?.let { SocialAccount(it.uid, it.email, it.isEmailVerified) })
            }
            firebase.auth.addIdTokenListener(listener)
            awaitClose { firebase.auth.removeIdTokenListener(listener) }
        }.distinctUntilChanged()
    }

    suspend fun signIn(email: String, password: String) {
        if (currentUid != null) stopPresenceBeforeSignOut()
        firebase.auth.signInWithEmailAndPassword(email.trim(), password).await()
        presenceBlockedUid.set(null)
        mutablePresencePaused.value = false
    }

    suspend fun register(email: String, password: String) {
        if (currentUid != null) stopPresenceBeforeSignOut()
        val user = firebase.auth.createUserWithEmailAndPassword(email.trim(), password).await().user
        presenceBlockedUid.set(null)
        mutablePresencePaused.value = false
        user?.sendEmailVerification()?.await()
    }

    suspend fun resetPassword(email: String) = firebase.auth.sendPasswordResetEmail(email.trim()).await()

    suspend fun verifyEmail() = checkNotNull(firebase.auth.currentUser).sendEmailVerification().await()

    suspend fun refreshAccount() {
        checkNotNull(firebase.auth.currentUser).reload().await()
        firebase.auth.currentUser?.getIdToken(true)?.await()
    }

    fun signOut() = firebase.auth.signOut()

    /**
     * Permanently deletes the signed-in account and everything the backend stores about it. Re-authenticates first
     * (the server only accepts a sign-in from the last few minutes), stops presence, then signs out locally.
     */
    suspend fun deleteAccount(password: String) {
        val user = checkNotNull(firebase.auth.currentUser)
        val email = checkNotNull(user.email) { "Account has no email" }
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        user.getIdToken(true).await()
        stopPresenceBeforeSignOut()
        call("socialDeleteAccount", mapOf("confirm" to "DELETE"))
        firebase.auth.signOut()
    }

    /**
     * Connection check for support: tries each Firebase service the social features use, one at a time with a time
     * limit, and reports what failed (e.g. a mobile network that blocks one of them). Reads only my own data.
     */
    suspend fun connectionCheck(): List<ConnectionProbe> {
        val uid = currentUid ?: return emptyList()
        suspend fun probe(service: ConnectionProbe.Service, block: suspend () -> Unit): ConnectionProbe = try {
            val started = System.currentTimeMillis()
            kotlinx.coroutines.withTimeout(15_000) { block() }
            ConnectionProbe(service, ConnectionProbe.Outcome.OK, "${System.currentTimeMillis() - started} ms")
        } catch (error: CancellationException) {
            if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            ConnectionProbe(service, ConnectionProbe.Outcome.TIMEOUT, "15 s")
        } catch (error: Exception) {
            ConnectionProbe(
                service,
                ConnectionProbe.Outcome.ERROR,
                "${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}",
            )
        }
        return listOf(
            probe(ConnectionProbe.Service.AUTH) { firebase.auth.currentUser?.getIdToken(true)?.await() },
            probe(ConnectionProbe.Service.FIRESTORE) {
                firebase.firestore.document("sharingSettings/$uid").get(Source.SERVER).await()
            },
            probe(ConnectionProbe.Service.FUNCTIONS) { call("socialGetSelf", emptyMap()) },
            probe(ConnectionProbe.Service.DATABASE) {
                firebase.database.getReference("connections/$uid").get().await()
            },
            probe(ConnectionProbe.Service.STORAGE) {
                try {
                    firebase.storage.reference.child("profileMedia/$uid/avatar").metadata.await()
                } catch (error: com.google.firebase.storage.StorageException) {
                    // "No avatar" still proves Storage answered.
                    if (error.errorCode !=
                        com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND
                    ) {
                        throw error
                    }
                }
            },
        )
    }

    suspend fun getSelf(): SocialSelf {
        val response = call("socialGetSelf", emptyMap())
        return SocialSelf(response["profile"]?.map()?.profile(), response["settings"].map().sharing())
    }

    suspend fun updateProfile(handle: String, displayName: String): SocialProfile = call(
        "socialUpdateProfile",
        mutation("handle" to handle.trim().lowercase(), "displayName" to displayName.trim()),
    )["profile"].map().profile()

    suspend fun lookupHandle(handle: String): SocialProfile = call(
        "socialLookupHandle",
        mapOf("handle" to handle.trim().lowercase()),
    )["profile"].map().profile()

    /** [viaInvite]: a request made from the other's invite link or friend code (backend `via: "invite"`). */
    suspend fun friendAction(action: String, otherUid: String, viaInvite: Boolean = false) {
        val via = if (viaInvite) arrayOf<Pair<String, Any?>>("via" to "invite") else emptyArray()
        call("socialFriendAction", mutation("action" to action, "otherUid" to otherUid, *via))
    }

    suspend fun updateSharing(settings: SocialSharing, hideContentKey: String? = null): SocialSharing = call(
        "socialUpdateSharing",
        mutation(
            "expectedGeneration" to settings.generation,
            "settings" to settings.payload(),
            "hideContentKey" to hideContentKey,
        ),
    )["settings"].map().sharing()

    fun watchSettings(uid: String): Flow<SocialSharing> = document("sharingSettings/$uid", uid)
        .mapDocument { it?.sharing() ?: SocialSharing() }

    fun watchSettings(): Flow<SocialSharing> = authChanges().flatMapLatest { account ->
        if (account == null) flowOf(SocialSharing()) else watchSettings(account.uid)
    }

    fun watchHiddenKeys(): Flow<Set<String>> = authChanges().flatMapLatest { account ->
        if (account == null) flowOf(emptySet()) else watchHidden(account.uid)
    }

    fun watchFriends(): Flow<List<SocialProfile>> = authChanges().flatMapLatest { account ->
        if (account == null) {
            flowOf(emptyList())
        } else {
            watchAccepted(account.uid).mapLatest { ids ->
                ids.map { getProfile(it) }
            }
        }
    }

    fun watchHidden(uid: String): Flow<Set<String>> = query(
        firebase.firestore.collection("hiddenTitles/$uid/entries"),
        uid,
    ).mapDocuments { documents -> documents.map { it.id }.toSet() }

    fun watchAccepted(uid: String): Flow<List<String>> = query(
        firebase.firestore.collection("friendships/$uid/accepted").orderBy(FieldPath.documentId()),
        uid,
    ).mapDocuments { documents -> documents.map { it.id } }

    /**
     * Accepted edges for revocation decisions: `null` while only cached data is available, so an offline
     * blip pauses shared state instead of being mistaken for the friend removing us.
     */
    fun watchAcceptedForRevocation(uid: String): Flow<Set<String>?> = queryOrUnknown(
        firebase.firestore.collection("friendships/$uid/accepted").orderBy(FieldPath.documentId()),
        uid,
    ).map { documents -> documents?.mapTo(mutableSetOf()) { it.id } }

    fun watchBlocks(uid: String): Flow<List<String>> = query(
        firebase.firestore.collection("blocks/$uid/users"),
        uid,
    ).mapDocuments { documents -> documents.map { it.id } }

    fun watchRequests(uid: String, incoming: Boolean): Flow<List<SocialRequest>> = query(
        firebase.firestore.collection("friendRequests")
            .whereEqualTo(if (incoming) "recipientUid" else "senderUid", uid)
            .whereEqualTo("state", "pending")
            .limit(50),
        uid,
    ).mapDocuments { documents ->
        documents.map {
            SocialRequest(
                it.id,
                it.getString("senderUid").orEmpty(),
                it.getString("recipientUid").orEmpty(),
                it.getString("senderHandle"),
                it.getString("senderDisplayName"),
                it.getString("recipientHandle"),
                it.getString("recipientDisplayName"),
            )
        }
    }

    fun watchInvites(uid: String): Flow<List<SocialInvite>> = query(
        firebase.firestore.collection("roomInvites/$uid/items")
            .whereIn("state", listOf("invited", "active"))
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(50),
        uid,
    ).mapDocuments { documents -> documents.map { SocialInvite(it.id, it.getString("state").orEmpty()) } }

    suspend fun getProfile(uid: String): SocialProfile {
        val actor = requireActor()
        val snapshot = firebase.firestore.document("profiles/$uid").get(Source.SERVER).await()
        checkActor(actor)
        return checkNotNull(snapshot.data).profile()
    }

    suspend fun getFriend(otherUid: String): SocialFriendDetails {
        val response = call("socialGetFriend", mapOf("otherUid" to otherUid))
        return SocialFriendDetails(response["profile"].map().profile(), response["sharing"].map().sharing())
    }

    suspend fun loadEntries(uid: String, kind: String, generation: Long): List<SharedEntry> {
        val actor = requireActor()
        val result = mutableListOf<SharedEntry>()
        var cursor: String? = null
        do {
            checkActor(actor)
            var query = firebase.firestore.collection(collectionFor(kind) + "/$uid/entries")
                .whereEqualTo("generation", generation).orderBy(FieldPath.documentId()).limit(50)
            cursor?.let { query = query.startAfter(it) }
            val page = query.get(Source.SERVER).await()
            checkActor(actor)
            result += page.documents.map { it.sharedEntry() }
            cursor = page.documents.lastOrNull()?.id
        } while (page.size() == 50)
        return result
    }

    /**
     * A friend's library entries that carry progress (titles they opened), for "friends who read this". Entries
     * without progress don't match the filter, so they cost no reads (composite index generation + progress.read).
     */
    suspend fun loadStartedEntries(uid: String, generation: Long): List<SharedEntry> {
        val actor = requireActor()
        val result = mutableListOf<SharedEntry>()
        var cursor: DocumentSnapshot? = null
        do {
            checkActor(actor)
            var query = firebase.firestore.collection("sharedLibraries/$uid/entries")
                .whereEqualTo("generation", generation).whereGreaterThanOrEqualTo("progress.read", 0)
                .orderBy("progress.read").limit(STARTED_PAGE)
            cursor?.let { query = query.startAfter(it) }
            val page = query.get(Source.SERVER).await()
            checkActor(actor)
            result += page.documents.map { it.sharedEntry() }
            cursor = page.documents.lastOrNull()
        } while (page.size().toLong() == STARTED_PAGE && result.size < MAX_STARTED)
        return result
    }

    /** Someone's ratings and reviews, newest first (mine, or a friend's while they share them). */
    suspend fun loadReviews(uid: String): List<SocialReview> {
        val actor = requireActor()
        val snapshot = firebase.firestore.collection("reviews/$uid/items")
            .orderBy("updatedAt", Query.Direction.DESCENDING).limit(MAX_REVIEWS)
            .get(Source.SERVER).await()
        checkActor(actor)
        return snapshot.documents.mapNotNull { document ->
            document.data?.let { SocialReview.parse(it) { v -> v.millis() } }
        }
    }

    /** Sets (stars 1–5, optional text) or deletes my review of [content]'s work; returns the stored review on set. */
    suspend fun review(action: String, content: SocialContent, rating: Int?, text: String?): SocialReview? {
        val response = call(
            "socialReview",
            mutation("action" to action, "content" to content.payload(), "rating" to rating, "text" to text),
        )
        return (response["review"] as? Map<*, *>)?.let { data ->
            @Suppress("UNCHECKED_CAST")
            SocialReview.parse(data as Map<String, Any?>) { it.millis() }
        }
    }

    /** My accepted friends' UIDs, read once from the server. */
    suspend fun loadFriendIds(): List<String> {
        val actor = requireActor()
        val snapshot = firebase.firestore.collection("friendships/$actor/accepted").get(Source.SERVER).await()
        checkActor(actor)
        return snapshot.documents.map { it.id }
    }

    /**
     * One page of a friend's projection, A–Z by title (composite index generation + content.title). Pass the returned
     * cursor to get the next page; it's null after the last one.
     */
    suspend fun loadEntriesPage(
        uid: String,
        kind: String,
        generation: Long,
        after: DocumentSnapshot?,
        size: Long,
    ): Pair<List<SocialContent>, DocumentSnapshot?> {
        val actor = requireActor()
        var query = firebase.firestore.collection(collectionFor(kind) + "/$uid/entries")
            .whereEqualTo("generation", generation).orderBy("content.title").limit(size)
        after?.let { query = query.startAfter(it) }
        val page = query.get(Source.SERVER).await()
        checkActor(actor)
        val items = page.documents.map { it.get("content").map().content() }
        return items to page.documents.lastOrNull()?.takeIf { page.size().toLong() == size }
    }

    /** How many entries a projection has (an aggregate: about one read per 1,000 entries). */
    suspend fun countEntries(uid: String, kind: String, generation: Long): Int {
        val actor = requireActor()
        val count = firebase.firestore.collection(collectionFor(kind) + "/$uid/entries")
            .whereEqualTo("generation", generation).count().get(AggregateSource.SERVER).await().count
        checkActor(actor)
        return count.toInt()
    }

    fun watchEntries(uid: String, kind: String, generation: Long): Flow<List<SocialContent>> = query(
        firebase.firestore.collection(collectionFor(kind) + "/$uid/entries")
            .whereEqualTo("generation", generation).orderBy(FieldPath.documentId()).limit(50),
        requireActor(),
    ).mapDocuments { documents -> documents.map { it.get("content").map().content() } }

    fun watchPresence(uid: String): Flow<Boolean?> = document("presence/$uid", requireActor())
        .mapDocument {
            when (it?.get("state")) {
                "online" -> true
                "offline" -> false
                else -> null
            }
        }

    fun watchLastSeen(uid: String): Flow<Long?> = document("lastSeen/$uid", requireActor())
        .mapDocument { it?.get("lastSeenAt").millis() }

    fun watchActivity(uid: String): Flow<Pair<SocialContent, Long>?> = document("activities/$uid", requireActor())
        .mapDocument { it?.activeContent() }

    suspend fun getActivity(uid: String): Pair<SocialContent, Long>? {
        val actor = requireActor()
        val snapshot = firebase.firestore.document("activities/$uid").get(Source.SERVER).await()
        currentCoroutineContext().ensureActive()
        checkActor(actor)
        return snapshot.data?.activeContent()
    }

    /** [progress] (content key → progress) is added to library entries only. */
    suspend fun publishEntries(
        kind: String,
        generation: Long,
        entries: List<SocialContent>,
        deleteKeys: List<String>,
        progress: Map<String, SocialProgress> = emptyMap(),
    ) {
        val actor = requireActor()
        val bounded = entries.map { it to false } + deleteKeys.map { it to true }
        bounded.chunked(50).forEach { page ->
            checkActor(actor)
            check(mutableProjectionPermit.value || page.all { it.second }) {
                "Projection privacy validation is pending"
            }
            call(
                "socialPublishEntries",
                mutation(
                    "kind" to kind,
                    "generation" to generation,
                    "entries" to page.filterNot { it.second }.map { (entry) ->
                        val content = entry as SocialContent
                        val started = progress[content.contentKey]?.takeIf { kind == "library" }
                        if (started ==
                            null
                        ) {
                            content.payload()
                        } else {
                            content.payload() + ("progress" to started.payload())
                        }
                    },
                    "deleteKeys" to page.filter { it.second }.map { it.first as String },
                ),
                serialGate = entriesGate,
            )
        }
    }

    suspend fun publishActivity(
        generation: Long,
        content: SocialContent?,
        chapterKey: String? = null,
        chapterLabel: String? = null,
    ) {
        call(
            "socialPublishActivity",
            mutation(
                "generation" to generation,
                "activity" to content?.let {
                    mapOf(
                        "content" to it.payload(),
                        "chapterKey" to checkNotNull(chapterKey),
                        "chapterLabel" to checkNotNull(chapterLabel),
                    )
                },
            ),
            serialGate = activityGate,
        )
    }

    suspend fun publishFeed(generation: Long, kind: String, content: SocialContent, chapterLabel: String?) {
        call(
            "socialPublishFeed",
            mutation(
                "generation" to generation,
                "kind" to kind,
                "content" to content.payload(),
                "chapterLabel" to chapterLabel,
            ),
        )
    }

    /** A friend's most recent feed items for [generation]; empty when not shared. */
    suspend fun loadFeed(uid: String, generation: Long): List<SocialFeedItem> {
        val actor = requireActor()
        // Rules must prove `expiresAt > request.time`; a bound an hour ahead tolerates device clock lag.
        val bound = Timestamp(Date(System.currentTimeMillis() + FEED_EXPIRY_MARGIN_MS))
        val snapshot = firebase.firestore.collection("feeds/$uid/items")
            .whereEqualTo("generation", generation)
            .whereGreaterThan("expiresAt", bound)
            .orderBy("expiresAt", Query.Direction.DESCENDING)
            .limit(20)
            .get(Source.SERVER)
            .await()
        currentCoroutineContext().ensureActive()
        checkActor(actor)
        return snapshot.documents.mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            SocialFeedItem(
                kind = data["kind"] as? String ?: return@mapNotNull null,
                content = data["content"].map().content(),
                chapterLabel = data["chapterLabel"] as? String,
                createdAt = data["createdAt"].millis() ?: return@mapNotNull null,
            )
        }
    }

    /** Uploads an avatar or banner (PNG, JPEG, WebP or GIF); the style callable then publishes it. */
    suspend fun uploadProfileMedia(kind: String, bytes: ByteArray, contentType: String) {
        val uid = requireActor()
        val metadata = StorageMetadata.Builder().setContentType(contentType).build()
        firebase.storage.reference.child("profileMedia/$uid/$kind").putBytes(bytes, metadata).await()
        checkActor(uid)
    }

    /** Uploads a cover thumbnail for one of my shared titles (see [CoverThumbnails]). */
    suspend fun uploadCoverThumb(contentKey: String, jpeg: ByteArray) {
        val uid = requireActor()
        val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
        firebase.storage.reference.child("coverThumbs/$uid/$contentKey").putBytes(jpeg, metadata).await()
        checkActor(uid)
    }

    /**
     * Uploads the cover of what I'm reading for my Discord status and returns its download link (anyone with the
     * link can open it, like the cover on Discord itself). Keeps only this one: older Discord covers are deleted.
     * [name] is 32 hex characters (see [mihon.feature.discord.DiscordCovers.name]).
     */
    suspend fun publishDiscordCover(name: String, jpeg: ByteArray): String {
        val uid = requireActor()
        val folder = firebase.storage.reference.child("discordCovers/$uid")
        val cover = folder.child(name)
        cover.putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
        val link = cover.downloadUrl.await().toString()
        checkActor(uid)
        try {
            folder.listAll().await().items.filter { it.name != name }.forEach { it.delete().await() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Leftovers are removed with the next cover (or the account).
        }
        return link
    }

    /** Best effort: removes my uploaded thumbnail for [contentKey]. */
    suspend fun deleteCoverThumb(contentKey: String) {
        val uid = requireActor()
        try {
            firebase.storage.reference.child("coverThumbs/$uid/$contentKey").delete().await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Already gone, or offline: nothing else to do.
        }
    }

    suspend fun deleteProfileMedia(kind: String) {
        val uid = requireActor()
        try {
            firebase.storage.reference.child("profileMedia/$uid/$kind").delete().await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Already gone or unreachable: the profile no longer references it either way.
        }
    }

    /** [avatar]/[banner] are `keep`, `set` (after an upload) or `remove`. */
    suspend fun updateProfileStyle(bio: String, accent: Int, avatar: String, banner: String): SocialProfile {
        val response = call(
            "socialUpdateProfileStyle",
            mutation("bio" to bio, "accent" to accent, "avatar" to avatar, "banner" to banner),
        )
        return response["profile"].map().profile()
    }

    /** Friends-only media bytes, cached per version; null when missing or not readable. */
    suspend fun loadProfileMedia(uid: String, kind: String, version: Long): ByteArray? {
        val key = "$uid/$kind/$version"
        mediaCache.get(key)?.let { return it }
        requireActor()
        val bytes = try {
            firebase.storage.reference.child("profileMedia/$uid/$kind").getBytes(MAX_PROFILE_MEDIA_BYTES).await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        mediaCache.put(key, bytes)
        return bytes
    }

    private val mediaCache = object : LruCache<String, ByteArray>(MEDIA_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** "Ask to read along" with a friend who shares what they're reading. */
    suspend fun askToReadAlong(otherUid: String) {
        call("socialJoinRequest", mutation("action" to "ask", "otherUid" to otherUid))
    }

    suspend fun declineJoinRequest(requesterUid: String) {
        call("socialJoinRequest", mutation("action" to "decline", "otherUid" to requesterUid))
    }

    /** Friends asking to read along with me, newest first; expired ones are left out. */
    fun watchJoinRequests(uid: String): Flow<List<JoinRequest>> = query(
        firebase.firestore.collection("joinRequests/$uid/items").limit(20),
        uid,
    ).mapDocuments { documents ->
        val now = System.currentTimeMillis()
        documents.mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            JoinRequest(
                requesterUid = data["requesterUid"] as? String ?: return@mapNotNull null,
                name = (data["name"] as? String).orEmpty().take(40),
                handle = (data["handle"] as? String).orEmpty().take(24),
                createdAt = data["createdAt"].millis() ?: 0,
                expiresAt = data["expiresAt"].millis() ?: 0,
            ).takeIf { it.expiresAt > now }
        }.sortedByDescending { it.createdAt }
    }

    /** [text] may be null when [content] (a recommended title) is set; [replyTo] is the seq of the message answered. */
    suspend fun sendMessage(otherUid: String, text: String?, content: SocialContent? = null, replyTo: Long? = null) {
        val fields = buildList {
            add("action" to "send")
            add("otherUid" to otherUid)
            add("text" to text)
            if (content != null) add("content" to content.payload())
            if (replyTo != null) add("replyTo" to replyTo)
        }
        call("socialMessage", mutation(*fields.toTypedArray()))
    }

    /** Sets, replaces or (the same emoji again) removes my reaction to message [seq]. */
    suspend fun reactToMessage(otherUid: String, seq: Long, emoji: String) {
        call(
            "socialMessage",
            mutation("action" to "react", "otherUid" to otherUid, "text" to null, "seq" to seq, "emoji" to emoji),
        )
    }

    suspend fun markChatRead(otherUid: String) {
        call("socialMessage", mutation("action" to "read", "otherUid" to otherUid, "text" to null))
    }

    /** My conversations, most recent first (the backend removes them when a friendship ends). */
    fun watchChats(uid: String): Flow<List<ChatSummary>> = query(
        firebase.firestore.collection(
            "chatIndex/$uid/chats",
        ).orderBy("updatedAt", Query.Direction.DESCENDING).limit(50),
        uid,
    ).mapDocuments { documents ->
        documents.mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            ChatSummary(
                otherUid = data["otherUid"] as? String ?: return@mapNotNull null,
                chatId = data["chatId"] as? String ?: return@mapNotNull null,
                lastText = data["lastText"] as? String ?: "",
                lastSenderUid = data["lastSenderUid"] as? String ?: "",
                updatedAt = data["updatedAt"].millis() ?: 0,
                unread = (data["unread"] as? Number)?.toInt() ?: 0,
            )
        }
    }

    /** Unread private and group messages across all conversations, for badges; 0 while signed out. */
    fun watchUnreadMessages(): Flow<Int> = authChanges().flatMapLatest { account ->
        if (account == null) {
            flowOf(0)
        } else {
            fun unread(path: String) = query(
                firebase.firestore.collection(path).whereGreaterThan("unread", 0).limit(50),
                account.uid,
            ).mapDocuments { documents -> documents.sumOf { (it.get("unread") as? Number)?.toInt() ?: 0 } }
                .catch { emit(0) }
            combine(unread("chatIndex/${account.uid}/chats"), unread("groupIndex/${account.uid}/groups")) { a, b ->
                a + b
            }
        }
    }.catch { emit(0) }

    /** The latest 100 messages of a conversation, oldest first. Ends with an error once the friendship ends. */
    fun watchChatMessages(chatId: String): Flow<List<ChatMessage>> = query(
        firebase.firestore.collection("chats/$chatId/messages").orderBy("seq", Query.Direction.DESCENDING).limit(100),
        requireActor(),
    ).mapDocuments { documents ->
        documents.mapNotNull { document ->
            document.data?.let { ChatMessages.parse(it) { value -> value.millis() } }
        }.sortedBy { it.seq }
    }

    /** Sends a friend a light "keep reading" push (one per friend per 24 h; the backend enforces it). */
    suspend fun nudge(otherUid: String) {
        call("socialNudge", mutation("otherUid" to otherUid))
    }

    /** "Let's read this": [action] is `add` or `remove`; returns `wished`, `matched` or `removed`. */
    suspend fun readWish(action: String, otherUid: String, content: SocialContent): String = call(
        "socialReadWish",
        mutation("action" to action, "otherUid" to otherUid, "content" to content.payload()),
    )["state"] as? String ?: ""

    /** My read wishes (owner-only index); expired ones are left out. */
    fun watchReadWishes(uid: String): Flow<List<ReadWish>> = query(
        firebase.firestore.collection("readWishIndex/$uid/items").limit(100),
        uid,
    ).mapDocuments { documents ->
        val now = System.currentTimeMillis()
        ReadWish.sorted(
            documents.mapNotNull { document ->
                document.data?.let { ReadWish.parse(it, { value -> value.millis() }, now) }
            },
        )
    }

    /** [action] is `register` or `unregister`; the backend keeps the newest ten devices per account. */
    suspend fun registerDevice(action: String, token: String) {
        call("socialRegisterDevice", mutation("action" to action, "token" to token))
    }

    /** Counts a finished chapter (stats under consent, XP always); [roomId] earns the reading-together bonus. */
    suspend fun recordChapter(generation: Long, pages: Int, roomId: String? = null): XpReward? {
        val fields = buildList {
            add("generation" to generation)
            add("pages" to pages)
            if (roomId != null) add("roomId" to roomId)
        }
        val result = call("socialRecordChapter", mutation(*fields.toTypedArray()))
        val xp = result["xp"] as? Map<*, *> ?: return null
        return XpReward(
            gained = (xp["gained"] as? Number)?.toInt() ?: 0,
            total = (xp["total"] as? Number)?.toLong() ?: 0,
            level = (xp["level"] as? Number)?.toInt() ?: 1,
            leveledUp = xp["leveledUp"] == true,
            unlocked = (xp["unlocked"] as? List<*>).orEmpty().filterIsInstance<String>(),
            freezeUsed = (xp["freezeUsed"] as? Number)?.toInt() ?: 0,
            challenges = (xp["challenges"] as? List<*>).orEmpty().filterIsInstance<String>(),
            challengeXp = (xp["challengeXp"] as? Number)?.toInt() ?: 0,
            season = xp["season"] as? String,
        )
    }

    /** XP and level for [uid]: always mine; a friend's only while they share stats (otherwise null). */
    fun watchXp(uid: String): Flow<SocialXp?> = document("xp/$uid", requireActor()).mapDocument { it?.xp() }

    suspend fun loadXp(uid: String): SocialXp? {
        val actor = requireActor()
        val snapshot = try {
            firebase.firestore.document("xp/$uid").get(Source.SERVER).await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        checkActor(actor)
        return snapshot.data?.xp()
    }

    private fun Map<String, Any?>.xp(): SocialXp = parseXp(this)

    private suspend fun groupChat(
        action: String,
        groupId: String? = null,
        name: String? = null,
        uids: List<String>? = null,
        text: String? = null,
    ): Map<String, Any?> = call(
        "socialGroupChat",
        mutation("action" to action, "groupId" to groupId, "name" to name, "uids" to uids, "text" to text),
    )

    /** Creates a group with friends and returns its ID. */
    suspend fun createGroup(name: String, uids: List<String>): String =
        groupChat("create", name = name, uids = uids)["groupId"] as? String ?: error("Invalid group response")

    suspend fun sendGroupMessage(
        groupId: String,
        text: String?,
        content: SocialContent? = null,
        replyTo: Long? = null,
    ) {
        if (content == null && replyTo == null) {
            groupChat("send", groupId = groupId, text = text)
            return
        }
        val fields = buildList {
            add("action" to "send")
            add("groupId" to groupId)
            add("name" to null)
            add("uids" to null)
            add("text" to text)
            if (content != null) add("content" to content.payload())
            if (replyTo != null) add("replyTo" to replyTo)
        }
        call("socialGroupChat", mutation(*fields.toTypedArray()))
    }

    /** Sets, replaces or (the same emoji again) removes my reaction to group message [seq]. */
    suspend fun reactInGroup(groupId: String, seq: Long, emoji: String) {
        call(
            "socialGroupChat",
            mutation(
                "action" to "react",
                "groupId" to groupId,
                "name" to null,
                "uids" to null,
                "text" to null,
                "seq" to seq,
                "emoji" to emoji,
            ),
        )
    }

    suspend fun markGroupRead(groupId: String) {
        groupChat("read", groupId = groupId)
    }

    suspend fun addToGroup(groupId: String, uids: List<String>) {
        groupChat("add", groupId = groupId, uids = uids)
    }

    suspend fun removeFromGroup(groupId: String, uid: String) {
        groupChat("remove", groupId = groupId, uids = listOf(uid))
    }

    suspend fun renameGroup(groupId: String, name: String) {
        groupChat("rename", groupId = groupId, name = name)
    }

    suspend fun leaveGroup(groupId: String) {
        groupChat("leave", groupId = groupId)
    }

    /** My group chats, most recent first. */
    fun watchGroups(uid: String): Flow<List<GroupSummary>> = query(
        firebase.firestore.collection("groupIndex/$uid/groups").orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(50),
        uid,
    ).mapDocuments { documents ->
        documents.mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            GroupSummary(
                groupId = data["groupId"] as? String ?: return@mapNotNull null,
                name = (data["name"] as? String).orEmpty().take(40),
                members = (data["members"] as? Number)?.toInt() ?: 0,
                lastText = (data["lastText"] as? String).orEmpty(),
                lastSenderName = (data["lastSenderName"] as? String).orEmpty(),
                unread = (data["unread"] as? Number)?.toInt() ?: 0,
                updatedAt = data["updatedAt"].millis() ?: 0,
            )
        }
    }

    /** The group, or null once I'm no longer a member (the read is denied or it's gone). */
    fun watchGroup(groupId: String): Flow<SocialGroup?> = document("groups/$groupId", requireActor())
        .mapDocument { data ->
            data ?: return@mapDocument null
            val names = data["names"] as? Map<*, *> ?: emptyMap<Any, Any>()
            SocialGroup(
                groupId = groupId,
                name = (data["name"] as? String).orEmpty(),
                ownerUid = (data["ownerUid"] as? String).orEmpty(),
                members = (data["members"] as? List<*>).orEmpty().mapNotNull { uid ->
                    val card = names[uid] as? Map<*, *> ?: return@mapNotNull null
                    GroupMember(
                        uid = uid as? String ?: return@mapNotNull null,
                        name = (card["name"] as? String).orEmpty(),
                        handle = (card["handle"] as? String).orEmpty(),
                        accent = (card["accent"] as? Number)?.toInt()?.coerceIn(0, ProfileStyle.ACCENTS.lastIndex) ?: 0,
                        since = (card["since"] as? Number)?.toLong() ?: 0,
                    )
                },
            )
        }

    /** The latest 100 messages I may see (from when I joined, [since]), oldest first. */
    fun watchGroupMessages(groupId: String, since: Long): Flow<List<GroupMessage>> = query(
        firebase.firestore.collection("groups/$groupId/messages").whereGreaterThanOrEqualTo("seq", since)
            .orderBy("seq", Query.Direction.DESCENDING).limit(100),
        requireActor(),
    ).mapDocuments { documents ->
        documents.mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            GroupMessage(
                seq = (data["seq"] as? Number)?.toLong() ?: return@mapNotNull null,
                kind = data["kind"] as? String ?: return@mapNotNull null,
                senderUid = (data["senderUid"] as? String).orEmpty(),
                senderName = (data["senderName"] as? String).orEmpty().take(40),
                text = (data["text"] as? String).orEmpty(),
                event = data["event"] as? String,
                createdAt = data["createdAt"].millis() ?: 0,
                content = data["content"].sharedContent(),
                replyTo = ChatReactions.quote(data["replyTo"]),
                reactions = ChatReactions.parse(data["reactions"]),
            )
        }.sortedBy { it.seq }
    }

    /** Today's counters for [uid], or null when missing or not shared with me (rules deny). */
    suspend fun loadDailyStats(uid: String, day: String): DailyStats? {
        val actor = requireActor()
        val snapshot = try {
            firebase.firestore.document("dailyStats/$uid/days/$day").get(Source.SERVER).await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        checkActor(actor)
        val data = snapshot.data ?: return null
        return DailyStats(
            day,
            (data["chapters"] as? Number)?.toInt() ?: 0,
            (data["pages"] as? Number)?.toInt() ?: 0,
            (data["streak"] as? Number)?.toInt() ?: 1,
        )
    }

    /**
     * A global ranking (top 50 who didn't opt out): a UTC day's, most chapters first, or [LeaderboardEntry.ALL_TIME],
     * most XP first. Empty when nobody is on it yet.
     */
    suspend fun loadLeaderboard(day: String): List<LeaderboardEntry> {
        val actor = requireActor()
        val snapshot = firebase.firestore.document("leaderboards/$day").get(Source.SERVER).await()
        checkActor(actor)
        val entries = snapshot.get("entries") as? Map<*, *> ?: return emptyList()
        return entries.mapNotNull { (uid, value) ->
            val data = value as? Map<*, *> ?: return@mapNotNull null
            LeaderboardEntry(
                uid = uid as? String ?: return@mapNotNull null,
                handle = data["handle"] as? String ?: return@mapNotNull null,
                displayName = data["displayName"] as? String ?: return@mapNotNull null,
                accent = (data["accent"] as? Number)?.toInt()?.coerceIn(0, ProfileStyle.ACCENTS.lastIndex) ?: 0,
                chapters = (data["chapters"] as? Number)?.toInt() ?: 0,
                streak = (data["streak"] as? Number)?.toInt() ?: 1,
                level = (data["level"] as? Number)?.toInt(),
                xp = (data["xp"] as? Number)?.toInt(),
                avatar = data["avatar"].profileMedia(),
                banner = data["banner"].profileMedia(),
            )
        }.sortedByDescending { if (day == LeaderboardEntry.ALL_TIME) it.xp ?: 0 else it.chapters }
    }

    /** Clears a hidden-title marker; nothing is republished until the user shares again. */
    suspend fun unhideTitle(contentKey: String) {
        call("socialUnhideTitle", mutation("contentKey" to contentKey))
    }

    suspend fun sendRoomMessage(roomId: String, text: String?, emoji: String?) {
        call(
            "socialRoomMessage",
            mutation(
                "roomId" to roomId,
                "kind" to if (emoji != null) RoomMessage.KIND_REACTION else RoomMessage.KIND_TEXT,
                "text" to text,
                "emoji" to emoji,
            ),
        )
    }

    /** Ready check: [kind] is [RoomMessage.KIND_READY] or [RoomMessage.KIND_WAIT]. */
    suspend fun sendRoomSignal(roomId: String, kind: String) {
        require(kind in RoomMessage.SIGNALS)
        call("socialRoomMessage", mutation("roomId" to roomId, "kind" to kind, "text" to null, "emoji" to null))
    }

    /** The latest 30 messages of a room, oldest first. Fails (and ends) when the room becomes unreadable. */
    fun watchRoomMessages(roomId: String): Flow<List<RoomMessage>> = query(
        firebase.firestore.collection("rooms/$roomId/messages").orderBy("seq", Query.Direction.DESCENDING).limit(30),
        requireActor(),
    ).mapDocuments { documents ->
        documents.mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            RoomMessage(
                seq = (data["seq"] as? Number)?.toLong() ?: return@mapNotNull null,
                senderUid = data["senderUid"] as? String ?: return@mapNotNull null,
                kind = data["kind"] as? String ?: return@mapNotNull null,
                text = data["text"] as? String,
                emoji = data["emoji"] as? String,
                createdAt = data["createdAt"].millis() ?: 0,
            )
        }.sortedBy { it.seq }
    }

    suspend fun inviteToRoom(roomId: String, otherUid: String): SocialRoom =
        roomAction("invite", roomId = roomId, otherUid = otherUid)

    suspend fun removeFromRoom(roomId: String, otherUid: String): SocialRoom =
        roomAction("kick", roomId = roomId, otherUid = otherUid)

    suspend fun roomAction(
        action: String,
        roomId: String? = null,
        otherUid: String? = null,
        sessionId: String? = null,
        content: SocialContent? = null,
        position: SocialPosition? = null,
        /** Only with create and open: let friends drop in (left out otherwise, as older backends expect). */
        open: Boolean? = null,
    ): SocialRoom {
        val fields = buildList {
            add("action" to action)
            add("roomId" to roomId)
            add("otherUid" to otherUid)
            add("sessionId" to sessionId)
            add("content" to content?.payload())
            add("position" to position?.payload())
            if (open != null) add("open" to open)
        }
        val response = call("socialRoomAction", mutation(*fields.toTypedArray()))
        return response["room"].map().room(response["roomId"] as String)
    }

    /** Host only: let friends drop in to this room, or stop it. */
    suspend fun setRoomOpen(
        roomId: String,
        open: Boolean,
    ): SocialRoom = roomAction("open", roomId = roomId, open = open)

    /**
     * Shares my place in the room (chapter key and page index only; null clears it). The backend allows one update
     * every [ROOM_POSITION_GAP_MS]; room members see it.
     */
    suspend fun publishMemberPage(roomId: String, chapterKey: String?, pageIndex: Int) {
        call(
            "socialRoomAction",
            mutation(
                "action" to "position",
                "roomId" to roomId,
                "otherUid" to null,
                "sessionId" to null,
                "content" to null,
                "position" to chapterKey?.let { mapOf("chapterKey" to it, "pageIndex" to pageIndex) },
            ),
        )
    }

    /** A friend's open room, while they let friends drop in (null otherwise, or when their activity isn't shared). */
    fun watchOpenRoom(uid: String): Flow<OpenRoomCard?> = document("openRooms/$uid", requireActor())
        .mapDocument { data ->
            data?.let {
                OpenRoomCard(
                    roomId = it["roomId"] as? String ?: return@let null,
                    hostUid = uid,
                    title = (it["title"] as? String).orEmpty().take(200),
                    memberCount = (it["memberCount"] as? Number)?.toInt() ?: 0,
                    maxMembers = (it["maxMembers"] as? Number)?.toInt() ?: 6,
                    leaseExpiresAt = it["leaseExpiresAt"].millis() ?: 0,
                )
            }
        }

    fun watchRoom(roomId: String): Flow<SocialRoom?> = document("rooms/$roomId", requireActor())
        // Rules deny reads once the lease expires or the friendship ends; that is the room ending, not a fault.
        .catch { error ->
            if (error is FirebaseFirestoreException &&
                error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            ) {
                throw SocialRoomUnavailable()
            }
            throw error
        }
        .mapDocument { data ->
            data?.room(roomId)?.takeIf { it.state != "closed" && it.leaseExpiresAt > System.currentTimeMillis() }
        }

    suspend fun getRoom(roomId: String): SocialRoom? {
        val actor = requireActor()
        val snapshot = firebase.firestore.document("rooms/$roomId").get(Source.SERVER).await()
        checkActor(actor)
        return snapshot.data?.room(roomId)?.takeIf {
            it.state != "closed" && it.leaseExpiresAt > System.currentTimeMillis()
        }
    }

    suspend fun publishPosition(
        roomId: String,
        sessionId: String,
        position: SocialPosition,
        sequence: Long,
    ) {
        val actor = requireActor()
        val roomRef = firebase.firestore.document("rooms/$roomId")
        // A plain update instead of a transaction saves the room and lease reads on every scroll update: the rules
        // already require this host's live lease and session for the room's epoch, an active room and exactly the next
        // sequence, so a stale or competing write is rejected server-side.
        // SDK completion owns the mutex release; cancelling this caller stays prompt while a
        // recreated reader cannot start an overlapping write.
        awaitSerialTask(positionGate) {
            checkActor(actor)
            roomRef.update(
                mapOf(
                    "position" to position.payload(),
                    "sequence" to sequence,
                    "writerSessionId" to sessionId,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
        }
        currentCoroutineContext().ensureActive()
        checkActor(actor)
    }

    // The process foreground lifecycle owns this coroutine. No background service or disk outbox.
    suspend fun maintainConnection(uid: String) {
        checkActor(uid)
        if (presenceBlockedUid.get() == uid) return
        val connection = firebase.database.getReference("connections/$uid").child(UUID.randomUUID().toString())
        val registration = PresenceRegistration(uid, checkNotNull(currentCoroutineContext()[Job]))
        activePresence.set(registration)
        val keptAlive = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = Unit
            override fun onCancelled(error: DatabaseError) = Unit
        }
        connection.addValueEventListener(keptAlive)
        try {
            callbackFlow {
                val connected = firebase.database.getReference(".info/connected")
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        trySend(snapshot.getValue(Boolean::class.java) == true)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        close(error.toException())
                    }
                }
                connected.addValueEventListener(listener)
                awaitClose { connected.removeEventListener(listener) }
            }.collect { connected ->
                logcat(LogPriority.DEBUG) { "Social presence connected=$connected" }
                if (connected) {
                    checkActor(uid)
                    if (presenceBlockedUid.get() == uid) return@collect
                    connection.onDisconnect().removeValue().await()
                    checkActor(uid)
                    if (presenceBlockedUid.get() == uid) return@collect
                    connection.setValue(mapOf("connectedAt" to ServerValue.TIMESTAMP)).await()
                }
            }
            awaitCancellation()
        } finally {
            connection.removeEventListener(keptAlive)
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) {
                    try {
                        connection.removeValue().await()
                        registration.removed = true
                        connection.onDisconnect().cancel().await()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // The armed disconnect handler remains the network-loss fallback.
                    }
                }
            }
            activePresence.compareAndSet(registration, null)
            registration.stopped.complete(Unit)
        }
    }

    /** Cancels this process's presence owner and waits for cleanup before dropping its Auth token. */
    suspend fun stopPresenceBeforeSignOut(): Boolean {
        val uid = currentUid ?: return true
        presenceBlockedUid.set(uid)
        mutablePresencePaused.value = true
        val registration = activePresence.get()?.takeIf { it.uid == uid } ?: return true
        registration.owner.cancel()
        val stopped = withTimeoutOrNull(3_000) {
            registration.stopped.await()
            true
        } ?: false
        presenceCleanupPending = !stopped || !registration.removed
        return !presenceCleanupPending
    }

    internal fun permitProjection(uid: String, permitted: Boolean) {
        if (currentUid == uid) mutableProjectionPermit.value = permitted
    }

    private class PresenceRegistration(val uid: String, val owner: Job) {
        val stopped = CompletableDeferred<Unit>()

        @Volatile var removed = false
    }

    private suspend fun call(
        name: String,
        data: Map<String, Any?>,
        serialGate: CompletionOwnedGate? = null,
    ): Map<String, Any?> {
        val actor = requireActor()
        val result = try {
            if (BuildConfig.SOCIAL_ENVIRONMENT == "emulator") {
                val token = checkNotNull(firebase.auth.currentUser?.getIdToken(false)?.await()?.token)
                currentCoroutineContext().ensureActive()
                checkActor(actor)
                val start = {
                    checkActor(actor)
                    emulatorCallables.start(name, data, token)
                }
                if (serialGate != null) awaitSerialTask(serialGate, start) else start().await()
            } else {
                val reference = firebase.functions.getHttpsCallable(name).apply { setTimeout(15, TimeUnit.SECONDS) }
                if (serialGate != null) {
                    awaitSerialTask(serialGate) {
                        checkActor(actor)
                        reference.call(data)
                    }.data.map()
                } else {
                    reference.call(data).await().data.map()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) {
                val code = (error as? FirebaseFunctionsException)?.code ?: (error as? SocialCallableFailure)?.status
                logcat(LogPriority.ERROR) {
                    "Social callable $name failed: ${error.javaClass.simpleName}, code=$code, " +
                        "cause=${error.cause?.javaClass?.simpleName}"
                }
            }
            throw error
        }
        currentCoroutineContext().ensureActive()
        checkActor(actor)
        return result
    }

    private suspend fun <T> awaitSerialTask(gate: CompletionOwnedGate, create: () -> Task<T>): T = gate.await(
        create = create,
        onComplete = { task, completed -> task.addOnCompleteListener { completed() } },
        wait = { task -> task.await() },
    )

    private fun requireActor(): String = checkNotNull(currentUid) { "Sign in to use social features" }

    private fun checkActor(uid: String) {
        check(currentUid == uid) { "Social account changed" }
    }

    private fun document(path: String, actor: String): Flow<Map<String, Any?>?> = callbackFlow {
        val reference = firebase.firestore.document(path)
        val registration = reference.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (currentUid !=
                actor
            ) {
                close(IllegalStateException("Social account changed"))
                return@addSnapshotListener
            }
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                trySend(snapshot.data)
            } else if (snapshot != null && snapshot.metadata.isFromCache) {
                trySend(null)
            }
        }
        awaitClose { registration.remove() }
    }

    private fun query(query: Query, actor: String): Flow<List<DocumentSnapshot>> =
        queryOrUnknown(query, actor).map { it ?: emptyList() }

    /** Like [query], but a cache-only (offline) snapshot is `null` ("unknown") instead of an empty result. */
    private fun queryOrUnknown(query: Query, actor: String): Flow<List<DocumentSnapshot>?> = callbackFlow {
        val registration = query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (currentUid !=
                actor
            ) {
                close(IllegalStateException("Social account changed"))
                return@addSnapshotListener
            }
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                trySend(snapshot.documents)
            } else if (snapshot != null && snapshot.metadata.isFromCache) {
                trySend(null)
            }
        }
        awaitClose { registration.remove() }
    }

    private fun collectionFor(kind: String): String = when (kind) {
        "library" -> "sharedLibraries"
        "favorites" -> "sharedFavorites"
        else -> error("Unknown social projection kind")
    }

    private fun mutation(vararg fields: Pair<String, Any?>): Map<String, Any?> =
        mapOf("operationId" to UUID.randomUUID().toString(), *fields)
}

private fun <T> Flow<Map<String, Any?>?>.mapDocument(transform: (Map<String, Any?>?) -> T): Flow<T> =
    map(transform)

private fun <T> Flow<List<DocumentSnapshot>>.mapDocuments(transform: (List<DocumentSnapshot>) -> T): Flow<T> =
    map(transform)

@Suppress("UNCHECKED_CAST")
internal fun Any?.map(): Map<String, Any?> = this as? Map<String, Any?> ?: error("Invalid social response")

internal fun Map<String, Any?>.profile() = SocialProfile(
    this["uid"] as String,
    this["handle"] as String,
    this["displayName"] as String,
    bio = this["bio"] as? String ?: "",
    accent = (this["accent"] as? Number)?.toInt()?.coerceIn(0, ProfileStyle.ACCENTS.lastIndex) ?: 0,
    avatar = this["avatar"].profileMedia(),
    banner = this["banner"].profileMedia(),
)

private fun Any?.profileMedia(): ProfileMedia? {
    val data = this as? Map<*, *> ?: return null
    val version = (data["version"] as? Number)?.toLong() ?: data["version"].millis() ?: return null
    return ProfileMedia(version, data["contentType"] as? String ?: return null)
}

internal fun Map<String, Any?>.sharing() = SocialSharing(
    generation = (this["generation"] as Number).toLong(),
    library = this["library"] as String,
    favorites = this["favorites"] as String,
    activity = this["activity"] as String,
    presence = this["presence"] as String,
    lastSeen = this["lastSeen"] as String,
    invisible = this["invisible"] as? Boolean ?: false,
    // Settings written before the feed existed have no field; absent means private.
    feed = this["feed"] as? String ?: "private",
    stats = this["stats"] as? String ?: "private",
    globalRanking = this["globalRanking"] as? Boolean ?: true,
    rankingMedia = this["rankingMedia"] as? Boolean ?: true,
    reviews = this["reviews"] as? Boolean ?: true,
    progress = this["progress"] as? Boolean ?: true,
    progressStored = this["progress"] is Boolean,
)

private fun DocumentSnapshot.sharedEntry() = SharedEntry(
    get("content").map().content(),
    SocialProgress.parse(get("progress")),
)

internal fun Map<String, Any?>.content() = SocialContent(
    this["contentKey"] as String,
    this["title"] as String,
    this["sourceKey"] as String,
    this["sourceWorkKey"] as String,
    this["editionKey"] as String,
    this["canonicalProvider"] as? String,
    this["canonicalId"] as? String,
    SocialIdentity.publicCover(this["coverUrl"] as? String),
    (this["source"] as? Map<*, *>)?.let { source ->
        val id = (source["id"] as? String)?.toLongOrNull() ?: return@let null
        SocialSource(
            id,
            (source["name"] as? String).orEmpty().take(80),
            (source["lang"] as? String).orEmpty().take(12),
            source["pkg"] as? String,
            SocialSource.validUrl(source["url"] as? String),
        )
    },
    thumb = this["thumb"] == true,
)

/** A title inside a message; anything malformed is simply not shown as a card. */
@Suppress("UNCHECKED_CAST")
private fun Any?.sharedContent(): SocialContent? =
    (this as? Map<String, Any?>)?.let { runCatching { it.content() }.getOrNull() }

private fun Map<String, Any?>.activeContent(): Pair<SocialContent, Long>? {
    val expiry = this["expiresAt"].millis() ?: return null
    return if (expiry > System.currentTimeMillis()) this["content"].map().content() to expiry else null
}

internal fun Map<String, Any?>.position() = SocialPosition(
    this["chapterKey"] as String,
    this["chapterLabel"] as String,
    (this["pageIndex"] as Number).toInt(),
    (this["pageCount"] as Number).toInt(),
    this["pageMappingKey"] as String,
    (this["stripPosition"] as? Number)?.toDouble(),
)

internal fun Map<String, Any?>.room(id: String) = SocialRoom(
    id = id,
    hostUid = this["hostUid"] as String,
    participants = (this["participants"] as List<*>).filterIsInstance<String>(),
    members = (this["members"] as? Map<*, *>).orEmpty().mapNotNull { (uid, value) ->
        val data = value as? Map<*, *> ?: return@mapNotNull null
        val key = uid as? String ?: return@mapNotNull null
        key to RoomMember(
            uid = key,
            state = data["state"] as? String ?: RoomMember.INVITED,
            name = (data["name"] as? String).orEmpty().take(40),
            accent = (data["accent"] as? Number)?.toInt()?.coerceIn(0, ProfileStyle.ACCENTS.lastIndex) ?: 0,
            joinedAt = data["joinedAt"].millis(),
            page = (data["page"] as? Map<*, *>)?.let { page ->
                val chapterKey = page["chapterKey"] as? String ?: return@let null
                val pageIndex = (page["pageIndex"] as? Number)?.toInt() ?: return@let null
                MemberPage(chapterKey, pageIndex, page["at"].millis() ?: 0)
            },
        )
    }.toMap(),
    maxMembers = (this["maxMembers"] as? Number)?.toInt() ?: 6,
    content = this["content"].map().content(),
    state = this["state"] as String,
    epoch = (this["epoch"] as Number).toLong(),
    sequence = (this["sequence"] as Number).toLong(),
    position = this["position"]?.map()?.position(),
    writerSessionId = this["writerSessionId"] as? String,
    leaseExpiresAt = checkNotNull(this["leaseExpiresAt"].millis()),
    open = this["open"] == true,
)

internal fun Any?.millis(): Long? = when (this) {
    is Timestamp -> toDate().time
    is Number -> toLong()
    else -> null
}
private const val FEED_EXPIRY_MARGIN_MS = 3_600_000L
private const val STARTED_PAGE = 100L
private const val MAX_STARTED = 500
private const val MAX_REVIEWS = 100L
private const val MAX_PROFILE_MEDIA_BYTES = 5L * 1024 * 1024
private const val MEDIA_CACHE_BYTES = 24 * 1024 * 1024
