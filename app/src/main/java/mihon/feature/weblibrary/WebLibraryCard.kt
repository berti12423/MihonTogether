package mihon.feature.weblibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.tachiyomi.BuildConfig
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** Friends, Me: the "Library on the web" switch and "Share library with my browser". */
@Composable
fun WebLibraryCard() {
    val context = LocalContext.current
    val host = remember { context.appGraph.webLibraryHost }
    val preference = remember { context.appGraph.webLibraryPreferences.enabled }
    val enabled by preference.collectAsState()
    val state by host.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(MR.strings.web_library_setting), style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { preference.set(it) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(MR.strings.web_library_setting_summary), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        if (!enabled) return@Column
        when {
            state.connected -> {
                Text(stringResource(MR.strings.web_library_connected), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = host::disconnect) { Text(stringResource(MR.strings.web_library_disconnect)) }
            }
            state.availableUntil > 0 -> {
                Text(stringResource(MR.strings.web_library_available, webAddress()))
                OutlinedButton(onClick = host::disconnect) { Text(stringResource(MR.strings.web_library_stop)) }
            }
            else -> {
                Button(onClick = { host.shareNow() }) { Text(stringResource(MR.strings.web_library_share)) }
                Text(
                    stringResource(MR.strings.web_library_share_hint, webAddress()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Where to open the library, without the scheme: mihontogether.web.app/my-library. */
private fun webAddress(): String {
    val base = BuildConfig.SOCIAL_WEB_URL.takeIf { it.startsWith("https://") } ?: "https://mihontogether.web.app"
    return base.removePrefix("https://").trimEnd('/') + "/my-library"
}
