package eu.kanade.tachiyomi.network

import android.os.Build
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import java.security.KeyStore
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Mihon Together: pins source traffic to the TLS provider stock Mihon uses.
 *
 * Firebase Firestore calls Google Play's `ProviderInstaller.installIfNeeded`, which puts `GmsCore_OpenSSL` first in
 * the process-wide provider list. OkHttp then builds every TLS connection through it, so source requests present a
 * different TLS fingerprint than stock Mihon, and some Cloudflare-protected image hosts (Comix) answer 403
 * "you have been blocked". Choosing the provider by name keeps the fingerprint identical to Mihon no matter which
 * provider is installed first or when.
 */
internal object PlatformTls {
    /** The platform provider on Android 10+, and the bundled Conscrypt Mihon installs on older versions. */
    private fun providerName() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "AndroidOpenSSL" else "Conscrypt"

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        try {
            val provider = Security.getProvider(providerName()) ?: return builder
            val trustFactory = try {
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), provider)
            } catch (_: Exception) {
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            }
            trustFactory.init(null as KeyStore?)
            val trustManager = trustFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return builder
            val context = SSLContext.getInstance("TLS", provider)
            context.init(null, arrayOf(trustManager), null)
            return builder.sslSocketFactory(context.socketFactory, trustManager)
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Platform TLS provider unavailable; using the default" }
            return builder
        }
    }
}
