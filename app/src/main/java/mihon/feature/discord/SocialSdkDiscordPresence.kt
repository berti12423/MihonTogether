package mihon.feature.discord

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Rich Presence through the official Discord Social SDK: RPC to the signed-in Discord app on this phone (no login, no
 * token). The SDK is only in builds that found it in third_party/discord-social-sdk/ (BuildConfig.DISCORD_SDK); its
 * Java entry point is reached by reflection so builds without it still compile. Every native call runs on one thread.
 */
object SocialSdkDiscordPresence : DiscordPresence {
    private val scope = CoroutineScope(
        SupervisorJob() +
            Executors.newSingleThreadExecutor { Thread(it, "discord-presence").apply { isDaemon = true } }
                .asCoroutineDispatcher(),
    )
    private var initialized = false
    private var pump: Job? = null
    private var pumpUntil = 0L
    private var pendingClear: Job? = null
    private var showing = false

    // Presence thread only. The last status shown, resent after a recovery; and when recoveries started recently.
    private var lastInfo: PresenceInfo? = null
    private val recoveryAttempts = ArrayDeque<Long>()
    private var needsReset = false

    // Remembers (on disk) that a status may still be showing on Discord, so a force-stop, crash or freeze that skipped
    // the clear is undone on the next start (clearIfLingering). Discord keeps the last status until told otherwise.
    @Volatile private var appContext: Context? = null

