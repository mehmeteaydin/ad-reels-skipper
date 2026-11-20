package com.adreels.skipper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.KeywordValidator
import com.adreels.skipper.domain.ReelAdDetector
import com.adreels.skipper.domain.SkipperConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persisted user preferences and lifetime skip metrics (`BR-2`, `BR-5`, `BR-6`, `BR-7`). */
data class SkipperSettings(
    val isMasterEnabled: Boolean = true,
    val skipDelayMs: Long = SkipperConfig.DEFAULT_SKIP_DELAY_MS,
    val customKeywords: Set<String> = emptySet(),
    val skippedReelsCount: Int = 0,
) {
    /** Converts persisted user settings into a pure domain [SkipperConfig] snapshot. */
    fun toSkipperConfig(): SkipperConfig {
        return SkipperConfig(
            isMasterEnabled = isMasterEnabled,
            keywords = SkipperConfig.DEFAULT_KEYWORDS + customKeywords,
            skipDelayMs = SkipperConfig.clampSkipDelay(skipDelayMs),
        )
    }
}

/** Contract for local persistence and observation of user settings and skip metrics. */
interface SkipperSettingsRepository {
    val settingsFlow: StateFlow<SkipperSettings>

    suspend fun setMasterEnabled(enabled: Boolean)

    suspend fun setSkipDelayMs(delayMs: Long)

    suspend fun addCustomKeyword(rawKeyword: String): KeywordValidationResult

    suspend fun removeCustomKeyword(keyword: String): Boolean

    suspend fun incrementSkippedReelsCount()
}

private val Context.skipperPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DataStoreSkipperSettingsRepository.DATA_STORE_FILE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Jetpack DataStore implementation of [SkipperSettingsRepository] with safe recovery to defaults
 * when storage is unreadable or corrupted (`AC-1`, `AC-2`).
 */
class DataStoreSkipperSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SkipperSettingsRepository {

    private val _settingsFlow = MutableStateFlow(SkipperSettings())
    override val settingsFlow: StateFlow<SkipperSettings> = _settingsFlow.asStateFlow()

    private val mutationMutex = Mutex()
    private val stateLock = Any()
    private var mutationCount: Long = 0L

    init {
        scope.launch {
            safeSettingsFlow().collect { settings ->
                synchronized(stateLock) {
                    if (mutationCount == 0L) {
                        _settingsFlow.value = settings
                    }
                }
            }
        }
    }

    /** Suspends until the backing [DataStore] finishes its initial disk load and emits settings. */
    suspend fun awaitInitialLoad(): SkipperSettings {
        return mutationMutex.withLock {
            val loaded = safeSettingsFlow().first()
            synchronized(stateLock) {
                if (mutationCount == 0L) {
                    _settingsFlow.value = loaded
                }
                _settingsFlow.value
            }
        }
    }

    override suspend fun setMasterEnabled(enabled: Boolean) {
        mutatePreferences { prefs ->
            prefs[MASTER_ENABLED_KEY] = enabled
        }
    }

    override suspend fun setSkipDelayMs(delayMs: Long) {
        val clamped = SkipperConfig.clampSkipDelay(delayMs)
        mutatePreferences { prefs ->
            prefs[SKIP_DELAY_MS_KEY] = clamped
        }
    }

    override suspend fun addCustomKeyword(rawKeyword: String): KeywordValidationResult {
        return mutatePreferences { prefs ->
            val current = sanitizeCustomKeywords(prefs[CUSTOM_KEYWORDS_KEY].orEmpty())
            val result = KeywordValidator.validateCustomKeyword(rawKeyword, current)
            if (result is KeywordValidationResult.Valid) {
                prefs[CUSTOM_KEYWORDS_KEY] = current + result.trimmedKeyword
            }
            result
        }
    }

    override suspend fun removeCustomKeyword(keyword: String): Boolean {
        if (KeywordValidator.isBuiltInKeyword(keyword)) {
            return false
        }
        val targetNormalized = ReelAdDetector.normalizeKeywordTokens(keyword)
        return mutatePreferences { prefs ->
            val current = sanitizeCustomKeywords(prefs[CUSTOM_KEYWORDS_KEY].orEmpty())
            var removed = false
            val remaining = current.filterNot { existing ->
                val matches = existing == keyword ||
                    (targetNormalized.isNotEmpty() &&
                        ReelAdDetector.normalizeKeywordTokens(existing) == targetNormalized)
                if (matches) removed = true
                matches
            }.toSet()
            if (removed) {
                prefs[CUSTOM_KEYWORDS_KEY] = remaining
            }
            removed
        }
    }

