package mihon.feature.translate

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/** A Mihon Together Translator found on this Wi-Fi: its PC name (TXT `name`) and where it listens. */
data class FoundPc(val serviceName: String, val name: String, val address: PcAddress)

/**
 * Finds Mihon Together Translators on the home network with DNS-SD (service `_mihontogether._tcp`). The system's NSD
 * service does the multicast, so no permission or multicast lock is needed. Discovery runs while the flow is
 * collected; the emulator's NAT hides the PC, so manual entry stays the fallback.
 */
class PcDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    /** Every PC found so far (resolved to an address), updated as PCs appear and disappear. */
    fun discover(): Flow<List<FoundPc>> = callbackFlow {
        val manager = nsd ?: run {
            send(emptyList())
            close()
            return@callbackFlow
        }
        val found = linkedMapOf<String, FoundPc>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        fun publish() = trySend(synchronized(found) { found.values.toList() })

        // Before Android 14 NSD resolves one service at a time, so resolve them one after another.
        launch {
            for (info in toResolve) {
                val resolved = resolve(manager, info) ?: continue
                synchronized(found) { found[resolved.serviceName] = resolved }
                publish()
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName) }
                publish()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logcat(LogPriority.WARN) { "PC discovery failed to start: $errorCode" }
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        send(emptyList())
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            toResolve.close()
            try {
                manager.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // Discovery never started (it failed): nothing to stop.
            }
        }
    }

    /** The paired PC's current address, found by its name within [timeoutMs]; null when it doesn't show up. */
    suspend fun find(name: String, timeoutMs: Long = FIND_TIMEOUT_MS): PcAddress? = withTimeoutOrNull(timeoutMs) {
        discover().mapNotNull { list -> list.firstOrNull { it.name.equals(name, ignoreCase = true) } }.first().address
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): FoundPc? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    logcat(LogPriority.DEBUG) { "PC discovery: resolve failed ($errorCode)" }
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(toFound(info))
                }
            }
            try {
                manager.resolveService(info, listener)
            } catch (error: IllegalArgumentException) {
                logcat(LogPriority.DEBUG, error) { "PC discovery: resolve refused" }
                continuation.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    private fun toFound(info: NsdServiceInfo): FoundPc? {
        val hosts: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        // IPv4 first: an IPv6 link-local address needs a scope that HTTP URLs can't carry.
        val host = (hosts.firstOrNull { it is Inet4Address } ?: hosts.firstOrNull())?.hostAddress ?: return null
        val address =
            PcAddress(host.substringBefore('%'), info.port.takeIf { it in 1..65535 } ?: PcAddress.DEFAULT_PORT)
        val txtName = info.attributes["name"]?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
        return FoundPc(info.serviceName, txtName ?: info.serviceName, address)
    }

    companion object {
        const val SERVICE_TYPE = "_mihontogether._tcp"
        private const val FIND_TIMEOUT_MS = 5_000L
    }
}
