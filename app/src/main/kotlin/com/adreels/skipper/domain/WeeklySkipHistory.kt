package com.adreels.skipper.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Aggregated skip count for a single local calendar day (`BR-7`). */
data class DailySkipEntry(
    val date: LocalDate,
    val dayOfWeek: DayOfWeek,
    val count: Int,
    val isToday: Boolean,
)

/** Monday–Sunday skip breakdown covering seven consecutive local calendar days. */
data class WeeklySkipRecord(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val days: List<DailySkipEntry>,
    val totalSkips: Int,
)

/** Current active week and newest-first non-empty archived weeks summary (`AC-2`, `AC-3`). */
data class WeeklySkipSummary(
    val currentWeek: WeeklySkipRecord,
    val archivedWeeks: List<WeeklySkipRecord>,
)

/**
 * Pure domain aggregation, pruning, fail-safe serialization, and deterministic range formatting for
 * local Monday–Sunday weekly skip history (`BR-7`, `INV-1`).
 */
object WeeklySkipAggregator {
    const val MAX_ARCHIVED_WEEKS: Int = 12
    private const val DAYS_IN_WEEK: Long = 7L
    private const val EN_DASH: String = "\u2013"

    private val sameYearFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val withYearFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** Returns the Monday starting the ISO Monday–Sunday calendar week containing [date]. */
    fun weekStartOf(date: LocalDate): LocalDate {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    /**
     * Groups [dailyCounts] into the active `[currentWeekMonday .. currentWeekSunday]` breakdown for
     * [today] and newest-first archived past weeks (`weekStart < currentWeekMonday`, `totalSkips > 0`).
     * Non-positive counts and strictly-future dates (`> currentWeekSunday`) are ignored.
     */
    fun summarize(
        dailyCounts: Map<LocalDate, Int>,
        today: LocalDate,
        maxArchivedWeeks: Int = MAX_ARCHIVED_WEEKS,
    ): WeeklySkipSummary {
        val currentWeekStart = weekStartOf(today)
        val currentWeekEnd = currentWeekStart.plusDays(DAYS_IN_WEEK - 1L)
        val validEntries = dailyCounts.filter { (date, count) ->
            count > 0 && !date.isAfter(currentWeekEnd)
        }
        val currentWeek = buildWeekRecord(
            weekStart = currentWeekStart,
            countsByDate = validEntries,
            today = today,
        )
        val archiveLimit = maxArchivedWeeks.coerceAtLeast(0)
        val archivedWeeks = validEntries.entries
            .filter { (date, _) -> date.isBefore(currentWeekStart) }
            .groupBy { (date, _) -> weekStartOf(date) }
            .entries
            .sortedByDescending { (weekStart, _) -> weekStart }
            .map { (weekStart, entries) ->
                buildWeekRecord(
                    weekStart = weekStart,
                    countsByDate = entries.associate { it.toPair() },
                    today = null,
                )
            }
            .filter { it.totalSkips > 0 }
            .take(archiveLimit)
        return WeeklySkipSummary(
            currentWeek = currentWeek,
            archivedWeeks = archivedWeeks,
        )
    }

    /**
     * Retains positive counts within the active week containing [referenceDate] plus up to
     * [maxArchivedWeeks] newest non-empty archived weeks strictly before it, dropping older weeks,
     * non-positive entries, and strictly-future dates (`> currentWeekSunday`).
     */
    fun prune(
        dailyCounts: Map<LocalDate, Int>,
        referenceDate: LocalDate,
        maxArchivedWeeks: Int = MAX_ARCHIVED_WEEKS,
    ): Map<LocalDate, Int> {
        val currentWeekStart = weekStartOf(referenceDate)
        val currentWeekEnd = currentWeekStart.plusDays(DAYS_IN_WEEK - 1L)
        val validEntries = dailyCounts.filter { (date, count) ->
            count > 0 && !date.isAfter(currentWeekEnd)
        }
        val archiveLimit = maxArchivedWeeks.coerceAtLeast(0)
        val retainedArchiveStarts = validEntries.keys
            .filter { it.isBefore(currentWeekStart) }
            .map(::weekStartOf)
            .distinct()
            .sortedDescending()
            .take(archiveLimit)
            .toSet()
        return validEntries.filter { (date, _) ->
            !date.isBefore(currentWeekStart) || weekStartOf(date) in retainedArchiveStarts
        }
    }

    /** Serializes positive daily counts deterministically as `"YYYY-MM-DD=count"` entries. */
    fun serialize(dailyCounts: Map<LocalDate, Int>): Set<String> {
        return dailyCounts.entries
            .filter { (_, count) -> count > 0 }
            .sortedBy { (date, _) -> date }
            .mapTo(linkedSetOf()) { (date, count) -> "$date=$count" }
    }

    /**
     * Fail-safely parses persisted `"YYYY-MM-DD=count"` strings, ignoring malformed lines,
     * invalid calendar dates, non-numeric values, and non-positive counts.
     */
    fun deserialize(rawEntries: Set<String>): Map<LocalDate, Int> {
        val parsed = sortedMapOf<LocalDate, Int>()
        for (rawEntry in rawEntries) {
            val (date, count) = parseSerializedEntry(rawEntry) ?: continue
            val existing = parsed[date] ?: 0
            parsed[date] = maxOf(existing, count)
        }
        return parsed.toMap()
    }

    /** Formats a Monday–Sunday week range deterministically using US English short month labels. */
    fun formatWeekRange(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        referenceYear: Int = weekEnd.year,
    ): String {
        return when {
            weekStart.year != weekEnd.year -> {
                "${weekStart.format(withYearFormatter)} $EN_DASH ${weekEnd.format(withYearFormatter)}"
            }
            weekStart.year != referenceYear -> {
                "${weekStart.format(sameYearFormatter)} $EN_DASH ${weekEnd.format(withYearFormatter)}"
            }
            else -> {
                "${weekStart.format(sameYearFormatter)} $EN_DASH ${weekEnd.format(sameYearFormatter)}"
            }
        }
    }

    private fun buildWeekRecord(
        weekStart: LocalDate,
        countsByDate: Map<LocalDate, Int>,
        today: LocalDate?,
    ): WeeklySkipRecord {
        val days = (0L until DAYS_IN_WEEK).map { offset ->
            val date = weekStart.plusDays(offset)
            DailySkipEntry(
                date = date,
                dayOfWeek = date.dayOfWeek,
                count = (countsByDate[date] ?: 0).coerceAtLeast(0),
                isToday = today != null && date == today,
            )
        }
        return WeeklySkipRecord(
            weekStart = weekStart,
            weekEnd = weekStart.plusDays(DAYS_IN_WEEK - 1L),
            days = days,
            totalSkips = days.sumOf { it.count },
        )
    }

    private fun parseSerializedEntry(rawEntry: String): Pair<LocalDate, Int>? {
        val parts = rawEntry.trim().split('=')
        if (parts.size != 2) return null
        val dateText = parts[0].trim()
        val countText = parts[1].trim()
        val date = try {
            LocalDate.parse(dateText)
        } catch (_: DateTimeParseException) {
            return null
        }
        val count = countText.toIntOrNull() ?: return null
        if (count <= 0) return null
        return date to count
    }
}
