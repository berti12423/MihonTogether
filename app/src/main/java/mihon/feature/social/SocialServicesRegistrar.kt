package mihon.feature.social

import androidx.annotation.Keep
import com.google.firebase.components.Component
import com.google.firebase.components.ComponentRegistrar
import com.google.firebase.functions.FirebaseFunctionsKtxRegistrar
import com.google.firebase.functions.FunctionsRegistrar
import com.google.firebase.iid.Registrar
import eu.kanade.tachiyomi.BuildConfig

/**
 * Delegate pinned Functions 22.1.1 and IID 21.1.0 only for explicitly configured production social.
 * Revalidate these delegates when the Firebase BoM changes.
 * API: https://firebase.google.com/docs/reference/android/com/google/firebase/components/ComponentRegistrar
 * Functions source: https://github.com/firebase/firebase-android-sdk/tree/main/firebase-functions
 * IID artifact: https://maven.google.com/web/index.html#com.google.firebase:firebase-iid:21.1.0
 */
@Keep
class SocialServicesRegistrar : ComponentRegistrar {
    override fun getComponents(): List<Component<*>> = if (BuildConfig.SOCIAL_ENVIRONMENT == "production") {
        FunctionsRegistrar().components + FirebaseFunctionsKtxRegistrar().components + Registrar().components
    } else {
        emptyList()
    }
}
