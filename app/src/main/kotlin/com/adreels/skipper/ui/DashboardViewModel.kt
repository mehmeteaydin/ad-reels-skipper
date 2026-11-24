package com.adreels.skipper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.adreels.skipper.data.SkipperSettingsRepository
import com.adreels.skipper.domain.KeywordValidationError
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.ServiceConnectionState
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.domain.WeeklySkipAggregator
import com.adreels.skipper.domain.WeeklySkipRecord
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Clock provider for active calendar week computation, overridable in Compose UI tests. */
internal object DashboardClock {
    @Volatile
    var testingTodayOverride: (() -> LocalDate)? = null

    fun today(): LocalDate = testingTodayOverride?.invoke() ?: LocalDate.now()
}

/** Unidirectional UI state backing the single-screen Material 3 Dashboard (`AC-2`..`AC-6`). */
data class DashboardUiState(
    val serviceConnectionState: ServiceConnectionState = ServiceConnectionState.PERMISSION_REQUIRED,
    val isServiceEnabled: Boolean = serviceConnectionState == ServiceConnectionState.ACTIVE,
    val isBatteryUnrestricted: Boolean = false,
    val isMasterEnabled: Boolean = true,
    val skipDelayMs: Long = SkipperConfig.DEFAULT_SKIP_DELAY_MS,
    val builtInKeywords: List<String> = SkipperConfig.BUILT_IN_DISPLAY_KEYWORDS,
    val customKeywords: List<String> = emptyList(),
    val skippedReelsCount: Int = 0,
    val keywordInput: String = "",
    val keywordError: KeywordValidationError? = null,
    val today: LocalDate = LocalDate.now(),
    val currentWeek: WeeklySkipRecord = WeeklySkipAggregator.summarize(
        dailyCounts = emptyMap(),
        today = today,
    ).currentWeek,
    val archivedWeeks: List<WeeklySkipRecord> = emptyList(),
    val isHistoryDialogOpen: Boolean = false,
    val selectedArchivedWeekStart: LocalDate? = null,
)

