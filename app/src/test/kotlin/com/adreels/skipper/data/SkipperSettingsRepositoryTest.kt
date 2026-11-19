package com.adreels.skipper.data

import com.adreels.skipper.domain.KeywordValidationError
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.SkipperConfig
import java.io.File
import java.nio.file.Path
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
                skipDelayMs = 400L,
                customKeywords = emptySet(),
                skippedReelsCount = 0,
            ),
            initial,
        )

        repo1.setMasterEnabled(false)
        repo1.setSkipDelayMs(50L)
        assertEquals(100L, repo1.settingsFlow.value.skipDelayMs)
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
                    skipDelayMs = 400L,
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
}
