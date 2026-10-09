package mihon.feature.weblibrary

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.feature.social.SocialRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

@Inject
@SingleIn(AppScope::class)
class WebLibraryPreferences(private val store: PreferenceStore) {
    /** "Library on the web": on by default (sharing defaults on; it's the user's own browser). */
    val enabled: Preference<Boolean> = store.getBoolean("web_library_enabled", true)

    /** Random per install, kept out of backups (app state) so a restored phone gets its own. */
    private val deviceIdPreference: Preference<String> =
        store.getString(Preference.appStateKey("web_library_device_id"), "")

    @get:Synchronized
    val deviceId: String
        get() = deviceIdPreference.get().takeIf { WebLibrarySignaling.DEVICE_ID.matches(it) }
            ?: newDeviceId().also(deviceIdPreference::set)

    private fun newDeviceId(): String {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

data class WebLibraryState(
    val connected: Boolean = false,
    /** The phone stays available for the browser until then (Share library with my browser); 0 when not. */
    val availableUntil: Long = 0,
)

/**
 * Owns the web library lifecycle (docs/WEB_LIBRARY_PROTOCOL.md section 4): publishes this phone while the app is in
 * the foreground and signed in, or while a session runs or the user asked to stay available; listens for the
 * browser's offers while the process lives and the setting is on; one browser session at a time.
 */
@Inject
@SingleIn(AppScope::class)
class WebLibraryHost(
    private val context: Context,
    private val repository: SocialRepository,
    private val signaling: WebLibrarySignaling,
    // Lazy: the library, download and tracking classes are built when a browser first connects, not at app start.
    private val backend: Lazy<WebLibraryAppBackend>,
    private val preferences: WebLibraryPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(WebLibraryState())
    val state: StateFlow<WebLibraryState> = mutableState.asStateFlow()
    private val active = AtomicReference<Connection?>(null)
    private val handled = LinkedHashSet<String>()

    @Volatile private var started = false

    private class Connection(
        val uid: String,
        val linkId: String,
        val peer: WebLibraryPeer,
        val session: WebLibrarySession,
        val scope: CoroutineScope,
        val openedAt: Long,
    ) {
        @Volatile var lastRequest: Long = 0
    }

    /** Starts the loops once, in the main process; [foreground] is whether the app is on screen. */
    fun start(foreground: Flow<Boolean>) {
        if (started || !repository.enabled) return
        started = true
        val account = repository.authChanges().map { it?.uid }.distinctUntilChanged()
        scope.launch { listenForOffers(account) }
        scope.launch { heartbeat(account, foreground) }
        scope.launch { watchIdle() }
    }

    /** "Share library with my browser": keeps the phone available for [AVAILABLE_MS]. False when it can't serve. */
    fun shareNow(): Boolean {
        if (repository.currentUid == null || !preferences.enabled.get()) return false
        mutableState.value = mutableState.value.copy(availableUntil = System.currentTimeMillis() + AVAILABLE_MS)
        updateService()
        return true
    }

    /** The notification's Disconnect: ends the session and stops staying available. */
    fun disconnect() {
        mutableState.value = mutableState.value.copy(availableUntil = 0)
        active.get()?.let { connection -> scope.launch { end(connection) } }
        updateService()
    }

    /** Before signing out (while still allowed to): ends the session and removes this phone's document. */
    suspend fun beforeSignOut() {
        val uid = repository.currentUid ?: return
        disconnect()
        withTimeoutOrNull(3_000) { bestEffort("phone delete") { signaling.deletePhone(uid, preferences.deviceId) } }
    }

    private suspend fun listenForOffers(account: Flow<String?>) {
        combine(account, preferences.enabled.changes()) { uid, enabled -> uid to enabled }
            .distinctUntilChanged()
            .collectLatest { (uid, enabled) ->
                if (uid == null || !enabled) {
                    disconnect()
                    // Turned off while signed in: the browser stops seeing this phone right away.
                    if (uid != null) bestEffort("phone delete") { signaling.deletePhone(uid, preferences.deviceId) }
                    return@collectLatest
                }
                while (true) {
                    try {
                        signaling.offers(uid, preferences.deviceId).collect { offers ->
                            val now = System.currentTimeMillis()
                            offers.filter { WebLibrarySignaling.isFresh(it, now) && remember(it.linkId) }
                                .forEach { accept(uid, it) }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Web library offers stopped" }
                    }
                    delay(RETRY_MS)
                }
            }
    }

    /** First time this link is seen. */
    @Synchronized
    private fun remember(linkId: String): Boolean {
        if (!handled.add(linkId)) return false
        while (handled.size > 50) handled.remove(handled.first())
        return true
    }

    private suspend fun heartbeat(account: Flow<String?>, foreground: Flow<Boolean>) {
        val serving = state.map { it.connected || it.availableUntil > 0 }.distinctUntilChanged()
        combine(account, preferences.enabled.changes(), foreground, serving) { uid, enabled, visible, busy ->
            uid?.takeIf { enabled && (visible || busy) }
        }
            .distinctUntilChanged()
            .collectLatest { uid ->
                if (uid == null) return@collectLatest
                while (true) {
                    bestEffort("heartbeat") {
                        signaling.publishPhone(uid, preferences.deviceId, deviceName(), appBuild())
                    }
                    delay(WebLibrarySignaling.HEARTBEAT_MS)
                }
            }
    }

    private suspend fun watchIdle() {
        while (true) {
            delay(IDLE_CHECK_MS)
            val now = System.currentTimeMillis()
            active.get()?.let { connection ->
                val idle = if (connection.lastRequest == 0L) {
                    now - connection.openedAt > NO_HELLO_MS
                } else {
                    now - connection.lastRequest > IDLE_MS
                }
                if (idle) end(connection)
            }
            val until = state.value.availableUntil
            if (until in 1..now) {
                mutableState.value = state.value.copy(availableUntil = 0)
                updateService()
            }
        }
    }

    /** Answers [offer]; a running session (an older browser tab) is closed first. */
    private suspend fun accept(uid: String, offer: WebLibrarySignaling.Offer) {
        active.get()?.let { end(it) }
        val connectionScope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO)
        val holder = AtomicReference<Connection?>(null)
        val peer = try {
            WebLibraryPeer(
                context,
                onText = { text ->
                    holder.get()?.let { connection ->
                        connection.lastRequest = System.currentTimeMillis()
                        connection.session.receive(text)
                    }
                },
                onClosed = { holder.get()?.let { connection -> scope.launch { end(connection) } } },
            )
        } catch (error: Throwable) {
            // E.g. the native library couldn't load on this device.
            logcat(LogPriority.ERROR, error) { "Web library connection not created" }
            connectionScope.cancel()
            bestEffort("link close") { signaling.close(uid, offer.linkId) }
            return
        }
        val session =
            WebLibrarySession(backend.value, WebLibraryHostInfo(deviceName(), appBuild()), peer, connectionScope)
        val connection = Connection(uid, offer.linkId, peer, session, connectionScope, System.currentTimeMillis())
        holder.set(connection)
        active.set(connection)
        mutableState.value = state.value.copy(connected = true)
        updateService()
        try {
            val answer = withTimeout(ANSWER_TIMEOUT_MS) { peer.answer(offer.sdp) }
            signaling.answer(uid, offer.linkId, answer)
            android.util.Log.i(WebLibraryPeer.DIAG, "answer saved")
        } catch (error: CancellationException) {
            end(connection)
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Web library offer not answered" }
            android.util.Log.w(WebLibraryPeer.DIAG, "offer not answered", error)
            end(connection)
        }
    }

    private suspend fun end(connection: Connection) {
        android.util.Log.i(
            WebLibraryPeer.DIAG,
            "session end after ${System.currentTimeMillis() - connection.openedAt} ms",
        )
        if (!active.compareAndSet(connection, null)) {
            // Already ended (or replaced): only make sure its resources are gone.
            connection.session.close()
            connection.peer.close()
            connection.scope.cancel()
            return
        }
        connection.session.close()
        connection.peer.close()
        connection.scope.cancel()
        mutableState.value = state.value.copy(connected = false)
        updateService()
        if (repository.currentUid == connection.uid) {
            bestEffort("link close") { signaling.close(connection.uid, connection.linkId) }
        }
    }

    /** The foreground service runs while a session is open or the phone stays available. */
    private fun updateService() {
        val current = state.value
        if (current.connected || current.availableUntil > System.currentTimeMillis()) {
            WebLibraryService.start(context)
        } else {
            WebLibraryService.stop(context)
        }
    }

    private suspend fun bestEffort(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Web library $what failed" }
        }
    }

    private fun deviceName(): String {
        val name = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        return (
            name?.trim()?.takeIf {
                it.isNotEmpty()
            } ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Android"
            )
            .take(60)
    }

    private fun appBuild(): Int = BuildConfig.COMMIT_COUNT.toIntOrNull() ?: 0

    companion object {
        const val AVAILABLE_MS = 15 * 60_000L
        const val IDLE_MS = 15 * 60_000L
        private const val NO_HELLO_MS = 60_000L
        private const val IDLE_CHECK_MS = 15_000L
        private const val ANSWER_TIMEOUT_MS = 10_000L
        private const val RETRY_MS = 30_000L
    }
}
