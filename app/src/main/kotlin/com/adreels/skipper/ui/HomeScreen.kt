package com.adreels.skipper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adreels.skipper.R
import com.adreels.skipper.domain.KeywordValidationError
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.ui.theme.SkipperTheme
import kotlin.math.roundToLong

internal object DashboardTags {
    const val MASTER_TOGGLE = "MasterToggleSwitch"
    const val SKIP_DELAY_SLIDER = "SkipDelaySlider"
    const val KEYWORD_INPUT = "KeywordInputField"
    const val ADD_KEYWORD_BUTTON = "AddKeywordButton"
    const val KEYWORD_ERROR_TEXT = "KeywordErrorText"

    fun removeKeywordButton(keyword: String): String = "RemoveKeywordButton_$keyword"
}

/**
 * Single-screen Material 3 Dashboard displaying service permission onboarding (`S-1`), active/paused
 * skipping controls (`S-2`/`S-3`), delay slider, lifetime skip metrics, and custom keywords (`S-4`).
 */
@Composable
fun HomeScreen(
    uiState: DashboardUiState = DashboardUiState(),
    onOpenAccessibilitySettings: () -> Unit = {},
    onToggleMaster: (Boolean) -> Unit = {},
    onSkipDelayChange: (Long) -> Unit = {},
    onKeywordInputChange: (String) -> Unit = {},
    onAddKeyword: () -> Unit = {},
    onRemoveKeyword: (String) -> Unit = {},
    modifier: Modifier = Modifier,
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
                    isServiceEnabled = uiState.isServiceEnabled,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                )
                MetricsAndMasterCard(
                    isMasterEnabled = uiState.isMasterEnabled,
                    skippedReelsCount = uiState.skippedReelsCount,
                    onToggleMaster = onToggleMaster,
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
    isServiceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val containerColor = if (isServiceEnabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isServiceEnabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val indicatorColor = if (isServiceEnabled) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    text = if (isServiceEnabled) {
                        stringResource(R.string.service_status_active)
                    } else {
                        stringResource(R.string.service_status_permission_required)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = if (isServiceEnabled) {
                    stringResource(R.string.service_status_active_desc)
                } else {
                    stringResource(R.string.service_status_disabled_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isServiceEnabled) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.enable_accessibility_service))
                }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
