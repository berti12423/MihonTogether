package mihon.feature.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.roundedfilled.Favorite
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource

/**
 * My library's covers by shared content key ([FriendList.covers]). The Friends screen provides them, so every title
 * picker (chat and group recommendations, Read together) shows covers without passing them through each chat view.
 */
val LocalTitleCovers = compositionLocalOf<Map<String, MangaCover>> { emptyMap() }

/** What the title picker lists. */
object TitlePicker {
    /** Titles matching [query] A–Z; [pinned] (what a friend is reading now) comes first when it matches. */
    fun shown(titles: List<SocialContent>, query: String, pinned: SocialContent? = null): List<SocialContent> {
        val needle = query.trim()
        val matching = titles.asSequence()
            .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
            .distinctBy { it.contentKey }
            .sortedBy { it.title.lowercase() }
            .toList()
        val first = pinned?.let { pin -> matching.firstOrNull { it.contentKey == pin.contentKey } } ?: return matching
        return listOf(first) + matching.filterNot { it.contentKey == first.contentKey }
    }
}

/**
 * Pick one of my library titles (to recommend, or to read together): full screen, searchable, a cover grid like the
 * library. [pinned] is listed first. With [selected] it toggles instead (social favorites): selected covers carry a
 * heart, the others are dimmed, a tap calls [onPick] and the picker stays open until Done; [busy] ignores taps.
 */
@Composable
fun TitlePickerDialog(
    titles: List<SocialContent>,
    onPick: (SocialContent) -> Unit,
    title: String? = null,
    pinned: SocialContent? = null,
    selected: Set<String>? = null,
    busy: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val covers = LocalTitleCovers.current
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(titles, query, pinned) { TitlePicker.shown(titles, query, pinned) }
    val pinnedKey = pinned?.contentKey?.takeIf { key -> shown.firstOrNull()?.contentKey == key }
    Dialog(
        onDismissRequest = onDismiss,
        // Edge to edge: the bars and the keyboard come in as insets, so the grid ends above the keyboard.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                AppBar(
                    title = title ?: stringResource(MR.strings.social_recommend_title),
                    navigateUp = onDismiss,
                    navigationIcon = MaterialSymbols.Rounded.Close,
                    actions = {
                        if (selected != null) {
                            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.social_picker_done)) }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    leadingIcon = { Icon(MaterialSymbols.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(MR.strings.social_grid_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                header?.let { Box(Modifier.padding(horizontal = 16.dp)) { it() } }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                if (shown.isEmpty()) {
                    Text(
                        stringResource(MR.strings.no_results_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
                // Lazy, so covers load only as they scroll into view; no cap on the number of titles.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.contentKey }) { content ->
                        val chosen = selected?.let { content.contentKey in it }
                        PickerCover(
                            content = content,
                            cover = covers[content.contentKey],
                            readingNow = content.contentKey == pinnedKey,
                            chosen = chosen,
                        ) { if (!busy) onPick(content) }
                    }
                }
            }
        }
    }
}

/** One title in the picker: my library's cover with the name under it; [chosen] null when not toggling. */
@Composable
private fun PickerCover(
    content: SocialContent,
    cover: MangaCover?,
    readingNow: Boolean,
    chosen: Boolean?,
    onClick: () -> Unit,
) {
    Box(Modifier.semantics { if (chosen != null) selected = chosen }) {
        MangaComfortableGridItem(
            // Not in my library's covers (e.g. still loading): the shared https cover, if any.
            coverData = cover ?: MangaCover(
                mangaId = -1,
                sourceId = -1,
                isMangaFavorite = false,
                url = content.coverUrl,
                lastModified = 0,
            ),
            title = content.title,
            onClick = onClick,
            onLongClick = onClick,
            coverAlpha = if (chosen == false) DIMMED else 1f,
            coverBadgeStart = if (readingNow) {
                { Badge(text = stringResource(MR.strings.social_read_together_reading_now)) }
            } else {
                null
            },
            coverBadgeEnd = if (chosen == true) {
                {
                    Badge(
                        imageVector = MaterialSymbols.RoundedFilled.Favorite,
                        color = MaterialTheme.colorScheme.primary,
                        iconColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else {
                null
            },
        )
    }
}

private const val DIMMED = 0.4f