    private fun markShowing(shown: Boolean) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_SHOWN, shown)?.apply()
    }

    /**
     * Clears a status left on Discord by an earlier run (force-stop, crash, or Android freezing the app before the
     * clear went out), and the one shown now when [force] (turning Discord status off). Loads the SDK only if needed.
     */
    fun clearIfLingering(activity: Activity, force: Boolean = false) {
        if (!available) return
        appContext = activity.applicationContext
        val lingering = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)
        if (!lingering && !force) return
        attach(activity)
        scope.launch { clearNow(force = true) }
    }

    // Weak: the SDK keeps its own reference to the Activity; ours must never keep a destroyed one alive.
    @Volatile private var latestActivity: WeakReference<Activity>? = null

    private val _status = MutableStateFlow<DiscordStatus>(DiscordStatus.Idle)

    /** The latest connection result, for Settings → Discord. */
    val status: StateFlow<DiscordStatus> = _status.asStateFlow()

    override val available: Boolean
        get() = BuildConfig.DISCORD_SDK && BuildConfig.DISCORD_APP_ID.isNotEmpty()

    /**
     * Hands the SDK the current Activity (every call: the one it holds may be destroyed since, which makes its RPC
     * link to the Discord app fail with ErrorType 9) and loads it the first time. A recovery that had to wait for a
     * live Activity runs now.
     */
    fun attach(activity: Activity) {
        if (!available) return
        latestActivity = WeakReference(activity)
        appContext = activity.applicationContext
        scope.launch {
            try {
                val init = Class.forName("com.discord.socialsdk.DiscordSocialSdkInit") // loads libdiscord_partner_sdk
                init.getMethod("setEngineActivity", Activity::class.java).invoke(null, activity)
                if (initialized) {
                    if (needsReset) {
                        recoveryAttempts.clear()
                        rebuildClient()
                        val info = lastInfo
                        if (showing && info != null) sendNow(info)
                    }
                    return@launch
                }
                System.loadLibrary("mihon_discord")
                nativeInit(BuildConfig.DISCORD_APP_ID.toLong())
                initialized = true
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "Discord SDK unavailable" }
                if (!initialized) _status.value = DiscordStatus.Unavailable(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** The newest Activity handed over by [attach], while it's still alive. */
    private fun liveActivity(): Activity? = latestActivity?.get()?.takeUnless { it.isDestroyed || it.isFinishing }

    /** Presence thread: gives the SDK the newest live Activity again (after a rebuild or a long background). */
    private fun reapplyActivity() {
        val activity = liveActivity() ?: return
        try {
            Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
                .getMethod("setEngineActivity", Activity::class.java)
                .invoke(null, activity)
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "Discord SDK activity refresh failed" }
        }
    }

    /** Presence thread: throws the client away and builds a fresh one. */
    private fun rebuildClient() {
        try {
            nativeReset()
            reapplyActivity()
            nativeInit(BuildConfig.DISCORD_APP_ID.toLong())
            needsReset = false
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "Discord SDK rebuild failed" }
        }
    }

    /** Settings → Discord "Send test status": loads the SDK if needed, shows [info] for a minute, then clears it. */
    fun sendTest(activity: Activity, info: PresenceInfo) {
        if (!available) return
        discordAppProblem(activity)?.let {
            _status.value = it
            return
        }
        attach(activity)
        update(info)
        clearAfter(TEST_DURATION_MS)
    }

    /** Why the Discord app on this phone can't take a status, or null if it looks fine. */
    private fun discordAppProblem(context: Context): DiscordStatus? {
        val packages = context.packageManager
        val installed = try {
            packages.getPackageInfo(DISCORD_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return DiscordStatus.NoDiscordApp
        val rpc = Intent(RPC_SERVICE_ACTION).setPackage(DISCORD_PACKAGE)
        return DiscordStatus.DiscordTooOld.takeIf { packages.queryIntentServices(rpc, 0).isEmpty() }
    }

    override fun update(info: PresenceInfo) {
        scope.launch {
            if (!initialized) return@launch
            pendingClear?.cancel()
            pendingClear = null
            lastInfo = info
            if (needsReset) {
                recoveryAttempts.clear()
                rebuildClient()
            }
            sendNow(info)
        }
    }

    /** Presence thread. */
    private fun sendNow(info: PresenceInfo) {
        // The title is the activity name (Discord's bold line and "Watching <title>"); otherwise the SDK shows
        // its default "Game". The chapter goes underneath, then level and room. The cover is the picture with
        // the app logo as a small badge (the credit on hover); without a cover the logo is the picture.
        val cover = info.coverUrl
        nativeUpdate(
            name = info.details,
            details = info.state,
            state = info.extra,
            largeImage = cover ?: info.logoUrl,
            largeText = if (cover != null) info.details else CREDIT,
            smallImage = info.logoUrl.takeIf { cover != null },
            smallText = CREDIT.takeIf { cover != null && info.logoUrl != null },
            startedAt = info.startedAt,
            partyId = info.partyId,
            partySize = info.partySize ?: 0,
            partyMax = info.partyMax,
            buttonLabel = BUTTON_LABEL.takeIf { info.buttonUrl != null },
            buttonUrl = info.buttonUrl,
            watching = info.watching,
        )
        showing = true
        markShowing(true)
        if (_status.value != DiscordStatus.Reconnecting) _status.value = DiscordStatus.Waiting
        keepPumping()
    }

    override fun clear() {
        scope.launch { clearNow() }
    }

    override fun clearAfter(delayMs: Long) {
        scope.launch {
            if (!initialized || !showing) return@launch
            pendingClear?.cancel()
            pendingClear = scope.launch {
                delay(delayMs)
                clearNow()
            }
        }
    }

    /**
     * Runs on the presence thread. Pumps the SDK right away for a moment, so the clear reaches Discord even if Android
     * freezes the app just after the reader closes. [force]: clear even if this run never showed anything.
     */
    private suspend fun clearNow(force: Boolean = false) {
        pendingClear?.cancel()
        pendingClear = null
        if (!initialized || (!showing && !force)) return
        nativeClear()
        lastInfo = null
        showing = false
        repeat(CLEAR_PUMPS) {
            nativeRunCallbacks()?.let { result -> handleResult(result) }
            delay(CLEAR_PUMP_GAP_MS)
        }
        markShowing(false)
        _status.value = DiscordStatus.Cleared
        keepPumping()
    }

    /**
     * Callbacks (and the RPC exchange) only run while RunCallbacks is pumped: quickly right after a change, then
     * slowly for as long as a status is shown, so the connection to the Discord app stays alive.
     */
    private fun keepPumping() {
        pumpUntil = System.currentTimeMillis() + PUMP_AFTER_CHANGE_MS
        if (pump?.isActive == true) return
        pump = scope.launch {
            while (isActive) {
                val fast = System.currentTimeMillis() < pumpUntil
                if (!fast && !showing) break
                nativeRunCallbacks()?.let { result -> handleResult(result) }
                delay(if (fast) PUMP_INTERVAL_MS else SLOW_PUMP_INTERVAL_MS)
            }
        }
    }

    /** Presence thread: shows an update result, and rebuilds the client when the RPC link to Discord broke. */
    private suspend fun handleResult(result: String) {
        if (result == "ok") {
            recoveryAttempts.clear()
            _status.value = if (showing) DiscordStatus.Shown else DiscordStatus.Cleared
            return
        }
        if (DiscordRecovery.isRpcFailure(result)) {
            // Outside the reader there may be no live Activity: a rebuild couldn't reconnect, so it doesn't use up an
            // attempt; the next attach (the reader opening) rebuilds.
            if (liveActivity() == null) {
                needsReset = true
                _status.value = DiscordStatus.Reconnecting
                return
            }
            val now = System.currentTimeMillis()
            when (val decision = DiscordRecovery.decide(now, recoveryAttempts.toList())) {
                is DiscordRecovery.Decision.Retry -> {
                    recoveryAttempts.addLast(now)
                    while (recoveryAttempts.size > DiscordRecovery.MAX_ATTEMPTS) recoveryAttempts.removeFirst()
                    _status.value = DiscordStatus.Reconnecting
                    delay(decision.waitMs)
                    rebuildClient()
                    // Nothing to resend when nothing is shown; the new client simply waits for the next update.
                    val info = lastInfo
                    if (showing && info != null) sendNow(info) else _status.value = DiscordStatus.Cleared
                    return
                }
                DiscordRecovery.Decision.GiveUp -> needsReset = true // rebuilt on the next update
            }
        }
        _status.value = DiscordStatus.Failed(
            result.removePrefix(DiscordRecovery.RPC_PREFIX).removePrefix("error:").ifBlank { "unknown error" },
        )
    }

    private const val BUTTON_LABEL = "Get Mihon Together"

    /** Shown when hovering the logo. */
    private const val CREDIT = "Mihon Together • made by Berti"
    private const val PUMP_INTERVAL_MS = 250L
    private const val DISCORD_PACKAGE = "com.discord"

    /** The service the Discord app exposes for Rich Presence over RPC (declared in the SDK's manifest queries). */
    private const val RPC_SERVICE_ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"
    private const val TEST_DURATION_MS = 60_000L
    private const val PREFS = "mihon_discord"
    private const val KEY_SHOWN = "status_maybe_showing"
    private const val CLEAR_PUMPS = 10
    private const val CLEAR_PUMP_GAP_MS = 50L
    private const val SLOW_PUMP_INTERVAL_MS = 2_000L
    private const val PUMP_AFTER_CHANGE_MS = 20_000L

    @JvmStatic private external fun nativeInit(applicationId: Long)

    @JvmStatic private external fun nativeUpdate(
        name: String,
        details: String?,
        state: String?,
        largeImage: String?,
        largeText: String?,
        smallImage: String?,
        smallText: String?,
        startedAt: Long,
        partyId: String?,
        partySize: Int,
        partyMax: Int,
        buttonLabel: String?,
        buttonUrl: String?,
        watching: Boolean,
    )

    @JvmStatic private external fun nativeClear()

    /** Drops the SDK client and pending result; [nativeInit] builds a fresh one. */
    @JvmStatic private external fun nativeReset()

    /** Pumps the SDK; returns the newest update result ("ok" or "error:…") or null. */
    @JvmStatic private external fun nativeRunCallbacks(): String?
}
