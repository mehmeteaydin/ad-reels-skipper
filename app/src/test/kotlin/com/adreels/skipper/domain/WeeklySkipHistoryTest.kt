package com.adreels.skipper.domain

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WeeklySkipHistoryTest {

    @Test
    fun `weekStartOf resolves Monday for Monday mid-week Sunday and cross-year boundaries`() {
        assertEquals(LocalDate.of(2026, 11, 23), WeeklySkipAggregator.weekStartOf(LocalDate.of(2026, 11, 23)))
        assertEquals(LocalDate.of(2026, 11, 23), WeeklySkipAggregator.weekStartOf(LocalDate.of(2026, 11, 25)))
        assertEquals(LocalDate.of(2026, 11, 23), WeeklySkipAggregator.weekStartOf(LocalDate.of(2026, 11, 29)))
        assertEquals(LocalDate.of(2026, 12, 28), WeeklySkipAggregator.weekStartOf(LocalDate.of(2027, 1, 3)))
    }

    @Test
    fun `summarize returns zero-filled Monday through Sunday active week with today highlight when empty`() {
        val wednesday = LocalDate.of(2026, 11, 25)
        val summary = WeeklySkipAggregator.summarize(emptyMap(), today = wednesday)

        assertEquals(LocalDate.of(2026, 11, 23), summary.currentWeek.weekStart)
        assertEquals(LocalDate.of(2026, 11, 29), summary.currentWeek.weekEnd)
        assertEquals(0, summary.currentWeek.totalSkips)
        assertEquals(DayOfWeek.entries, summary.currentWeek.days.map { it.dayOfWeek })
        assertTrue(summary.currentWeek.days.all { it.count == 0 })
        assertEquals(listOf(wednesday), summary.currentWeek.days.filter { it.isToday }.map { it.date })
        assertTrue(summary.archivedWeeks.isEmpty())
    }

    @Test
    fun `summarize aggregates current week rolls over cleanly on Monday excludes zero-skip gap weeks and ignores future dates`() {
        val counts = mapOf(
            LocalDate.of(2026, 11, 4) to 2, // Two weeks prior (Nov 2 – Nov 8)
            LocalDate.of(2026, 11, 16) to 4, // Previous week Mon
            LocalDate.of(2026, 11, 22) to 5, // Previous week Sun (Nov 16 – Nov 22 -> total 9)
            LocalDate.of(2026, 11, 23) to 3, // Active week Mon
            LocalDate.of(2026, 11, 26) to 7, // Active week Thu
            LocalDate.of(2026, 12, 10) to 99, // Strictly-future week after clock rollback -> ignored
        )

        val midWeekToday = LocalDate.of(2026, 11, 26)
        val activeSummary = WeeklySkipAggregator.summarize(counts, today = midWeekToday)

        assertEquals(LocalDate.of(2026, 11, 23), activeSummary.currentWeek.weekStart)
        assertEquals(10, activeSummary.currentWeek.totalSkips)
        assertEquals(listOf(3, 0, 0, 7, 0, 0, 0), activeSummary.currentWeek.days.map { it.count })
        assertEquals(listOf(midWeekToday), activeSummary.currentWeek.days.filter { it.isToday }.map { it.date })

        // Archived weeks strictly before currentWeekMonday (excluding empty Nov 9 – Nov 15 gap week), newest-first.
        assertEquals(
            listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 2)),
            activeSummary.archivedWeeks.map { it.weekStart },
        )
        assertEquals(listOf(9, 2), activeSummary.archivedWeeks.map { it.totalSkips })
        assertTrue(activeSummary.archivedWeeks.all { week -> week.days.none { it.isToday } })

        // On Monday Nov 30, the active week resets to all zeroes and Nov 23 – Nov 29 moves into archivedWeeks[0].
        val nextMonday = LocalDate.of(2026, 11, 30)
        val rolledSummary = WeeklySkipAggregator.summarize(counts, today = nextMonday)
        assertEquals(nextMonday, rolledSummary.currentWeek.weekStart)
        assertEquals(0, rolledSummary.currentWeek.totalSkips)
        assertTrue(rolledSummary.currentWeek.days.all { it.count == 0 })
        assertEquals(
            listOf(
                LocalDate.of(2026, 11, 23),
                LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 2),
            ),
            rolledSummary.archivedWeeks.map { it.weekStart },
        )
    }

    @Test
    fun `prune retains active week plus 12 newest non-empty archived weeks and drops older or future entries`() {
        val currentMonday = LocalDate.of(2026, 11, 23)
        val currentWednesday = LocalDate.of(2026, 11, 25)
        val counts = linkedMapOf<LocalDate, Int>()

        // Seed 15 archived weeks (1..15 weeks back) plus an empty gap week (week back 16 with 0) and active week.
        for (weeksBack in 1..15) {
            counts[currentMonday.minusWeeks(weeksBack.toLong()).plusDays(2)] = weeksBack
        }
        counts[currentMonday.minusWeeks(16)] = 0
        counts[currentMonday] = 4
        counts[currentWednesday] = 6
        counts[currentMonday.plusWeeks(1)] = 15 // Strictly after currentWeekSunday

        val pruned = WeeklySkipAggregator.prune(counts, referenceDate = currentWednesday)
        val expectedOldestKept = currentMonday.minusWeeks(12).plusDays(2)

        assertEquals(4, pruned[currentMonday])
        assertEquals(6, pruned[currentWednesday])
        assertTrue(expectedOldestKept in pruned)
        assertTrue(currentMonday.minusWeeks(13).plusDays(2) !in pruned)
        assertTrue(currentMonday.minusWeeks(15).plusDays(2) !in pruned)
        assertTrue(currentMonday.minusWeeks(16) !in pruned)
        assertTrue(currentMonday.plusWeeks(1) !in pruned)

        val summary = WeeklySkipAggregator.summarize(pruned, today = currentWednesday)
        assertEquals(12, summary.archivedWeeks.size)
        assertEquals(currentMonday.minusWeeks(1), summary.archivedWeeks.first().weekStart)
        assertEquals(currentMonday.minusWeeks(12), summary.archivedWeeks.last().weekStart)
    }

    @Test
    fun `prune preserves 12 non-empty archived weeks even when empty gap weeks exist between them`() {
        val currentMonday = LocalDate.of(2026, 11, 23)
        val counts = (1..14).associate { step ->
            currentMonday.minusWeeks((step * 2).toLong()) to step
        } + (currentMonday to 3)

        val pruned = WeeklySkipAggregator.prune(counts, referenceDate = currentMonday)
        val summary = WeeklySkipAggregator.summarize(pruned, today = currentMonday)

        assertEquals(12, summary.archivedWeeks.size)
        assertEquals(currentMonday.minusWeeks(2), summary.archivedWeeks.first().weekStart)
        assertEquals(currentMonday.minusWeeks(24), summary.archivedWeeks.last().weekStart)
    }

    @Test
    fun `serialize and deserialize round-trip valid entries and ignore malformed or negative entries`() {
        val original = mapOf(
            LocalDate.of(2026, 11, 23) to 5,
            LocalDate.of(2026, 11, 24) to 2,
            LocalDate.of(2026, 11, 20) to 0,
            LocalDate.of(2026, 11, 19) to -3,
        )
        val encoded = WeeklySkipAggregator.serialize(original)
        assertEquals(setOf("2026-11-23=5", "2026-11-24=2"), encoded)

        val corrupted = encoded + setOf(
            "not-a-date=4",
            "2026-02-31=2",
            "2026-11-25=-1",
            "2026-11-26=0",
            "2026-11-27=abc",
            "missing-separator",
            "2026-11-28=1=extra",
            "+999999999-12-31=1",
            "-999999999-01-01=2",
            "+2026-11-2=1",
            "2026-11-23=9",
        )
        val decoded = WeeklySkipAggregator.deserialize(corrupted)
        assertEquals(
            mapOf(
                LocalDate.of(2026, 11, 23) to 9,
                LocalDate.of(2026, 11, 24) to 2,
            ),
            decoded,
        )
        val afterCorruptedSummary = WeeklySkipAggregator.summarize(decoded, today = LocalDate.of(2026, 11, 25))
        assertEquals(11, afterCorruptedSummary.currentWeek.totalSkips)
        assertEquals(decoded, WeeklySkipAggregator.prune(decoded, referenceDate = LocalDate.of(2026, 11, 25)))
    }

    @Test
    fun `summarize saturates weekly totalSkips at Int MAX_VALUE without signed overflow`() {
        val counts = mapOf(
            LocalDate.of(2026, 11, 16) to Int.MAX_VALUE,
            LocalDate.of(2026, 11, 17) to 5,
            LocalDate.of(2026, 11, 23) to Int.MAX_VALUE,
            LocalDate.of(2026, 11, 24) to 1,
        )
        val summary = WeeklySkipAggregator.summarize(counts, today = LocalDate.of(2026, 11, 25))

        assertEquals(Int.MAX_VALUE, summary.currentWeek.totalSkips)
        assertEquals(1, summary.archivedWeeks.size)
        assertEquals(Int.MAX_VALUE, summary.archivedWeeks.single().totalSkips)
    }

    @Test
    fun `formatWeekRange handles same-year cross-year and prior-year intervals deterministically`() {
        assertEquals(
            "Nov 23 \u2013 Nov 29",
            WeeklySkipAggregator.formatWeekRange(
                weekStart = LocalDate.of(2026, 11, 23),
                weekEnd = LocalDate.of(2026, 11, 29),
                referenceYear = 2026,
            ),
        )
        assertEquals(
            "Dec 28, 2026 \u2013 Jan 3, 2027",
            WeeklySkipAggregator.formatWeekRange(
                weekStart = LocalDate.of(2026, 12, 28),
                weekEnd = LocalDate.of(2027, 1, 3),
                referenceYear = 2027,
            ),
        )
        assertEquals(
            "Nov 17 \u2013 Nov 23, 2025",
            WeeklySkipAggregator.formatWeekRange(
                weekStart = LocalDate.of(2025, 11, 17),
                weekEnd = LocalDate.of(2025, 11, 23),
                referenceYear = 2026,
            ),
        )
    }
}
