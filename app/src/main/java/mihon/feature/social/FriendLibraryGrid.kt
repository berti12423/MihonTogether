package mihon.feature.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.manga.components.MangaCover
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource

/** Covers come straight from the shared https URL; there is no local manga behind them. */
internal fun SocialContent.cover() = tachiyomi.domain.manga.model.MangaCover(
    mangaId = -1,
    sourceId = -1,
    isMangaFavorite = false,
    url = coverUrl,
    lastModified = 0,
)

/** A friend's title in Mihon's comfortable-grid style, with an "In library" badge when I have it too. */
@Composable
private fun SharedCover(content: SocialContent, inLibrary: Boolean, onClick: () -> Unit) {
    MangaComfortableGridItem(
        coverData = content.cover(),
        title = content.title,
        onClick = onClick,
        onLongClick = onClick,
        coverBadgeStart = if (inLibrary) {
            { Badge(text = stringResource(MR.strings.social_in_library_badge)) }
        } else {
            null
        },
    )
}

/** Profile section: title with count, a strip of the first covers, and "See all" for the grid. */
@Composable
fun FriendLibraryPreview(
    title: String,
    items: List<SocialContent>,
    count: Int?,
    localIndex: Map<String, Long>,
    onSeeAll: () -> Unit,
    onPick: (SocialContent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (count != null) "$title ($count)" else title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) {
                TextButton(onClick = onSeeAll) { Text(stringResource(MR.strings.social_see_all)) }
            }
        }
        if (items.isEmpty()) {
            Text(stringResource(MR.strings.social_library_empty), style = MaterialTheme.typography.bodySmall)
            return
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.contentKey }) { content ->
                Box(Modifier.width(108.dp)) {
                    SharedCover(content, localIndex[content.contentKey] != null) { onPick(content) }
                }
            }
        }
    }
}

/**
 * A friend's whole library or favorites like Mihon's library: pages load A–Z as you scroll; searching or the
 * "Not in my library" filter loads the rest first, since they need every title.
 */
@Composable
fun FriendLibraryGrid(
    grid: FriendGrid,
    localIndex: Map<String, Long>,
    contentPadding: PaddingValues,
    onLoad: (all: Boolean) -> Unit,
    onPick: (SocialContent) -> Unit,
) {
    var query by rememberSaveable(grid.uid, grid.kind) { mutableStateOf("") }
    var missingOnly by rememberSaveable(grid.uid, grid.kind) { mutableStateOf(false) }
    LaunchedEffect(query.isNotBlank() || missingOnly) {
        if (query.isNotBlank() || missingOnly) onLoad(true)
    }
    val shown = remember(grid.items, query, missingOnly, localIndex) {
        grid.items.filter { content ->
            (query.isBlank() || content.title.contains(query.trim(), ignoreCase = true)) &&
                (!missingOnly || localIndex[content.contentKey] == null)
        }
    }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(100) },
            leadingIcon = { Icon(MaterialSymbols.Rounded.Search, contentDescription = null) },
            placeholder = { Text(stringResource(MR.strings.social_grid_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = missingOnly,
                onClick = { missingOnly = !missingOnly },
                label = { Text(stringResource(MR.strings.social_grid_missing)) },
            )
            Text(
                stringResource(MR.strings.social_grid_count, shown.size, grid.total ?: grid.items.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.contentKey }) { content ->
                SharedCover(content, localIndex[content.contentKey] != null) { onPick(content) }
            }
            if (!grid.complete || grid.loading || grid.failed) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (grid.failed) {
                            TextButton(onClick = { onLoad(false) }) { Text(stringResource(MR.strings.action_retry)) }
                        } else {
                            // Reaching the end of what's loaded asks for the next page.
                            LaunchedEffect(grid.items.size) { onLoad(false) }
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a friend's title can do here: open my copy, or search my sources for it; "Read together" (pick friends) when
 * it's in my library; and on a friend's own title the private "I'd read this with you" wish ([wished] not null).
 */
@Composable
fun SharedTitleDialog(
    content: SocialContent,
    localId: Long?,
    onOpenManga: (Long) -> Unit,
    onSearch: (String) -> Unit,
    onReadTogether: (() -> Unit)? = null,
    wished: Boolean? = null,
    onToggleWish: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            MangaCover.Book(data = content.cover(), modifier = Modifier.width(96.dp))
        },
        title = { Text(content.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (localId != null) {
                            MR.strings.social_title_in_library
                        } else {
                            MR.strings.social_title_not_in_library
                        },
                    ),
                )
                if (wished != null) {
                    FilterChip(
                        selected = wished,
                        onClick = onToggleWish,
                        label = { Text(stringResource(MR.strings.social_wish_toggle)) },
                    )
                    Text(
                        stringResource(MR.strings.social_wish_private),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onReadTogether != null) {
                    TextButton(onClick = {
                        onReadTogether()
                        onDismiss()
                    }) { Text(stringResource(MR.strings.social_read_together)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (localId != null) onOpenManga(localId) else onSearch(content.title)
                onDismiss()
            }) {
                Text(
                    stringResource(
                        if (localId != null) MR.strings.social_open_in_library else MR.strings.social_search_title,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
}
