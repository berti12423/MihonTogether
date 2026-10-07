package mihon.feature.social

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkUsage
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Data meter (Friends → Me): data used since the app opened (or the last reset), split into reading (manga pages,
 * covers, extensions) and everything else (Mihon Together's social features). Refreshes every few seconds.
 */
@Composable
fun DataUsageCard() {
    val context = LocalContext.current
    var usage by remember { mutableStateOf(NetworkUsage.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            usage = NetworkUsage.snapshot()
            delay(2_000)
        }
    }
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(MR.strings.data_usage_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    MR.strings.data_usage_since,
                    DateUtils.formatDateTime(
                        context,
                        usage.since,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(MR.strings.data_usage_reading, size(usage.readingBytes)))
            Text(stringResource(MR.strings.data_usage_social, size(usage.otherBytes)))
            Text(
                stringResource(MR.strings.data_usage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                NetworkUsage.reset()
                usage = NetworkUsage.snapshot()
            }) {
                Text(stringResource(MR.strings.data_usage_reset))
            }
        }
    }
}
