package mihon.feature.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * "Edit page translation": the current page's blocks (original and translation), each editable or hideable, plus the
 * title's glossary. Saving stores the edits in the cached translation and redraws the page. With an active reading
 * room, a block can be sent to the room chat as a quote.
 */
@Composable
fun PageTranslationSheet(
    translation: PageTranslation,
    glossary: Glossary,
    canSendToRoom: Boolean,
    onSave: (blocks: List<TranslatedBlock>, glossary: Glossary) -> Unit,
    onSendToRoom: (TranslatedBlock) -> Unit,
    onDismiss: () -> Unit,
) {
    val blocks = remember(translation) { mutableStateListOf(*translation.blocks.toTypedArray()) }
    val entries = remember(glossary) { mutableStateListOf(*glossary.entries.toTypedArray()) }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(16.dp)
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(MR.strings.translate_edit_title), style = MaterialTheme.typography.titleMedium)
            if (blocks.isEmpty()) {
                Text(stringResource(MR.strings.translate_edit_empty), style = MaterialTheme.typography.bodyMedium)
            }
            blocks.forEachIndexed { index, block ->
                BlockEditor(
                    block = block,
                    original = translation.blocks[index],
                    canSendToRoom = canSendToRoom,
                    onChange = { blocks[index] = it },
                    onSendToRoom = { onSendToRoom(block) },
                )
                HorizontalDivider()
            }

            Text(stringResource(MR.strings.translate_glossary), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(MR.strings.translate_glossary_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.forEachIndexed { index, entry ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = entry.find,
                        onValueChange = { entries[index] = entry.copy(find = it) },
                        label = { Text(stringResource(MR.strings.translate_glossary_find)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = entry.replace,
                        onValueChange = { entries[index] = entry.copy(replace = it) },
                        label = { Text(stringResource(MR.strings.translate_glossary_replace)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = { entries.removeAt(index) }) { Text(stringResource(MR.strings.action_remove)) }
            }
            TextButton(onClick = { entries += GlossaryEntry("", "") }) {
                Text(stringResource(MR.strings.translate_glossary_add))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
                TextButton(onClick = { onSave(blocks.toList(), Glossary(entries.toList()).cleaned()) }) {
                    Text(stringResource(MR.strings.action_save))
                }
            }
        }
    }
}

@Composable
private fun BlockEditor(
    block: TranslatedBlock,
    original: TranslatedBlock,
    canSendToRoom: Boolean,
    onChange: (TranslatedBlock) -> Unit,
    onSendToRoom: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            block.source,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = block.translation,
            onValueChange = {
                onChange(block.copy(translation = it, edited = original.edited || it != original.translation))
            },
            enabled = !block.hidden,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .toggleable(
                        value = block.hidden,
                        role = Role.Checkbox,
                        onValueChange = { onChange(block.copy(hidden = it)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = block.hidden, onCheckedChange = null)
                Text(stringResource(MR.strings.translate_edit_hide), Modifier.padding(start = 8.dp))
            }
            if (canSendToRoom && block.translation.isNotBlank()) {
                TextButton(onClick = onSendToRoom) { Text(stringResource(MR.strings.translate_edit_send_room)) }
            }
        }
    }
}
