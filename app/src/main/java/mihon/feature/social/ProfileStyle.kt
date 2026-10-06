package mihon.feature.social

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest

/** Loads friends-only profile media bytes (see SocialRepository.loadProfileMedia); null = not available. */
typealias ProfileMediaLoader = suspend (uid: String, kind: String, version: Long) -> ByteArray?

object ProfileStyle {
    const val AVATAR = "avatar"
    const val BANNER = "banner"
    const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
    const val MAX_BANNER_BYTES = 5 * 1024 * 1024
    val CONTENT_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    /** Must have the same length as PROFILE_ACCENTS in the backend. */
    val ACCENTS = listOf(
        Color(0xFF5C6BC0),
        Color(0xFFEF5350),
        Color(0xFFFFA726),
        Color(0xFF66BB6A),
        Color(0xFF26C6DA),
        Color(0xFFAB47BC),
        Color(0xFFEC407A),
        Color(0xFF8D6E63),
    )

    fun accent(index: Int): Color = ACCENTS[index.coerceIn(0, ACCENTS.lastIndex)]

    fun maxBytes(kind: String) = if (kind == AVATAR) MAX_AVATAR_BYTES else MAX_BANNER_BYTES

    @Volatile private var loader: ImageLoader? = null

    /** A small image loader for profile media that animates GIFs (and animated WebP on API 28+). */
    fun imageLoader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
            .also { loader = it }
    }
}

@Composable
private fun rememberMedia(uid: String, kind: String, media: ProfileMedia?, load: ProfileMediaLoader): ByteArray? {
    val bytes by produceState<ByteArray?>(null, uid, kind, media?.version) {
        value = media?.let { load(uid, kind, it.version) }
    }
    return bytes
}

@Composable
private fun MediaImage(bytes: ByteArray, key: String, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(key) {
        ImageRequest.Builder(context).data(bytes).memoryCacheKey(key).build()
    }
    AsyncImage(
        model = request,
        imageLoader = ProfileStyle.imageLoader(context),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** Round avatar; falls back to the first letter on the accent colour. */
@Composable
fun ProfileAvatar(profile: SocialProfile, load: ProfileMediaLoader, size: Dp, modifier: Modifier = Modifier) {
    val bytes = rememberMedia(profile.uid, ProfileStyle.AVATAR, profile.avatar, load)
    Box(
        modifier.size(size).clip(CircleShape).background(ProfileStyle.accent(profile.accent)),
        contentAlignment = Alignment.Center,
    ) {
        if (bytes != null) {
            MediaImage(bytes, "${profile.uid}/avatar/${profile.avatar?.version}", Modifier.fillMaxSize())
        } else {
            Text(
                profile.displayName.trim().take(1).uppercase(),
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** 3:1 banner; the accent colour when there is no image. */
@Composable
fun ProfileBanner(profile: SocialProfile, load: ProfileMediaLoader, modifier: Modifier = Modifier) {
    val bytes = rememberMedia(profile.uid, ProfileStyle.BANNER, profile.banner, load)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(3f)
            .clip(RoundedCornerShape(12.dp))
            .background(ProfileStyle.accent(profile.accent).copy(alpha = 0.85f)),
    ) {
        if (bytes != null) MediaImage(bytes, "${profile.uid}/banner/${profile.banner?.version}", Modifier.fillMaxSize())
    }
}

/** The banner (or the accent colour without one) filling [modifier]'s bounds, e.g. the top of a ranking card. */
@Composable
fun ProfileBannerFill(profile: SocialProfile, load: ProfileMediaLoader, modifier: Modifier = Modifier) {
    val bytes = rememberMedia(profile.uid, ProfileStyle.BANNER, profile.banner, load)
    Box(modifier.background(ProfileStyle.accent(profile.accent).copy(alpha = 0.85f))) {
        if (bytes != null) MediaImage(bytes, "${profile.uid}/banner/${profile.banner?.version}", Modifier.fillMaxSize())
    }
}

/** Banner with the avatar overlapping its bottom edge, plus name, handle and bio. */
@Composable
fun ProfileHeader(profile: SocialProfile, load: ProfileMediaLoader, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box {
            ProfileBanner(profile, load)
            ProfileAvatar(
                profile,
                load,
                72.dp,
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 16.dp, y = 36.dp),
            )
        }
        Spacer(Modifier.size(40.dp))
        Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
        Text("@${profile.handle}", style = MaterialTheme.typography.bodyMedium)
        if (profile.bio.isNotBlank()) Text(profile.bio, style = MaterialTheme.typography.bodyMedium)
    }
}
