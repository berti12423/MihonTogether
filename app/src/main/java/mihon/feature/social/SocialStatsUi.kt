package mihon.feature.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.presentation.util.toDurationString
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.milliseconds

/** "My stats", opened from the Me tab. */
class SocialStatsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<SocialStatsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(viewModel, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.observe() }
        }
        SocialStatsContent(state, onBack = navigator::pop)
    }
}

@Composable
fun SocialStatsContent(state: SocialStatsState, onBack: () -> Unit) {
    var howOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(MR.strings.stats_title),
                navigateUp = onBack,
                actions = {
                    TextButton(onClick = { howOpen = true }) { Text(stringResource(MR.strings.xp_how_title)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.local?.let { local -> item { MonthSummaryCard(local.month) } }
            val xp = state.xp
            if (xp != null) {
                item { ChallengesCard(xp) }
                item { ServerStatsCard(xp) }
                item { AchievementsCard(xp) }
            } else if (!state.signedIn) {
                item {
                    Text(
                        stringResource(MR.strings.stats_signed_out),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.local?.let { local -> item { LocalStatsCard(local) } }
        }
    }
    if (howOpen) HowXpWorksDialog(onDismiss = { howOpen = false })
}

/** "This month" on this phone: chapters, new titles, best streak (could become a share card). */
@Composable
private fun MonthSummaryCard(month: LocalReadingStats.MonthSummary) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(MR.strings.stats_month_card, MonthlyChallenges.monthName(month.month)),
                style = MaterialTheme.typography.titleMedium,
            )
            StatGrid(
                listOf(
                    month.chapters.toString() to stringResource(MR.strings.stats_chapters),
                    month.newTitles.toString() to stringResource(MR.strings.stats_month_new),
                    month.bestStreak.toString() to stringResource(MR.strings.stats_month_streak),
                ),
            )
        }
    }
}

/** This month's three challenges with progress bars; [compact] for the Me tab. */
@Composable
fun ChallengesCard(xp: SocialXp?, compact: Boolean = false, actions: @Composable () -> Unit = {}) {
    val month = MonthlyChallenges.monthOf()
    val progress = MonthlyChallenges.progress(xp, month)
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(MR.strings.challenge_title, MonthlyChallenges.monthName(YearMonth.parse(month))),
                style = MaterialTheme.typography.titleMedium,
            )
            progress.forEach { item -> ChallengeRow(item) }
            if (progress.all { it.done }) {
                Text(stringResource(MR.strings.challenge_all_done), style = MaterialTheme.typography.bodySmall)
            } else if (!compact) {
                Text(
                    stringResource(MR.strings.challenge_reward, XpRules.CHALLENGE_XP),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            actions()
        }
    }
}

@Composable
private fun ChallengeRow(item: ChallengeProgress) {
    val name = stringResource(item.challenge.title, item.challenge.min)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.challenge.icon, Modifier.padding(end = 6.dp))
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (item.done) {
                    stringResource(MR.strings.challenge_done)
                } else {
                    stringResource(MR.strings.challenge_progress, item.value, item.challenge.min)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.run { if (item.done) primary else onSurfaceVariant },
            )
        }
        LinearProgressIndicator(progress = { item.fraction }, modifier = Modifier.fillMaxWidth())
    }
}

/** The server-side counters from `xp/{uid}`: level, progress and lifetime numbers. Also used on friend profiles. */
@Composable
fun ServerStatsCard(xp: SocialXp) {
    val (into, size) = XpLevels.progress(xp.total)
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(xp.level)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(XpLevels.title(xp.level)), style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(
                progress = { if (size > 0) into.toFloat() / size else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(MR.strings.social_xp_progress, into, size, xp.level + 1),
                style = MaterialTheme.typography.bodySmall,
            )
            StatGrid(
                listOf(
                    xp.total.toString() to stringResource(MR.strings.stats_total_xp),
                    xp.weekXp(xpWeek()).toString() to stringResource(MR.strings.stats_week_xp),
                    xp.chapters.toString() to stringResource(MR.strings.stats_chapters),
                    xp.together.toString() to stringResource(MR.strings.stats_together),
                    xp.streak.toString() to stringResource(MR.strings.stats_streak),
                    xp.bestStreak.toString() to stringResource(MR.strings.stats_best_streak),
                    xp.freezes.toString() to stringResource(MR.strings.stats_freezes),
                    xp.days.toString() to stringResource(MR.strings.stats_days),
                    xp.bestDay.toString() to stringResource(MR.strings.stats_best_day),
                ),
            )
        }
    }
}

