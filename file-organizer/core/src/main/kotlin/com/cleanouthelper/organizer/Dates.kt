package com.cleanouthelper.organizer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Finds dates in file names and in document text. */
object Dates {
    private val compact = Regex("(?<![0-9])((?:19|20)[0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])(?:[-_ T.]?([01][0-9]|2[0-3])([0-5][0-9])([0-5][0-9])?)?(?![0-9])")
    private val separated = Regex("(?<![0-9])((?:19|20)[0-9]{2})[-_.]([01]?[0-9])[-_.]([0-3]?[0-9])(?:[-_ T.at]+([01]?[0-9]|2[0-3])[-_.:h]([0-5][0-9])(?:[-_.:m]([0-5][0-9]))?)?(?![0-9])")
    private val unixMillis = Regex("(?<![0-9])(1[3-9][0-9]{11})(?![0-9])")

    /** Reads a date from names like IMG_20240512_143022, Screenshot_2024-05-12-14-30-22, 1715524222000.jpg. */
    fun fromFileName(name: String): LocalDateTime? {
        compact.find(name)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            return build(y, mo, d, h, mi, s)
        }
        separated.find(name)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            return build(y, mo, d, h, mi, s)
        }
        unixMillis.find(name)?.let { m ->
            val millis = m.groupValues[1].toLong()
            val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
            if (dt.year in 2010..LocalDate.now().year) return dt
        }
        return null
    }

    private fun build(y: String, mo: String, d: String, h: String, mi: String, s: String): LocalDateTime? = try {
        val date = LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
        if (date.year < 1990 || date.isAfter(LocalDate.now().plusDays(1))) null
        else {
            val time = if (h.isNotEmpty() && mi.isNotEmpty()) LocalTime.of(h.toInt(), mi.toInt(), s.ifEmpty { "0" }.toInt()) else LocalTime.NOON
            LocalDateTime.of(date, time)
        }
    } catch (e: Exception) {
        null
    }

    private val monthNames: Map<String, Int> = buildMap {
        Month.values().forEach { m ->
            val full = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
            put(full, m.value)
            put(full.take(3), m.value)
        }
        put("sept", 9)
    }
    private val monthAlternation = monthNames.keys.sortedByDescending { it.length }.joinToString("|")
    private val textIso = Regex("(?<![0-9])((?:19|20)[0-9]{2})-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])(?![0-9])")
    private val textMonthFirst = Regex("(?i)\\b($monthAlternation)\\.?\\s+([0-3]?[0-9])(?:st|nd|rd|th)?,?\\s+((?:19|20)[0-9]{2})\\b")
    private val textDayFirst = Regex("(?i)\\b([0-3]?[0-9])(?:st|nd|rd|th)?\\s+($monthAlternation)\\.?,?\\s+((?:19|20)[0-9]{2})\\b")
    private val textSlashes = Regex("(?<![0-9/])([0-3]?[0-9])[/.]([0-3]?[0-9])[/.]((?:19|20)[0-9]{2})(?![0-9/])")

    /**
     * The first clear date written in a document ("May 12, 2024", "12 May 2024", "2024-05-12", "05/12/2024").
     * For 05/12/2024 style dates, US order (month first) is used unless the phone is set to a
     * day-first country or the first number is over 12.
     */
    fun fromText(text: String, locale: Locale = Locale.getDefault()): LocalDate? {
        val head = text.take(4000)
        val candidates = mutableListOf<Pair<Int, LocalDate>>()
        fun add(index: Int, y: Int, m: Int, d: Int) {
            try {
                val date = LocalDate.of(y, m, d)
                if (date.year >= 1990 && !date.isAfter(LocalDate.now().plusYears(1))) candidates += index to date
            } catch (_: Exception) {
            }
        }
        textIso.findAll(head).forEach { add(it.range.first, it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        textMonthFirst.findAll(head).forEach {
            add(it.range.first, it.groupValues[3].toInt(), monthNames.getValue(it.groupValues[1].lowercase()), it.groupValues[2].toInt())
        }
        textDayFirst.findAll(head).forEach {
            add(it.range.first, it.groupValues[3].toInt(), monthNames.getValue(it.groupValues[2].lowercase()), it.groupValues[1].toInt())
        }
        val monthFirstCountry = locale.country in setOf("US", "PH", "FM", "MH", "PW", "CA", "")
        textSlashes.findAll(head).forEach {
            val a = it.groupValues[1].toInt()
            val b = it.groupValues[2].toInt()
            val y = it.groupValues[3].toInt()
            val (m, d) = when {
                a > 12 -> b to a
                b > 12 -> a to b
                monthFirstCountry -> a to b
                else -> b to a
            }
            add(it.range.first, y, m, d)
        }
        return candidates.minByOrNull { it.first }?.second
    }

    fun monthFolder(d: LocalDateTime): String =
        "%02d - %s".format(d.monthValue, d.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.titlecase() })
}
