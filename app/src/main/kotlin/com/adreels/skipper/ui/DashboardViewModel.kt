package com.adreels.skipper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.adreels.skipper.data.SkipperSettingsRepository
import com.adreels.skipper.domain.KeywordValidationError
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.SkipperConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Unidirectional UI state backing the single-screen Material 3 Dashboard (`AC-4`..`AC-6`). */
data class DashboardUiState(
    val isServiceEnabled: Boolean = false,
    val isMasterEnabled: Boolean = true,
    val skipDelayMs: Long = SkipperConfig.DEFAULT_SKIP_DELAY_MS,
    val builtInKeywords: List<String> = SkipperConfig.BUILT_IN_DISPLAY_KEYWORDS,
    val customKeywords: List<String> = emptyList(),
    val skippedReelsCount: Int = 0,
    val keywordInput: String = "",
    val keywordError: KeywordValidationError? = null,
)

/** Manages persisted settings updates, keyword validation, and service permission state for UI. */
class DashboardViewModel(
    private val repository: SkipperSettingsRepository,
    initialServiceEnabled: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            isServiceEnabled = initialServiceEnabled,
            isMasterEnabled = repository.settingsFlow.value.isMasterEnabled,
            skipDelayMs = repository.settingsFlow.value.skipDelayMs,
            customKeywords = repository.settingsFlow.value.customKeywords.toList(),
            skippedReelsCount = repository.settingsFlow.value.skippedReelsCount,
        ),
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settingsFlow.collect { settings ->
                _uiState.update { current ->
                    current.copy(
                        isMasterEnabled = settings.isMasterEnabled,
                        skipDelayMs = settings.skipDelayMs,
                        customKeywords = settings.customKeywords.toList(),
                        skippedReelsCount = settings.skippedReelsCount,
                    )
                }
            }
        }
    }

    /** Updates whether Android Accessibility Service permission is currently active. */
    fun refreshServiceStatus(isEnabled: Boolean) {
        _uiState.update { it.copy(isServiceEnabled = isEnabled) }
    }

    /** Toggles automatic ad skipping (`BR-2`) and persists the updated state locally. */
    fun setMasterEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isMasterEnabled = enabled) }
        launchPersistence {
            repository.setMasterEnabled(enabled)
        }
    }

    /** Snaps and clamps [delayMs] to `100..2000 ms` (`50 ms` step) and persists it (`BR-5`). */
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
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DashboardViewModel(
                        repository = repository,
                        initialServiceEnabled = initialServiceEnabled,
                    ) as T
                }
            }
        }
    }
}