/** Every achievement with its unlock date (locked ones greyed), plus season badges. */
@Composable
private fun AchievementsCard(xp: SocialXp) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val known = Badge.entries.count { it.id in xp.badges }
            Text(
                stringResource(MR.strings.social_badges_count, known, Badge.entries.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Badge.entries.forEach { badge ->
                val at = xp.badges[badge.id]
                BadgeLine(
                    icon = badge.icon,
                    title = stringResource(badge.title),
                    detail = if (at != null) {
                        stringResource(MR.strings.badge_unlocked_at, relativeTimeSpanString(at))
                    } else {
                        stringResource(badge.description)
                    },
                    unlocked = at != null,
                )
            }
            val seasons = MonthlyChallenges.seasons(xp.badges)
            if (seasons.isNotEmpty()) {
                Text(stringResource(MR.strings.stats_seasons), style = MaterialTheme.typography.titleSmall)
                seasons.forEach { (month, at) ->
                    BadgeLine(
                        icon = "🏆",
                        title = stringResource(MR.strings.challenge_season, MonthlyChallenges.monthName(month)),
                        detail = stringResource(MR.strings.badge_unlocked_at, relativeTimeSpanString(at)),
                        unlocked = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeLine(icon: String, title: String, detail: String, unlocked: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().alpha(if (unlocked) 1f else 0.45f),
    ) {
        Text(icon, Modifier.width(32.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Stats from this phone's history (never uploaded). */
@Composable
private fun LocalStatsCard(local: LocalReadingStats) {
    val context = LocalContext.current
    val noTime = stringResource(MR.strings.stats_no_time)
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(MR.strings.stats_on_phone), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(MR.strings.stats_on_phone_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(MR.strings.stats_weekly_chart), style = MaterialTheme.typography.titleSmall)
            WeeklyBars(local.weeks)
            Text(stringResource(MR.strings.stats_top_titles), style = MaterialTheme.typography.titleSmall)
            if (local.topTitles.isEmpty()) {
                Text(stringResource(MR.strings.stats_top_titles_none), style = MaterialTheme.typography.bodySmall)
            }
            local.topTitles.forEachIndexed { index, title ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}.", Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        title.title,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(MR.strings.stats_title_chapters, title.chapters),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            val totalTime = local.totalReadMs.milliseconds.toDurationString(context, noTime)
            StatGrid(listOf(totalTime to stringResource(MR.strings.stats_total_time)))
            local.longestDay?.let { day ->
                Text(stringResource(MR.strings.stats_longest_day), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        MR.strings.stats_longest_day_value,
                        day.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        day.chapters,
                        day.readMs.milliseconds.toDurationString(context, noTime),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Twelve weekly bars, oldest left; plain boxes, no chart library. */
@Composable
private fun WeeklyBars(weeks: List<LocalReadingStats.WeekCount>) {
    val max = weeks.maxOfOrNull { it.chapters }?.coerceAtLeast(1) ?: 1
    val dates = DateTimeFormatter.ofPattern("d MMM")
    Column {
        Row(
            Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            weeks.forEach { week ->
                val label = stringResource(MR.strings.stats_week_bar, week.start.format(dates), week.chapters)
                Column(
                    Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label },
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (week.chapters > 0) {
                        Text(week.chapters.toString(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((72f * week.chapters / max).dp.coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (week.chapters > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                weeks.firstOrNull()?.start?.format(dates).orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                weeks.lastOrNull()?.start?.format(dates).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Big numbers with small labels, wrapping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatGrid(cells: List<Pair<String, String>>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cells.forEach { (value, label) ->
            Column(Modifier.widthIn(min = 84.dp)) {
                Text(value, style = MaterialTheme.typography.titleLarge)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A friend's shared stats on their profile (only readable while they share stats). */
@Composable
fun FriendStatsSection(xp: SocialXp) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(MR.strings.stats_friend_section), style = MaterialTheme.typography.titleMedium)
        ServerStatsCard(xp)
        ChallengesCard(xp, compact = true)
        MonthlyChallenges.seasons(xp.badges).takeIf { it.isNotEmpty() }?.let { seasons ->
            Text(
                seasons.joinToString("\n") { (month, _) ->
                    "🏆 " + MonthlyChallenges.monthName(month)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** "How XP works": the real rules, from [XpRules] (kept equal to the backend constants by a test). */
@Composable
fun HowXpWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.xp_how_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(MR.strings.xp_how_chapter, XpRules.CHAPTER))
                Text(stringResource(MR.strings.xp_how_together, XpRules.TOGETHER))
                Text(
                    stringResource(
                        MR.strings.xp_how_streak,
                        XpRules.STREAK_DAY,
                        XpRules.STREAK_MAX_DAYS,
                        XpRules.STREAK_DAY * XpRules.STREAK_MAX_DAYS,
                    ),
                )
                Text(stringResource(MR.strings.xp_how_cap, XpRules.DAILY_CAP))
                Text(stringResource(MR.strings.xp_how_double))
                Text(stringResource(MR.strings.xp_how_freezes, XpRules.FREEZE_EVERY_DAYS, XpRules.MAX_FREEZES))
                Text(stringResource(MR.strings.xp_how_challenges, XpRules.CHALLENGE_XP))
                Text(stringResource(MR.strings.xp_how_levels), style = MaterialTheme.typography.titleSmall)
                XpRules.SHOWN_LEVELS.forEach { level ->
                    Text(
                        stringResource(
                            MR.strings.xp_how_level_row,
                            level,
                            XpLevels.xpFor(level),
                            stringResource(XpLevels.title(level)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(MR.strings.xp_how_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_close)) } },
    )
}
