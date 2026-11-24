package com.adreels.skipper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adreels.skipper.R
import com.adreels.skipper.domain.DailySkipEntry
import com.adreels.skipper.domain.KeywordValidationError
import com.adreels.skipper.domain.ServiceConnectionState
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.domain.WeeklySkipAggregator
import com.adreels.skipper.domain.WeeklySkipRecord
import com.adreels.skipper.ui.theme.SkipperTheme
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToLong

internal object DashboardTags {
    const val SERVICE_STATUS_CARD = "ServiceStatusCard"
    const val RECONNECT_SERVICE_BUTTON = "ReconnectServiceButton"
    const val MASTER_TOGGLE = "MasterToggleSwitch"
    const val SKIP_DELAY_SLIDER = "SkipDelaySlider"
    const val KEYWORD_INPUT = "KeywordInputField"
    const val ADD_KEYWORD_BUTTON = "AddKeywordButton"
    const val KEYWORD_ERROR_TEXT = "KeywordErrorText"
    const val BACKGROUND_RELIABILITY_CARD = "BackgroundReliabilityCard"
    const val BATTERY_STATUS_TEXT = "BatteryStatusText"
    const val CONFIGURE_BATTERY_BUTTON = "ConfigureBatteryButton"
    const val OPEN_APP_INFO_BUTTON = "OpenAppInfoButton"
    const val WEEKLY_ACTIVITY_CARD = "WeeklyActivityCard"
    const val WEEKLY_RANGE_TEXT = "WeeklyRangeText"
    const val WEEKLY_TOTAL_TEXT = "WeeklyTotalText"
    const val OPEN_HISTORY_BUTTON = "OpenHistoryButton"
    const val HISTORY_DIALOG = "WeeklyHistoryDialog"
    const val HISTORY_EMPTY_TEXT = "HistoryEmptyText"
    const val CLOSE_HISTORY_BUTTON = "CloseHistoryButton"
    const val ARCHIVED_DETAIL_RANGE_TEXT = "ArchivedDetailRangeText"

    fun removeKeywordButton(keyword: String): String = "RemoveKeywordButton_$keyword"

    fun weeklyDayBar(dayOfWeek: DayOfWeek): String = "WeeklyDayBar_${dayOfWeek.name}"

    fun archivedWeekRow(weekStart: LocalDate): String = "ArchivedWeekRow_$weekStart"

    fun archivedDayBar(dayOfWeek: DayOfWeek): String = "ArchivedDayBar_${dayOfWeek.name}"
}

