package eu.kanade.tachiyomi.network

import android.net.TrafficStats
import android.os.Process
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.atomic.AtomicLong

/**
 * A data meter for "where does my data go?": bytes moved by the app's OkHttp clients (manga pages, covers,
 * extensions, sources) versus everything else the app sent and received since it started, which is mostly Mihon
 * Together's social features (Firebase). Android counts data only per app, so the rest is the app total minus OkHttp.
 * Counts the wire (compressed) bytes; cache hits never reach the network and aren't counted.
 */
object NetworkUsage {
    private val okHttpReceived = AtomicLong()
    private val okHttpSent = AtomicLong()
    private var startReceived = 0L
    private var startSent = 0L
    private var startedAt = 0L

    /** Starts the meter (the first call wins); call early, when the network stack is created. */
    @Synchronized
    fun start() {
        if (startedAt != 0L) return
        startedAt = System.currentTimeMillis()
        startReceived = uidReceived()
        startSent = uidSent()
    }

    /** Zeroes the meter, e.g. before a measured test. */
    @Synchronized
    fun reset() {
        okHttpReceived.set(0)
        okHttpSent.set(0)
        startedAt = System.currentTimeMillis()
        startReceived = uidReceived()
        startSent = uidSent()
    }

    data class Snapshot(
        val since: Long,
        /** Manga pages, covers, extensions and other source traffic (OkHttp). */
        val readingBytes: Long,
        /** Everything else: Mihon Together's social features, Firebase, push. */
        val otherBytes: Long,
    )

    @Synchronized
    fun snapshot(): Snapshot {
        val reading = okHttpReceived.get() + okHttpSent.get()
        val total = (uidReceived() - startReceived).coerceAtLeast(0) + (uidSent() - startSent).coerceAtLeast(0)
        return Snapshot(startedAt, reading, (total - reading).coerceAtLeast(0))
    }

    /** A network interceptor: sees the bytes on the wire, after the cache and before gzip is undone. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        request.body?.contentLength()?.takeIf { it > 0 }?.let(okHttpSent::addAndGet)
        val response = chain.proceed(request)
        val body = response.body
        response.newBuilder().body(CountingBody(body)).build()
    }

    private fun uidReceived() = TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it >= 0 } ?: 0L
    private fun uidSent() = TrafficStats.getUidTxBytes(Process.myUid()).takeIf { it >= 0 } ?: 0L

    private class CountingBody(private val delegate: ResponseBody) : ResponseBody() {
        private val counted: BufferedSource by lazy {
            object : ForwardingSource(delegate.source() as Source) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    if (read > 0) okHttpReceived.addAndGet(read)
                    return read
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = counted
        override fun close() = delegate.close()
    }
}
