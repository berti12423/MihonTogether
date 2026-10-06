package mihon.feature.social

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MEDALS = listOf("🥇", "🥈", "🥉")
private val MEDAL_RINGS = listOf(Color(0xFFFFC94D), Color(0xFFC7CDD6), Color(0xFFD9955B))

/** The top three as a podium (second, first, third), each with banner, avatar, name and score. */
@Composable
internal fun RankingPodium(
    top: List<RankingEntry>,
    profileOf: (RankingEntry) -> SocialProfile,
    score: @Composable (RankingEntry) -> String,
    meLabel: String,
    load: ProfileMediaLoader,
    onClick: (RankingEntry) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (place in listOf(1, 0, 2)) {
            val entry = top.getOrNull(place)
            if (entry == null) {
                Spacer(Modifier.weight(1f))
            } else {
                PodiumSpot(
                    entry = entry,
                    place = place,
                    profile = profileOf(entry),
                    score = score(entry),
                    name = if (entry.me) meLabel else entry.name,
                    load = load,
                    modifier = Modifier.weight(1f),
                    onClick = { onClick(entry) },
                )
            }
        }
    }
}

@Composable
private fun PodiumSpot(
    entry: RankingEntry,
    place: Int,
    profile: SocialProfile,
    score: String,
    name: String,
    load: ProfileMediaLoader,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // First place stands taller.
    val bannerHeight = if (place == 0) 64.dp else 44.dp
    val avatarSize = if (place == 0) 60.dp else 48.dp
    Card(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = !entry.me, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.me) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(bannerHeight)) {
                    ProfileBannerFill(profile, load, Modifier.matchParentSize())
                    Text(MEDALS[place], Modifier.align(Alignment.TopEnd).padding(6.dp), fontSize = 18.sp)
                }
                ProfileAvatar(
                    profile,
                    load,
                    avatarSize,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = bannerHeight - avatarSize / 2)
                        .border(3.dp, MEDAL_RINGS[place], CircleShape),
                )
            }
            Text(
                name,
                Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.level?.let { LevelBadge(it, Modifier.padding(top = 4.dp)) }
            Text(
                score,
                Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                // My card is primaryContainer: primary text would vanish into it.
                color = if (entry.me) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** Fourth place and below: position, avatar, name with level, a detail line and the score. */
@Composable
internal fun RankingRow(
    position: Int,
    entry: RankingEntry,
    profile: SocialProfile,
    name: String,
    detail: String?,
    score: String,
    load: ProfileMediaLoader,
    onClick: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (entry.me) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$position",
                Modifier.width(28.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            ProfileAvatar(profile, load, 40.dp)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.level?.let { LevelBadge(it, Modifier.padding(start = 6.dp)) }
                }
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                score,
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
