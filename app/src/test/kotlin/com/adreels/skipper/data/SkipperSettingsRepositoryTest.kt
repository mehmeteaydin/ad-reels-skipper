package com.adreels.skipper.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.adreels.skipper.domain.KeywordValidationError
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.SkipperConfig
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SkipperSettingsRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `initializes defaults persists changes across restarts clamps delay and increments skip counter`() = runBlocking {
        val storeFile = File(tempDir.toFile(), "skipper_settings.preferences_pb")

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo1 = DataStoreSkipperSettingsRepository(
            dataStore = DataStoreSkipperSettingsRepository.createDataStore(scope1) { storeFile },
            scope = scope1,
        )
        val initial = repo1.awaitInitialLoad()
        assertEquals(
            SkipperSettings(
                isMasterEnabled = true,
                skipDelayMs = 150L,
                customKeywords = emptySet(),
                skippedReelsCount = 0,
            ),
            initial,
        )

        repo1.setMasterEnabled(false)
        repo1.setSkipDelayMs(10L)
        assertEquals(50L, repo1.settingsFlow.value.skipDelayMs)
        repo1.setSkipDelayMs(50L)
        assertEquals(50L, repo1.settingsFlow.value.skipDelayMs)
        repo1.setSkipDelayMs(3_500L)
        assertEquals(2_000L, repo1.settingsFlow.value.skipDelayMs)
        repo1.setSkipDelayMs(750L)

        assertEquals(
            KeywordValidationResult.Invalid(KeywordValidationError.DUPLICATE),
            repo1.addCustomKeyword("SPONSORED"),
        )
        assertEquals(
            KeywordValidationResult.Valid("Reklam"),
            repo1.addCustomKeyword("  Reklam  "),
        )
        assertFalse(repo1.removeCustomKeyword("Sponsorlu"))

        repo1.incrementSkippedReelsCount()
        repo1.incrementSkippedReelsCount()

        scope1.cancel()
        scope1.coroutineContext[Job]?.join()

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo2 = DataStoreSkipperSettingsRepository(
                dataStore = DataStoreSkipperSettingsRepository.createDataStore(scope2) { storeFile },
                scope = scope2,
            )
            val reloaded = repo2.awaitInitialLoad()
            assertEquals(false, reloaded.isMasterEnabled)
            assertEquals(750L, reloaded.skipDelayMs)
            assertEquals(setOf("Reklam"), reloaded.customKeywords)
            assertEquals(2, reloaded.skippedReelsCount)

            val config = reloaded.toSkipperConfig()
            assertFalse(config.isMasterEnabled)
            assertEquals(750L, config.skipDelayMs)
            assertEquals(SkipperConfig.DEFAULT_KEYWORDS + "Reklam", config.keywords)

            assertTrue(repo2.removeCustomKeyword("reklam"))
            assertEquals(emptySet(), repo2.settingsFlow.value.customKeywords)
        } finally {
            scope2.cancel()
            scope2.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun `recovers safely to default settings when DataStore file is corrupted`() = runBlocking {
        val corruptedFile = File(tempDir.toFile(), "corrupted_settings.preferences_pb").apply {
            writeBytes(byteArrayOf(0x00, 0x01, 0x7F, -1, -2, 0x13, 0x37))
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = DataStoreSkipperSettingsRepository(
                dataStore = DataStoreSkipperSettingsRepository.createDataStore(scope) { corruptedFile },
                scope = scope,
            )
            val recovered = repo.awaitInitialLoad()
            assertEquals(
                SkipperSettings(
                    isMasterEnabled = true,
                    skipDelayMs = 150L,
                    customKeywords = emptySet(),
                    skippedReelsCount = 0,
                ),
                recovered,
            )

            repo.setSkipDelayMs(600L)
            assertEquals(600L, repo.settingsFlow.value.skipDelayMs)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun `serializes concurrent mutations without stale settingsFlow regressions`() = runBlocking {
        val storeFile = File(tempDir.toFile(), "concurrent_settings.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val observedCounts = mutableListOf<Int>()
        val lock = Any()

        try {
            val repo = DataStoreSkipperSettingsRepository(
                dataStore = DataStoreSkipperSettingsRepository.createDataStore(scope) { storeFile },
                scope = scope,
            )
            val collectorJob = launch(Dispatchers.Unconfined) {
                repo.settingsFlow.collect { settings ->
                    synchronized(lock) {
                        observedCounts.add(settings.skippedReelsCount)
                    }
                }
            }

            try {
                val jobs = (1..20).map {
                    async(Dispatchers.Default) {
                        repo.incrementSkippedReelsCount()
                    }
                } + listOf(
                    async(Dispatchers.Default) { repo.setMasterEnabled(false) },
                    async(Dispatchers.Default) { repo.setSkipDelayMs(900L) },
                )
                jobs.awaitAll()
            } finally {
                collectorJob.cancel()
            }

            val snapshotCounts = synchronized(lock) { observedCounts.toList() }
            for (index in 1 until snapshotCounts.size) {
                assertTrue(
                    snapshotCounts[index] >= snapshotCounts[index - 1],
                    "settingsFlow count regressed in $snapshotCounts",
                )
            }
            assertEquals(20, repo.settingsFlow.value.skippedReelsCount)
            assertFalse(repo.settingsFlow.value.isMasterEnabled)
            assertEquals(900L, repo.settingsFlow.value.skipDelayMs)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun `persists atomic daily counts alongside preserved legacy lifetime count and prunes beyond 12 weeks`() = runBlocking {
        val storeFile = File(tempDir.toFile(), "history_settings.preferences_pb")
        val legacyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val legacyDataStore = DataStoreSkipperSettingsRepository.createDataStore(legacyScope) { storeFile }
            legacyDataStore.edit { prefs ->
                prefs[booleanPreferencesKey("master_enabled")] = false
                prefs[longPreferencesKey("skip_delay_ms")] = 500L
                prefs[stringSetPreferencesKey("custom_keywords")] = setOf("Ad")
                prefs[intPreferencesKey("skipped_reels_count")] = 23
            }
        } finally {
            legacyScope.cancel()
            legacyScope.coroutineContext[Job]?.join()
        }

        val currentMonday = LocalDate.of(2026, 11, 23)
        val currentWednesday = LocalDate.of(2026, 11, 25)
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo1 = DataStoreSkipperSettingsRepository(
                dataStore = DataStoreSkipperSettingsRepository.createDataStore(scope1) { storeFile },
                scope = scope1,
            )
            val loaded = repo1.awaitInitialLoad()
            assertEquals(23, loaded.skippedReelsCount)
            assertEquals(emptyMap(), loaded.dailySkipCounts)
            assertFalse(loaded.isMasterEnabled)
            assertEquals(500L, loaded.skipDelayMs)
            assertEquals(setOf("Ad"), loaded.customKeywords)

            // Record skips spanning 15 past weeks plus 2 days in the active week.
            for (weeksBack in 15 downTo 1) {
                repo1.incrementSkippedReelsCount(currentMonday.minusWeeks(weeksBack.toLong()))
            }
            repo1.incrementSkippedReelsCount(currentMonday)
            repo1.incrementSkippedReelsCount(currentMonday)
            repo1.incrementSkippedReelsCount(currentWednesday)
        } finally {
            scope1.cancel()
            scope1.coroutineContext[Job]?.join()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo2 = DataStoreSkipperSettingsRepository(
                dataStore = DataStoreSkipperSettingsRepository.createDataStore(scope2) { storeFile },
                scope = scope2,
            )
            val reloaded = repo2.awaitInitialLoad()
            assertEquals(41, reloaded.skippedReelsCount)
            assertFalse(reloaded.isMasterEnabled)
            assertEquals(500L, reloaded.skipDelayMs)
            assertEquals(setOf("Ad"), reloaded.customKeywords)
            assertEquals(14, reloaded.dailySkipCounts.size)
            assertEquals(2, reloaded.dailySkipCounts[currentMonday])
            assertEquals(1, reloaded.dailySkipCounts[currentWednesday])
            assertTrue(currentMonday.minusWeeks(12) in reloaded.dailySkipCounts)
            assertTrue(currentMonday.minusWeeks(13) !in reloaded.dailySkipCounts)
            assertTrue(currentMonday.minusWeeks(15) !in reloaded.dailySkipCounts)
        } finally {
            scope2.cancel()
            scope2.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun `recovers fail-safely from malformed or negative daily_skip_counts without losing other settings`() = runBlocking {
        val storeFile = File(tempDir.toFile(), "malformed_history.preferences_pb")
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val seedStore = DataStoreSkipperSettingsRepository.createDataStore(seedScope) { storeFile }
            seedStore.edit { prefs ->
                prefs[booleanPreferencesKey("master_enabled")] = false
                prefs[longPreferencesKey("skip_delay_ms")] = 650L
                prefs[stringSetPreferencesKey("custom_keywords")] = setOf("Reklam")
                prefs[intPreferencesKey("skipped_reels_count")] = 40
                prefs[stringSetPreferencesKey("daily_skip_counts")] = setOf(
                    "2026-11-23=3",
                    "not-a-date=4",
                    "2026-11-22=-5",
                    "2026-02-30=2",
                    "2026-11-21=0",
                    "2026-11-20=abc",
                    "corrupted_entry",
                )
            }
        } finally {
            seedScope.cancel()
            seedScope.coroutineContext[Job]?.join()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = DataStoreSkipperSettingsRepository(
                dataStore = DataStoreSkipperSettingsRepository.createDataStore(scope) { storeFile },
                scope = scope,
            )
            val recovered = repo.awaitInitialLoad()
            assertFalse(recovered.isMasterEnabled)
            assertEquals(650L, recovered.skipDelayMs)
            assertEquals(setOf("Reklam"), recovered.customKeywords)
            assertEquals(40, recovered.skippedReelsCount)
            assertEquals(mapOf(LocalDate.of(2026, 11, 23) to 3), recovered.dailySkipCounts)

            repo.incrementSkippedReelsCount(LocalDate.of(2026, 11, 23))
            assertEquals(41, repo.settingsFlow.value.skippedReelsCount)
            assertEquals(mapOf(LocalDate.of(2026, 11, 23) to 4), repo.settingsFlow.value.dailySkipCounts)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }
}