    override suspend fun incrementSkippedReelsCount() {
        mutatePreferences { prefs ->
            val current = (prefs[SKIPPED_REELS_COUNT_KEY] ?: 0).coerceAtLeast(0)
            prefs[SKIPPED_REELS_COUNT_KEY] = current + 1
        }
    }

    private fun safeSettingsFlow(): Flow<SkipperSettings> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map(::mapPreferences)
    }

    private suspend fun <T> mutatePreferences(
        transform: (MutablePreferences) -> T,
    ): T {
        return mutationMutex.withLock {
            synchronized(stateLock) {
                mutationCount++
            }
            var result: T? = null
            val updated = dataStore.edit { prefs ->
                result = transform(prefs)
            }
            synchronized(stateLock) {
                _settingsFlow.value = mapPreferences(updated)
            }
            @Suppress("UNCHECKED_CAST")
            result as T
        }
    }

    private fun mapPreferences(prefs: Preferences): SkipperSettings {
        val masterEnabled = prefs[MASTER_ENABLED_KEY] ?: true
        val delayMs = SkipperConfig.clampSkipDelay(
            prefs[SKIP_DELAY_MS_KEY] ?: SkipperConfig.DEFAULT_SKIP_DELAY_MS,
        )
        val customKeywords = sanitizeCustomKeywords(prefs[CUSTOM_KEYWORDS_KEY].orEmpty())
        val skippedCount = (prefs[SKIPPED_REELS_COUNT_KEY] ?: 0).coerceAtLeast(0)
        return SkipperSettings(
            isMasterEnabled = masterEnabled,
            skipDelayMs = delayMs,
            customKeywords = customKeywords,
            skippedReelsCount = skippedCount,
        )
    }

    private fun sanitizeCustomKeywords(rawKeywords: Set<String>): Set<String> {
        val seenNormalized = mutableSetOf<String>()
        val sanitized = linkedSetOf<String>()
        for (entry in rawKeywords) {
            val trimmed = entry.trim()
            val normalized = ReelAdDetector.normalizeKeywordTokens(trimmed)
            if (trimmed.length in SkipperConfig.MIN_CUSTOM_KEYWORD_LENGTH..SkipperConfig.MAX_CUSTOM_KEYWORD_LENGTH &&
                normalized.isNotEmpty() &&
                !KeywordValidator.isBuiltInKeyword(trimmed) &&
                seenNormalized.add(normalized)
            ) {
                sanitized.add(trimmed)
            }
        }
        return sanitized
    }

    companion object {
        const val DATA_STORE_FILE_NAME: String = "skipper_settings"

        private val MASTER_ENABLED_KEY = booleanPreferencesKey("master_enabled")
        private val SKIP_DELAY_MS_KEY = longPreferencesKey("skip_delay_ms")
        private val CUSTOM_KEYWORDS_KEY = stringSetPreferencesKey("custom_keywords")
        private val SKIPPED_REELS_COUNT_KEY = intPreferencesKey("skipped_reels_count")

        /** Creates a corruption-safe [DataStore] backed by [produceFile] (used in unit tests). */
        fun createDataStore(
            scope: CoroutineScope,
            produceFile: () -> File,
        ): DataStore<Preferences> {
            return PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = produceFile,
            )
        }
    }
}

/** Process-wide provider ensuring a single DataStore instance across Activity and Service. */
object SkipperSettingsRepositoryProvider {
    @Volatile
    private var instance: SkipperSettingsRepository? = null

    @Volatile
    private var testingOverride: SkipperSettingsRepository? = null

    /** Returns the active repository singleton (or testing override when installed). */
    fun get(context: Context): SkipperSettingsRepository {
        testingOverride?.let { return it }
        return instance ?: synchronized(this) {
            testingOverride?.let { return@synchronized it }
            instance ?: DataStoreSkipperSettingsRepository(
                dataStore = context.applicationContext.skipperPreferencesDataStore,
            ).also { created ->
                instance = created
            }
        }
    }

    /** Installs or clears an isolated test repository double without creating disk DataStore files. */
    fun setForTesting(repository: SkipperSettingsRepository?) {
        synchronized(this) {
            testingOverride = repository
        }
    }
}
