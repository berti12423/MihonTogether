package mihon.feature.social

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig

@Inject
@SingleIn(AppScope::class)
class SocialFirebase(private val context: Context) {
    val enabled: Boolean = BuildConfig.SOCIAL_INCLUDED &&
        BuildConfig.SOCIAL_ENVIRONMENT in setOf("emulator", "production") &&
        BuildConfig.SOCIAL_PROJECT_ID.isNotBlank() && BuildConfig.SOCIAL_PROJECT_ID != "mihonapp"

    private val services by lazy {
        check(enabled) { "Social Firebase is not configured" }
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.SOCIAL_PROJECT_ID)
            .setApiKey(BuildConfig.SOCIAL_API_KEY)
            .setApplicationId(BuildConfig.SOCIAL_APP_ID)
            .setDatabaseUrl(BuildConfig.SOCIAL_DATABASE_URL)
            .setStorageBucket(BuildConfig.SOCIAL_STORAGE_BUCKET)
            // The FCM sender is the project number, the second part of the app ID ("1:<number>:android:<hash>").
            .apply { BuildConfig.SOCIAL_APP_ID.split(':').getOrNull(1)?.let(::setGcmSenderId) }
            .build()
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(context.applicationContext, options, APP_NAME)
        check(app.options == options) { "Social Firebase configuration mismatch" }
        val auth = FirebaseAuth.getInstance(app)
        val firestore = FirebaseFirestore.getInstance(app)
        val database = FirebaseDatabase.getInstance(app)
        val storage = FirebaseStorage.getInstance(app)

        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        database.setPersistenceEnabled(false)
        if (BuildConfig.SOCIAL_ENVIRONMENT == "emulator") {
            check(BuildConfig.SOCIAL_PROJECT_ID.startsWith("demo-"))
            val host = BuildConfig.SOCIAL_EMULATOR_HOST
            auth.useEmulator(host, BuildConfig.SOCIAL_AUTH_EMULATOR_PORT)
            firestore.useEmulator(host, BuildConfig.SOCIAL_FIRESTORE_EMULATOR_PORT)
            database.useEmulator(host, BuildConfig.SOCIAL_DATABASE_EMULATOR_PORT)
            storage.useEmulator(host, BuildConfig.SOCIAL_STORAGE_EMULATOR_PORT)
        }
        Services(app, auth, firestore, database, storage)
    }

    private val productionFunctions by lazy {
        check(BuildConfig.SOCIAL_ENVIRONMENT == "production") { "Emulator callables use the local REST transport" }
        FirebaseFunctions.getInstance(services.app, BuildConfig.SOCIAL_FUNCTIONS_REGION)
    }

    val auth: FirebaseAuth get() = services.auth
    val firestore: FirebaseFirestore get() = services.firestore
    val database: FirebaseDatabase get() = services.database
    val storage: FirebaseStorage get() = services.storage
    val functions: FirebaseFunctions get() = productionFunctions

    /** FCM for the social project; production only (see SocialPushRegistration). */
    val messaging: FirebaseMessaging by lazy {
        check(BuildConfig.SOCIAL_ENVIRONMENT == "production") { "Pushes need the production project" }
        services.app.get(FirebaseMessaging::class.java)
    }

    private data class Services(
        val app: FirebaseApp,
        val auth: FirebaseAuth,
        val firestore: FirebaseFirestore,
        val database: FirebaseDatabase,
        val storage: FirebaseStorage,
    )

    private companion object {
        const val APP_NAME = "mihon-together-social"
    }
}
