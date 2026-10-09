package mihon.feature.weblibrary

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import mihon.feature.social.SocialFirebase
import java.util.Date

/**
 * Signaling for the web library (docs/WEB_LIBRARY_PROTOCOL.md section 1): this phone's document in
 * `libraryHosts/{uid}/phones/{deviceId}`, and the browser's offers in `libraryLinks/{uid}/links`. Owner-only by the
 * rules; nothing here carries library data.
 */
@Inject
class WebLibrarySignaling(private val firebase: SocialFirebase) {

    data class Offer(val linkId: String, val sdp: String, val createdAtMs: Long?)

    /** Writes the whole phone document; `expiresAt` is [PHONE_TTL_MS] ahead (the rules allow at most 10 minutes). */
    suspend fun publishPhone(uid: String, deviceId: String, name: String, appBuild: Int) {
        firebase.firestore.document(phonePath(uid, deviceId)).set(
            mapOf(
                "name" to name,
                "appBuild" to appBuild,
                "protocol" to WebLibraryWire.PROTOCOL,
                "updatedAt" to FieldValue.serverTimestamp(),
                "expiresAt" to Timestamp(Date(System.currentTimeMillis() + PHONE_TTL_MS)),
            ),
        ).await()
    }

    suspend fun deletePhone(uid: String, deviceId: String) {
        firebase.firestore.document(phonePath(uid, deviceId)).delete().await()
    }

    /** Offers for this phone that wait for an answer. */
    fun offers(uid: String, deviceId: String): Flow<List<Offer>> = callbackFlow {
        val registration = firebase.firestore.collection("libraryLinks/$uid/links")
            .whereEqualTo("deviceId", deviceId)
            .whereEqualTo("state", "offered")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener
                trySend(
                    snapshot.documents.mapNotNull { document ->
                        val sdp = document.getString("offer") ?: return@mapNotNull null
                        Offer(document.id, sdp, document.getTimestamp("createdAt")?.toDate()?.time)
                    },
                )
            }
        awaitClose { registration.remove() }
    }

    suspend fun answer(uid: String, linkId: String, sdp: String) {
        firebase.firestore.document(linkPath(uid, linkId))
            .update(mapOf("answer" to sdp, "state" to "answered"))
            .await()
    }

    suspend fun close(uid: String, linkId: String) {
        firebase.firestore.document(linkPath(uid, linkId)).update("state", "closed").await()
    }

    companion object {
        const val PHONE_TTL_MS = 3 * 60_000L
        const val HEARTBEAT_MS = 60_000L

        /** Offers older than this are ignored (the browser gave up on them). */
        const val OFFER_MAX_AGE_MS = 2 * 60_000L

        /** A deviceId as the rules accept it. */
        val DEVICE_ID = Regex("^[A-Za-z0-9_-]{16,64}$")

        fun phonePath(uid: String, deviceId: String) = "libraryHosts/$uid/phones/$deviceId"
        fun linkPath(uid: String, linkId: String) = "libraryLinks/$uid/links/$linkId"

        /** Fresh enough to answer: created less than 2 minutes ago (by this phone's clock); unknown counts as fresh. */
        fun isFresh(offer: Offer, now: Long): Boolean =
            offer.createdAtMs == null || now - offer.createdAtMs <= OFFER_MAX_AGE_MS
    }
}
