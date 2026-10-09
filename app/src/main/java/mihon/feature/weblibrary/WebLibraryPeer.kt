package mihon.feature.weblibrary

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebRTC for the web library: data channel only (no audio or video tracks, no camera or microphone). The factory is
 * created once, on first use (never at app start), so the native library loads only when a browser connects.
 */
object WebLibraryRtc {
    private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun factory(context: Context): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        // The builder would create this audio module anyway; it never records because no track is ever added.
        val audio = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .createAudioDeviceModule()
        // Same-Wi-Fi feature: gather on the Wi-Fi/Ethernet network itself. Mobile data would cost the data plan, and a
        // VPN app (e.g. AdGuard's local VPN) gives an address the browser on the home network can't reach.
        val options = PeerConnectionFactory.Options().apply {
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
                PeerConnectionFactory.Options.ADAPTER_TYPE_VPN or PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
        }
        PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    val ICE_SERVERS: List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
}

/**
 * One browser connection: answers the offer (non-trickle: the answer carries the gathered candidates), accepts the
 * `library` data channel and sends with flow control. [onText] gets each text frame on the WebRTC thread; [onClosed]
 * runs once when the channel or the connection ends.
 */
class WebLibraryPeer(
    context: Context,
    private val onText: (String) -> Unit,
    private val onClosed: () -> Unit,
) : WebLibrarySink {
    private val gathered = CompletableDeferred<Unit>()
    private val buffered = MutableStateFlow(0L)
    private val sendLock = Mutex()
    private val closed = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    @Volatile private var channel: DataChannel? = null

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(DIAG, "ice $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.i(DIAG, "gathering $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.i(DIAG, "local candidate ${candidate.sdp}")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(DIAG, "connection $state")
            if (state == PeerConnection.PeerConnectionState.FAILED ||
                state == PeerConnection.PeerConnectionState.CLOSED
            ) {
                ended()
            }
        }
        override fun onDataChannel(dataChannel: DataChannel) {
            if (dataChannel.label() != LABEL || channel != null) {
                dataChannel.close()
                return
            }
            Log.i(DIAG, "data channel ${dataChannel.label()}")
            channel = dataChannel
            dataChannel.registerObserver(ChannelObserver(dataChannel))
        }
    }

    private val connection: PeerConnection = checkNotNull(
        WebLibraryRtc.factory(context).createPeerConnection(
            PeerConnection.RTCConfiguration(WebLibraryRtc.ICE_SERVERS).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            },
            observer,
        ),
    ) { "PeerConnection not created" }

    private inner class ChannelObserver(private val dataChannel: DataChannel) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {
            buffered.value = dataChannel.bufferedAmount()
        }

        override fun onStateChange() {
            if (dataChannel.state() == DataChannel.State.CLOSED) ended()
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            onText(String(bytes, Charsets.UTF_8))
        }
    }

    /** The complete answer SDP for [offer], after ICE gathering finished or [GATHER_TIMEOUT_MS]. */
    suspend fun answer(offer: String): String {
        Log.i(
            DIAG,
            "offer candidates: " +
                offer.lines().filter {
                    it.startsWith("a=candidate")
                }.joinToString {
                    it.split(' ').let { p -> "${p.getOrNull(7)} ${p.getOrNull(2)} ${p.getOrNull(4)?.takeLast(12)}" }
                },
        )
        connection.setRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
        val answer = connection.create()
        connection.setLocal(answer)
        withTimeoutOrNull(GATHER_TIMEOUT_MS) { gathered.await() }
        val sdp = connection.localDescription?.description ?: answer.description
        Log.i(DIAG, "answer ${sdp.length} chars, candidates: " + sdp.lines().count { it.startsWith("a=candidate") })
        return sdp
    }

    override suspend fun sendText(text: String) = send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))

    override suspend fun sendBinary(frame: ByteArray) = send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))

    /** Pauses while more than 1 MB waits in the channel, until it drops below 256 KB. Sends keep their order. */
    private suspend fun send(buffer: DataChannel.Buffer) = sendLock.withLock {
        val dataChannel = channel?.takeIf { !closed.get() } ?: throw IOException("Web library channel closed")
        buffered.value = dataChannel.bufferedAmount()
        if (buffered.value > HIGH_WATER) {
            buffered.first { it < LOW_WATER || closed.get() }
            buffered.value = dataChannel.bufferedAmount()
        }
        if (closed.get() || !dataChannel.send(buffer)) throw IOException("Web library channel closed")
    }

    private fun ended() {
        if (!closed.compareAndSet(false, true)) return
        buffered.value = -1 // wakes paused senders
        onClosed()
    }

    /** Closes the channel and the connection. Never call it from a WebRTC callback (the native close waits on it). */
    fun close() {
        ended()
        // Runs once: a second dispose of the native objects (two session ends racing) crashes the process.
        if (!disposed.compareAndSet(false, true)) return
        try {
            channel?.let {
                it.unregisterObserver()
                it.close()
                it.dispose()
            }
            channel = null
            connection.close()
            connection.dispose()
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Web library connection not closed cleanly" }
        }
    }

    private suspend fun PeerConnection.setRemote(description: SessionDescription) =
        suspendCancellableCoroutine { continuation ->
            setRemoteDescription(SetObserver(continuation::resume, continuation::resumeWithException), description)
        }

    private suspend fun PeerConnection.setLocal(description: SessionDescription) =
        suspendCancellableCoroutine { continuation ->
            setLocalDescription(SetObserver(continuation::resume, continuation::resumeWithException), description)
        }

    private suspend fun PeerConnection.create(): SessionDescription = suspendCancellableCoroutine { continuation ->
        createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
                override fun onCreateFailure(error: String?) =
                    continuation.resumeWithException(IOException("Answer not created: $error"))
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            MediaConstraints(),
        )
    }

    private class SetObserver(
        private val success: (Unit) -> Unit,
        private val failure: (Throwable) -> Unit,
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetSuccess() = success(Unit)
        override fun onSetFailure(error: String?) = failure(IOException("Description not set: $error"))
    }

    companion object {
        const val LABEL = "library"
        const val GATHER_TIMEOUT_MS = 3_000L
        const val DIAG = "WebLibrary"
        const val HIGH_WATER = 1_048_576L
        const val LOW_WATER = 262_144L
    }
}