/** Manages persisted settings updates, weekly skip summaries, keyword validation, and platform status for UI. */
class DashboardViewModel(
    private val repository: SkipperSettingsRepository,
    initialServiceEnabled: Boolean = false,
    private val todayProvider: () -> LocalDate = DashboardClock::today,
    private val initialConnectionState: ServiceConnectionState = if (initialServiceEnabled) {
        ServiceConnectionState.ACTIVE
    } else {
        ServiceConnectionState.PERMISSION_REQUIRED
    },
    private val initialBatteryUnrestricted: Boolean = false,
) : ViewModel() {

    private val stateLock = Any()
    private var latestDailySkipCounts: Map<LocalDate, Int> = repository.settingsFlow.value.dailySkipCounts

    private val _uiState = MutableStateFlow(
        buildInitialState(
            connectionState = initialConnectionState,
            isBatteryUnrestricted = initialBatteryUnrestricted,
        ),
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settingsFlow.collect { settings ->
                val activeToday = todayProvider()
                val summary = WeeklySkipAggregator.summarize(
                    dailyCounts = settings.dailySkipCounts,
                    today = activeToday,
                )
                synchronized(stateLock) {
                    latestDailySkipCounts = settings.dailySkipCounts
                }
                _uiState.update { current ->
                    current.copy(
                        isMasterEnabled = settings.isMasterEnabled,
                        skipDelayMs = settings.skipDelayMs,
                        customKeywords = settings.customKeywords.toList(),
                        skippedReelsCount = settings.skippedReelsCount,
                        today = activeToday,
                        currentWeek = summary.currentWeek,
                        archivedWeeks = summary.archivedWeeks,
                        selectedArchivedWeekStart = resolveSelectedArchiveWeek(
                            currentSelection = current.selectedArchivedWeekStart,
                            archivedWeeks = summary.archivedWeeks,
                        ),
                    )
                }
            }
        }
    }

    /** Updates connection state, battery exemption status, and weekly buckets (`onResume` / live updates). */
    fun refreshPlatformStatus(
        connectionState: ServiceConnectionState,
        isBatteryUnrestricted: Boolean,
    ) {
        val activeToday = todayProvider()
        val dailyCounts = synchronized(stateLock) { latestDailySkipCounts }
        val summary = WeeklySkipAggregator.summarize(
            dailyCounts = dailyCounts,
            today = activeToday,
        )
        _uiState.update { current ->
            current.copy(
                serviceConnectionState = connectionState,
                isServiceEnabled = connectionState == ServiceConnectionState.ACTIVE,
                isBatteryUnrestricted = isBatteryUnrestricted,
                today = activeToday,
                currentWeek = summary.currentWeek,
                archivedWeeks = summary.archivedWeeks,
                selectedArchivedWeekStart = resolveSelectedArchiveWeek(
                    currentSelection = current.selectedArchivedWeekStart,
                    archivedWeeks = summary.archivedWeeks,
                ),
            )
        }
    }

    /** Opens the Past Weeks history dialog with the newest archived week pre-selected (`S-7`, `S-8`). */
    fun openHistory() {
        _uiState.update { current ->
            current.copy(
                isHistoryDialogOpen = true,
                selectedArchivedWeekStart = current.archivedWeeks.firstOrNull()?.weekStart,
            )
        }
    }

    /** Closes the Past Weeks history dialog (`S-7`, `S-8`). */
    fun closeHistory() {
        _uiState.update { current ->
            current.copy(isHistoryDialogOpen = false)
        }
    }

    /** Selects an archived week in the Past Weeks detail breakdown (`S-8`). */
    fun selectArchivedWeek(weekStart: LocalDate) {
        _uiState.update { current ->
            if (current.archivedWeeks.none { it.weekStart == weekStart }) {
                current
            } else {
                current.copy(selectedArchivedWeekStart = weekStart)
            }
        }
    }

    /** Toggles automatic ad skipping (`BR-2`) and persists the updated state locally. */
    fun setMasterEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isMasterEnabled = enabled) }
        launchPersistence {
            repository.setMasterEnabled(enabled)
        }
    }

    /** Snaps and clamps [delayMs] to `50..2000 ms` (`50 ms` step) and persists it (`BR-5`). */
    fun setSkipDelayMs(delayMs: Long) {
        val snapped = SkipperConfig.snapAndClampSkipDelay(delayMs)
        _uiState.update { it.copy(skipDelayMs = snapped) }
        launchPersistence {
            repository.setSkipDelayMs(snapped)
        }
    }

    /** Updates candidate custom keyword input and clears stale inline error while editing. */
    fun onKeywordInputChange(value: String) {
        _uiState.update { current ->
            val clearError = value != current.keywordInput
            current.copy(
                keywordInput = value,
                keywordError = if (clearError) null else current.keywordError,
            )
        }
    }

    /** Validates and saves the current [DashboardUiState.keywordInput] (`BR-6`, `S-4`). */
    fun submitKeyword() {
        val candidate = _uiState.value.keywordInput
        launchPersistence {
            when (val result = repository.addCustomKeyword(candidate)) {
                is KeywordValidationResult.Valid -> {
                    _uiState.update {
                        it.copy(
                            keywordInput = "",
                            keywordError = null,
                        )
                    }
                }

                is KeywordValidationResult.Invalid -> {
                    _uiState.update { it.copy(keywordError = result.error) }
                }
            }
        }
    }

    /** Deletes a user-added custom keyword while preserving built-in defaults (`AC-2`). */
    fun removeCustomKeyword(keyword: String) {
        launchPersistence {
            repository.removeCustomKeyword(keyword)
        }
    }

    private fun buildInitialState(
        connectionState: ServiceConnectionState,
        isBatteryUnrestricted: Boolean,
    ): DashboardUiState {
        val settings = repository.settingsFlow.value
        val activeToday = todayProvider()
        val summary = WeeklySkipAggregator.summarize(
            dailyCounts = settings.dailySkipCounts,
            today = activeToday,
        )
        return DashboardUiState(
            serviceConnectionState = connectionState,
            isServiceEnabled = connectionState == ServiceConnectionState.ACTIVE,
            isBatteryUnrestricted = isBatteryUnrestricted,
            isMasterEnabled = settings.isMasterEnabled,
            skipDelayMs = settings.skipDelayMs,
            customKeywords = settings.customKeywords.toList(),
            skippedReelsCount = settings.skippedReelsCount,
            today = activeToday,
            currentWeek = summary.currentWeek,
            archivedWeeks = summary.archivedWeeks,
            selectedArchivedWeekStart = summary.archivedWeeks.firstOrNull()?.weekStart,
        )
    }

    private fun resolveSelectedArchiveWeek(
        currentSelection: LocalDate?,
        archivedWeeks: List<WeeklySkipRecord>,
    ): LocalDate? {
        return archivedWeeks.firstOrNull { it.weekStart == currentSelection }?.weekStart
            ?: archivedWeeks.firstOrNull()?.weekStart
    }

    private fun launchPersistence(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Preserve current in-memory UI state when disk write fails.
            } catch (_: RuntimeException) {
                // Preserve current in-memory UI state when unexpected persistence failure occurs.
            }
        }
    }

    companion object {
        /** Factory binding [SkipperSettingsRepository] cleanly into Android `ViewModelProvider`. */
        fun factory(
            repository: SkipperSettingsRepository,
            initialServiceEnabled: Boolean = false,
            todayProvider: () -> LocalDate = DashboardClock::today,
            initialConnectionState: ServiceConnectionState = if (initialServiceEnabled) {
                ServiceConnectionState.ACTIVE
            } else {
                ServiceConnectionState.PERMISSION_REQUIRED
            },
            initialBatteryUnrestricted: Boolean = false,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DashboardViewModel(
                        repository = repository,
                        initialServiceEnabled = initialServiceEnabled,
                        todayProvider = todayProvider,
                        initialConnectionState = initialConnectionState,
                        initialBatteryUnrestricted = initialBatteryUnrestricted,
                    ) as T
                }
            }
        }
    }
}
