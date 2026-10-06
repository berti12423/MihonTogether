package mihon.feature.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.util.concurrent.TimeUnit

/** A test build on the Mihon Together website (`/files/apk.json`). */
data class TogetherRelease(
    /** The build number: the commit count, as at the end of versionName and in BuildConfig.COMMIT_COUNT. */
    val build: Int,
    val version: String,
    val notes: String,
    /** The arm64 APK, which Mihon's update screen downloads and installs. */
    val apkUrl: String,
    /** The download page, for phones that need another build. */
    val pageUrl: String,
)

/**
 * Update checks for the friends test builds, which aren't on GitHub releases (Mihon's own updater is off): the
 * website publishes the current APK with its build number, the app compares it with its own.
 */
object TogetherUpdates {
    private val json = Json { ignoreUnknownKeys = true }

    /** A release asset on GitHub: https://github.com/{owner}/{repo}/releases/download/{tag}/{name}.apk */
    private val GITHUB_RELEASE_APK =
        Regex(
            "https://github\\.com/[A-Za-z0-9-]+/[A-Za-z0-9._-]+/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk",
        )

    /** The website, when this build talks to the real project and the stock updater is off. */
    val baseUrl: String?
        get() = BuildConfig.SOCIAL_WEB_URL.trimEnd('/')
            .takeIf { !updaterEnabled && BuildConfig.SOCIAL_ENVIRONMENT == "production" && it.startsWith("https://") }

    val currentBuild: Int
        get() = BuildConfig.COMMIT_COUNT.toIntOrNull() ?: 0

    fun parse(body: String, baseUrl: String): TogetherRelease? {
        val data = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val build = data["build"]?.jsonPrimitive?.intOrNull ?: return null
        val file = data["file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+\\.apk")) }
            ?: return null
        // Newer manifests point at a GitHub release asset (cheaper than Hosting traffic); only that host is trusted.
        val external = data["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(GITHUB_RELEASE_APK) }
        return TogetherRelease(
            build = build,
            version = data["version"]?.jsonPrimitive?.contentOrNull.orEmpty().take(40),
            notes = data["notes"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2000),
            apkUrl = external ?: "$baseUrl/files/$file",
            pageUrl = "$baseUrl/download",
        )
    }

    /** The published build when it's newer than this one, else null. */
    suspend fun check(network: NetworkHelper): TogetherRelease? {
        val base = baseUrl ?: return null
        return try {
            val body = network.client.newCall(GET("$base/files/apk.json")).awaitSuccess().use { it.body.string() }
            parse(body, base)?.takeIf { it.build > currentBuild }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Update check failed" }
            null
        }
    }

    /**
     * False in the online build, which leaves out the install permission (Play Protect blocks it, see
     * src/preview/AndroidManifest.xml): updates download through the browser, extensions install privately.
     */
    fun canInstallApks(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES)
    } catch (_: Exception) {
        true
    }

    /** The website's APK is the arm64 build; other phones get the download page instead. */
    fun installable(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    /** Remembers which build was already announced, so each one notifies (and opens the screen) once. */
    fun announced(store: PreferenceStore): Preference<Int> =
        store.getInt(Preference.appStateKey("together_update_announced"), 0)

    const val ACTION_SHOW_UPDATE = "mihon.together.SHOW_UPDATE"
}

/** Checks the website twice a day and posts a notification for a new build (tap: the in-app update screen). */
class TogetherUpdateJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        val release = TogetherUpdates.check(graph.networkHelper) ?: return Result.success()
        val announced = TogetherUpdates.announced(graph.preferenceStore)
        if (announced.get() >= release.build) return Result.success()
        notify(applicationContext, release)
        announced.set(release.build)
        return Result.success()
    }

    companion object {
        private const val TAG = "TogetherUpdateCheck"

        fun setupTask(context: Context) {
            if (TogetherUpdates.baseUrl == null) return
            val request = PeriodicWorkRequestBuilder<TogetherUpdateJob>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .addTag(TAG)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun notify(context: Context, release: TogetherRelease) {
            val open = PendingIntent.getActivity(
                context,
                Notifications.ID_TOGETHER_UPDATE,
                Intent(context, MainActivity::class.java)
                    .setAction(TogetherUpdates.ACTION_SHOW_UPDATE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.notify(Notifications.ID_TOGETHER_UPDATE, Notifications.CHANNEL_TOGETHER_UPDATE) {
                setSmallIcon(R.drawable.ic_mihon)
                setContentTitle(context.stringResource(MR.strings.together_update_title))
                setContentText(context.stringResource(MR.strings.together_update_text))
                setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        release.notes.ifBlank { context.stringResource(MR.strings.together_update_text) },
                    ),
                )
                setContentIntent(open)
                setAutoCancel(true)
            }
        }
    }
}