/**
 * Single-screen Material 3 Dashboard displaying service onboarding/recovery (`S-1`, `S-9`), active/paused
 * skipping controls (`S-2`/`S-3`), weekly activity chart + archive (`S-5`..`S-8`), delay slider,
 * custom keywords (`S-4`), and background reliability tips (`S-10`).
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    uiState: DashboardUiState = DashboardUiState(),
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenBatterySettings: () -> Unit = {},
    onOpenAppInfo: () -> Unit = {},
    onToggleMaster: (Boolean) -> Unit = {},
    onSkipDelayChange: (Long) -> Unit = {},
    onKeywordInputChange: (String) -> Unit = {},
    onAddKeyword: () -> Unit = {},
    onRemoveKeyword: (String) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onCloseHistory: () -> Unit = {},
    onSelectArchivedWeek: (LocalDate) -> Unit = {},
) {
    SkipperTheme {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DashboardHeader()
                ServiceStatusCard(
                    serviceConnectionState = uiState.serviceConnectionState,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                )
                MetricsAndMasterCard(
                    isMasterEnabled = uiState.isMasterEnabled,
                    skippedReelsCount = uiState.skippedReelsCount,
                    onToggleMaster = onToggleMaster,
                )
                WeeklyActivityCard(
                    currentWeek = uiState.currentWeek,
                    referenceYear = uiState.today.year,
                    onOpenHistory = onOpenHistory,
                )
                SkipDelayCard(
                    skipDelayMs = uiState.skipDelayMs,
                    onSkipDelayChange = onSkipDelayChange,
                )
                KeywordsCard(
                    builtInKeywords = uiState.builtInKeywords,
                    customKeywords = uiState.customKeywords,
                    keywordInput = uiState.keywordInput,
                    keywordError = uiState.keywordError,
                    onKeywordInputChange = onKeywordInputChange,
                    onAddKeyword = onAddKeyword,
                    onRemoveKeyword = onRemoveKeyword,
                )
                BackgroundReliabilityCard(
                    isBatteryUnrestricted = uiState.isBatteryUnrestricted,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenAppInfo = onOpenAppInfo,
                )
            }

            if (uiState.isHistoryDialogOpen) {
                WeeklyHistoryDialog(
                    archivedWeeks = uiState.archivedWeeks,
                    selectedWeekStart = uiState.selectedArchivedWeekStart,
                    referenceYear = uiState.today.year,
                    onSelectWeek = onSelectArchivedWeek,
                    onDismiss = onCloseHistory,
                )
            }
        }
    }
}

@Composable
private fun DashboardHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.dashboard_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceStatusCard(
    serviceConnectionState: ServiceConnectionState,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val containerColor = when (serviceConnectionState) {
        ServiceConnectionState.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer
        ServiceConnectionState.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.tertiaryContainer
        ServiceConnectionState.STOPPED -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (serviceConnectionState) {
        ServiceConnectionState.ACTIVE -> MaterialTheme.colorScheme.onSecondaryContainer
        ServiceConnectionState.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.onTertiaryContainer
        ServiceConnectionState.STOPPED -> MaterialTheme.colorScheme.onErrorContainer
    }
    val indicatorColor = when (serviceConnectionState) {
        ServiceConnectionState.ACTIVE -> MaterialTheme.colorScheme.secondary
        ServiceConnectionState.PERMISSION_REQUIRED,
        ServiceConnectionState.STOPPED,
        -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DashboardTags.SERVICE_STATUS_CARD),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(indicatorColor),
                )
                Text(
                    text = when (serviceConnectionState) {
                        ServiceConnectionState.ACTIVE -> stringResource(R.string.service_status_active)
                        ServiceConnectionState.PERMISSION_REQUIRED -> stringResource(R.string.service_status_permission_required)
                        ServiceConnectionState.STOPPED -> stringResource(R.string.service_status_stopped)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = when (serviceConnectionState) {
                    ServiceConnectionState.ACTIVE -> stringResource(R.string.service_status_active_desc)
                    ServiceConnectionState.PERMISSION_REQUIRED -> stringResource(R.string.service_status_disabled_desc)
                    ServiceConnectionState.STOPPED -> stringResource(R.string.service_status_stopped_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            when (serviceConnectionState) {
                ServiceConnectionState.PERMISSION_REQUIRED -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.enable_accessibility_service))
                    }
                }

                ServiceConnectionState.STOPPED -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(DashboardTags.RECONNECT_SERVICE_BUTTON),
                    ) {
                        Text(text = stringResource(R.string.reconnect_accessibility_service))
                    }
                }

                ServiceConnectionState.ACTIVE -> Unit
            }
        }
    }
}

@Composable
private fun MetricsAndMasterCard(
    isMasterEnabled: Boolean,
    skippedReelsCount: Int,
    onToggleMaster: (Boolean) -> Unit,
) {
    val toggleLabel = stringResource(R.string.master_toggle_label)
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.skipped_reels_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = skippedReelsCount.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = toggleLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (isMasterEnabled) {
                            stringResource(R.string.master_toggle_active_desc)
                        } else {
                            stringResource(R.string.master_toggle_paused_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isMasterEnabled,
                    onCheckedChange = onToggleMaster,
                    modifier = Modifier
                        .testTag(DashboardTags.MASTER_TOGGLE)
                        .semantics { contentDescription = toggleLabel },
                )
            }
        }
    }
}

@Composable
private fun WeeklyActivityCard(
    currentWeek: WeeklySkipRecord,
    referenceYear: Int,
    onOpenHistory: () -> Unit,
) {
    val weekRangeText = WeeklySkipAggregator.formatWeekRange(
        weekStart = currentWeek.weekStart,
        weekEnd = currentWeek.weekEnd,
        referenceYear = referenceYear,
    )
    val historyCd = stringResource(R.string.history_button_cd)

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DashboardTags.WEEKLY_ACTIVITY_CARD),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.weekly_activity_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = weekRangeText,
                        modifier = Modifier.testTag(DashboardTags.WEEKLY_RANGE_TEXT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.weekly_total_skips, currentWeek.totalSkips),
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag(DashboardTags.WEEKLY_TOTAL_TEXT),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenHistory,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(DashboardTags.OPEN_HISTORY_BUTTON)
                            .semantics { contentDescription = historyCd },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.history_button),
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }

            WeeklyBarChart(
                days = currentWeek.days,
                dayTagProvider = DashboardTags::weeklyDayBar,
                barMaxHeight = 64.dp,
            )
        }
    }
}

@Composable
private fun WeeklyBarChart(
    days: List<DailySkipEntry>,
    dayTagProvider: (DayOfWeek) -> String,
    barMaxHeight: Dp,
) {
    val maxCount = (days.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (entry in days) {
            WeeklyDayBarItem(
                entry = entry,
                maxCount = maxCount,
                barMaxHeight = barMaxHeight,
                modifier = Modifier.weight(1f),
                testTag = dayTagProvider(entry.dayOfWeek),
            )
        }
    }
}

@Composable
private fun WeeklyDayBarItem(
    entry: DailySkipEntry,
    maxCount: Int,
    barMaxHeight: Dp,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val dayLabel = shortDayLabel(entry.dayOfWeek)
    val accessibilitySummary = if (entry.isToday) {
        stringResource(R.string.day_skip_count_today_cd, dayLabel, entry.count)
    } else {
        stringResource(R.string.day_skip_count_cd, dayLabel, entry.count)
    }
    val barHeight = if (entry.count == 0) {
        4.dp
    } else {
        val fraction = (entry.count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
        (barMaxHeight * fraction).coerceAtLeast(10.dp)
    }
    val barColor = when {
        entry.isToday && entry.count == 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        entry.count == 0 -> MaterialTheme.colorScheme.surfaceVariant
        entry.isToday -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    val itemBackground = if (entry.isToday) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (entry.isToday) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(itemBackground)
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .testTag(testTag)
            .clearAndSetSemantics { contentDescription = accessibilitySummary },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = entry.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (entry.isToday) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            softWrap = false,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barMaxHeight),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .height(barHeight)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                    .background(barColor),
            )
        }
        Text(
            text = dayLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (entry.isToday) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun WeeklyHistoryDialog(
    archivedWeeks: List<WeeklySkipRecord>,
    selectedWeekStart: LocalDate?,
    referenceYear: Int,
    onSelectWeek: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DashboardTags.HISTORY_DIALOG),
        title = {
            Text(
                text = stringResource(R.string.past_weeks_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            if (archivedWeeks.isEmpty()) {
                Text(
                    text = stringResource(R.string.past_weeks_empty),
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .testTag(DashboardTags.HISTORY_EMPTY_TEXT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val selectedWeek = archivedWeeks.firstOrNull { it.weekStart == selectedWeekStart }
                    ?: archivedWeeks.first()
                val selectedRangeLabel = WeeklySkipAggregator.formatWeekRange(
                    weekStart = selectedWeek.weekStart,
                    weekEnd = selectedWeek.weekEnd,
                    referenceYear = referenceYear,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = selectedRangeLabel,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag(DashboardTags.ARCHIVED_DETAIL_RANGE_TEXT),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.weekly_total_skips, selectedWeek.totalSkips),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            WeeklyBarChart(
                                days = selectedWeek.days,
                                dayTagProvider = DashboardTags::archivedDayBar,
                                barMaxHeight = 52.dp,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (week in archivedWeeks) {
                            ArchivedWeekRow(
                                week = week,
                                referenceYear = referenceYear,
                                isSelected = week.weekStart == selectedWeek.weekStart,
                                onSelect = { onSelectWeek(week.weekStart) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(DashboardTags.CLOSE_HISTORY_BUTTON),
            ) {
                Text(text = stringResource(R.string.close_history_button))
            }
        },
    )
}

@Composable
private fun ArchivedWeekRow(
    week: WeeklySkipRecord,
    referenceYear: Int,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val rangeLabel = WeeklySkipAggregator.formatWeekRange(
        weekStart = week.weekStart,
        weekEnd = week.weekEnd,
        referenceYear = referenceYear,
    )
    val rowDescription = stringResource(R.string.archived_week_row_cd, rangeLabel, week.totalSkips)
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(DashboardTags.archivedWeekRow(week.weekStart))
            .semantics {
                selected = isSelected
                contentDescription = rowDescription
            },
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rangeLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
            )
            Text(
                text = stringResource(R.string.weekly_total_skips, week.totalSkips),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) textColor else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SkipDelayCard(
    skipDelayMs: Long,
    onSkipDelayChange: (Long) -> Unit,
) {
    val sliderLabel = stringResource(R.string.skip_delay_label)
    val stepsCount = (
        (SkipperConfig.MAX_SKIP_DELAY_MS - SkipperConfig.MIN_SKIP_DELAY_MS) /
            SkipperConfig.SKIP_DELAY_STEP_MS
    ).toInt() - 1

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sliderLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.skip_delay_value, skipDelayMs.toInt()),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Slider(
                value = skipDelayMs.toFloat(),
                onValueChange = { raw ->
                    onSkipDelayChange(SkipperConfig.snapAndClampSkipDelay(raw.roundToLong()))
                },
                valueRange = SkipperConfig.MIN_SKIP_DELAY_MS.toFloat()..SkipperConfig.MAX_SKIP_DELAY_MS.toFloat(),
                steps = stepsCount,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DashboardTags.SKIP_DELAY_SLIDER)
                    .semantics { contentDescription = sliderLabel },
            )
            Text(
                text = stringResource(R.string.skip_delay_range_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordsCard(
    builtInKeywords: List<String>,
    customKeywords: List<String>,
    keywordInput: String,
    keywordError: KeywordValidationError?,
    onKeywordInputChange: (String) -> Unit,
    onAddKeyword: () -> Unit,
    onRemoveKeyword: (String) -> Unit,
) {
    val errorMessage = keywordErrorMessage(keywordError)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.keywords_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.built_in_keywords_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (keyword in builtInKeywords) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = keyword,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Text(
                text = stringResource(R.string.custom_keywords_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = keywordInput,
                onValueChange = onKeywordInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DashboardTags.KEYWORD_INPUT),
                label = { Text(text = stringResource(R.string.keyword_input_label)) },
                singleLine = true,
                isError = errorMessage != null,
            )
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.testTag(DashboardTags.KEYWORD_ERROR_TEXT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onAddKeyword,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DashboardTags.ADD_KEYWORD_BUTTON),
            ) {
                Text(text = stringResource(R.string.add_keyword_button))
            }

            if (customKeywords.isEmpty()) {
                Text(
                    text = stringResource(R.string.custom_keywords_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (keyword in customKeywords) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = keyword,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { onRemoveKeyword(keyword) },
                                    modifier = Modifier.testTag(DashboardTags.removeKeywordButton(keyword)),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.remove_keyword_cd, keyword),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundReliabilityCard(
    isBatteryUnrestricted: Boolean,
    onOpenBatterySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val indicatorColor = if (isBatteryUnrestricted) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.error
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DashboardTags.BACKGROUND_RELIABILITY_CARD),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.background_reliability_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(indicatorColor),
                )
                Text(
                    text = if (isBatteryUnrestricted) {
                        stringResource(R.string.battery_status_unrestricted)
                    } else {
                        stringResource(R.string.battery_status_optimized)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(DashboardTags.BATTERY_STATUS_TEXT),
                )
            }
            Text(
                text = if (isBatteryUnrestricted) {
                    stringResource(R.string.battery_status_unrestricted_desc)
                } else {
                    stringResource(R.string.battery_status_optimized_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isBatteryUnrestricted) {
                Button(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(DashboardTags.CONFIGURE_BATTERY_BUTTON),
                ) {
                    Text(text = stringResource(R.string.configure_battery_settings))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Text(
                text = stringResource(R.string.restricted_settings_tip_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.restricted_settings_tip_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenAppInfo,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(DashboardTags.OPEN_APP_INFO_BUTTON),
            ) {
                Text(text = stringResource(R.string.open_app_info))
            }
        }
    }
}

@Composable
private fun shortDayLabel(dayOfWeek: DayOfWeek): String {
    return when (dayOfWeek) {
        DayOfWeek.MONDAY -> stringResource(R.string.day_short_mon)
        DayOfWeek.TUESDAY -> stringResource(R.string.day_short_tue)
        DayOfWeek.WEDNESDAY -> stringResource(R.string.day_short_wed)
        DayOfWeek.THURSDAY -> stringResource(R.string.day_short_thu)
        DayOfWeek.FRIDAY -> stringResource(R.string.day_short_fri)
        DayOfWeek.SATURDAY -> stringResource(R.string.day_short_sat)
        DayOfWeek.SUNDAY -> stringResource(R.string.day_short_sun)
    }
}

@Composable
private fun keywordErrorMessage(error: KeywordValidationError?): String? {
    return when (error) {
        KeywordValidationError.BLANK -> stringResource(R.string.error_keyword_blank)
        KeywordValidationError.TOO_SHORT -> stringResource(R.string.error_keyword_too_short)
        KeywordValidationError.TOO_LONG -> stringResource(R.string.error_keyword_too_long)
        KeywordValidationError.NO_ALPHANUMERIC -> stringResource(R.string.error_keyword_no_alnum)
        KeywordValidationError.DUPLICATE -> stringResource(R.string.error_keyword_duplicate)
        null -> null
    }
}
