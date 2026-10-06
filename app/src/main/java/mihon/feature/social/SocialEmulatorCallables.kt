package mihon.feature.social

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Direct emulator transport avoids Functions' legacy IID/Installations cloud token retrieval. */
@Inject
@SingleIn(AppScope::class)
class SocialEmulatorCallables {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    internal fun start(name: String, data: Map<String, Any?>, token: String): Task<Map<String, Any?>> {
        check(BuildConfig.SOCIAL_ENVIRONMENT == "emulator")
        require(BuildConfig.SOCIAL_PROJECT_ID.matches(Regex("demo-[a-z0-9-]{1,58}")))
        require(BuildConfig.SOCIAL_FUNCTIONS_REGION.matches(Regex("[a-z0-9-]{1,63}")))
        require(BuildConfig.SOCIAL_EMULATOR_HOST.length in 1..253)
        require(BuildConfig.SOCIAL_FUNCTIONS_EMULATOR_PORT in 1..65535)
        require(name in callableNames)
        require(token.isNotBlank() && token.none { it.isWhitespace() || it.isISOControl() })
        val body = SocialCallableProtocol.encodeRequest(data)
        require(body.toByteArray(Charsets.UTF_8).size <= MAXIMUM_BODY_BYTES)
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(BuildConfig.SOCIAL_EMULATOR_HOST)
            .port(BuildConfig.SOCIAL_FUNCTIONS_EMULATOR_PORT)
            .addPathSegment(BuildConfig.SOCIAL_PROJECT_ID)
            .addPathSegment(BuildConfig.SOCIAL_FUNCTIONS_REGION)
            .addPathSegment(name)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val completion = TaskCompletionSource<Map<String, Any?>>()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                completion.trySetException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val source = response.body.source()
                        if (source.request(MAXIMUM_BODY_BYTES.toLong() + 1)) {
                            throw SocialCallableFailure("RESOURCE_EXHAUSTED")
                        }
                        completion.trySetResult(SocialCallableProtocol.decodeResponse(response.code, source.readUtf8()))
                    } catch (error: Exception) {
                        completion.trySetException(error)
                    }
                }
            }
        })
        // Caller cancellation stays prompt; the completion-owned gate prevents overlapping requests
        // until this HTTP operation actually completes. The finite call timeout bounds abandoned work.
        return completion.task
    }

    private companion object {
        const val MAXIMUM_BODY_BYTES = 256_000
        val callableNames = SOCIAL_CALLABLE_NAMES
    }
}

/** Must match CALLABLES + ACCOUNT_CALLABLES in backend/src/domain.ts (checked by SocialCallableNamesTest). */
internal val SOCIAL_CALLABLE_NAMES = setOf(
    "socialGetSelf", "socialUpdateProfile", "socialLookupHandle", "socialFriendAction", "socialGetFriend",
    "socialUpdateSharing", "socialPublishEntries", "socialPublishActivity", "socialRoomAction",
    "socialPublishFeed", "socialRoomMessage", "socialUnhideTitle", "socialRecordChapter", "socialUpdateProfileStyle",
    "socialRegisterDevice", "socialMessage", "socialJoinRequest", "socialGroupChat", "socialReadWish",
    "socialReview", "socialNudge",
    // ACCOUNT_CALLABLES
    "socialDeleteAccount",
)
