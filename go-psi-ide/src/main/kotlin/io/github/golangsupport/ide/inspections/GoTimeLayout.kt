package io.github.golangsupport.ide.inspections

import java.time.LocalDate

/**
 * Go time layouts (`time.Time.Format`, `time.Parse`): the reference time `Mon Jan 2 15:04:05 MST 2006` and its elements, split the way
 * `nextStdChunk` of time/format.go does (longest match first, `Jan` not before a lower case letter, `_2006` is `_` and `2006`),
 * a rendering of a fixed [SAMPLE] for the inlay hint, and the foreign notation (`yyyy-MM-dd`) the inspection converts. Pure: no PSI.
 */
object GoTimeLayout {

    /** A moment to render a layout with: the fields of a `time.Time` in a fixed zone. */
    class Sample(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int, val nano: Int, val offsetSeconds: Int, val zone: String) {
        val date: LocalDate get() = LocalDate.of(year, month, day)
    }

    /**
     * Saturday 2026-03-07 15:09:08.123456789 +03:00 MSK: the day (7) differs from the month (3), the hour is above 12 (15 shows as 3 PM),
     * minute and second differ from each other and from the reference ones, and the fraction has no trailing zero.
     */
    val SAMPLE = Sample(2026, 3, 7, 15, 9, 8, 123_456_789, 3 * 3600, "MSK")

    /** The elements of a layout (the `std*` codes of time/format.go). */
    enum class Std {
        LONG_MONTH, MONTH, NUM_MONTH, ZERO_MONTH, LONG_WEEKDAY, WEEKDAY, DAY, UNDER_DAY, ZERO_DAY, UNDER_YEAR_DAY, ZERO_YEAR_DAY,
        HOUR, HOUR12, ZERO_HOUR12, MINUTE, ZERO_MINUTE, SECOND, ZERO_SECOND, LONG_YEAR, YEAR, PM, LOWER_PM, TZ,
        ISO8601_TZ, ISO8601_SECONDS_TZ, ISO8601_SHORT_TZ, ISO8601_COLON_TZ, ISO8601_COLON_SECONDS_TZ,
        NUM_TZ, NUM_SECONDS_TZ, NUM_SHORT_TZ, NUM_COLON_TZ, NUM_COLON_SECONDS_TZ, FRAC0, FRAC9,
    }

    /** A piece of a layout: literal [text] ([std] null), or an element with the source [text] it was written as; fractions know their digits and separator. */
    class Chunk(val text: String, val std: Std?, val digits: Int = 0, val separator: Char = '.') {
        val isElement: Boolean get() = std != null
    }

    /** [layout] split into literal and element chunks. */
    fun chunks(layout: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        var rest = layout
        while (rest.isNotEmpty()) {
            val next = nextStdChunk(rest)
            if (next == null) {
                out += Chunk(rest, null)
                break
            }
            if (next.prefix.isNotEmpty()) out += Chunk(next.prefix, null)
            out += next.chunk
            rest = next.suffix
        }
        return out
    }

    /** How many elements [layout] has. */
    fun elementCount(layout: String): Int = chunks(layout).count { it.isElement }

    /** [layout] formatted with [sample], as `Time.Format` would. */
    fun render(layout: String, sample: Sample = SAMPLE): String = buildString {
        for (c in chunks(layout)) if (c.std == null) append(c.text) else append(format(c, sample))
    }

    private class Next(val prefix: String, val chunk: Chunk, val suffix: String)

    private fun startsWithLowerCase(s: String): Boolean = s.isNotEmpty() && s[0] in 'a'..'z'

    private fun isDigit(s: String, i: Int): Boolean = i < s.length && s[i] in '0'..'9'

    private fun nextStdChunk(layout: String): Next? {
        fun at(i: Int, std: Std, len: Int, digits: Int = 0, sep: Char = '.') =
            Next(layout.substring(0, i), Chunk(layout.substring(i, i + len), std, digits, sep), layout.substring(i + len))
        for (i in layout.indices) {
            val c = layout[i]
            when (c) {
                'J' -> if (layout.startsWith("Jan", i)) {
                    if (layout.startsWith("January", i)) return at(i, Std.LONG_MONTH, 7)
                    if (!startsWithLowerCase(layout.substring(i + 3))) return at(i, Std.MONTH, 3)
                }
                'M' -> {
                    if (layout.startsWith("Mon", i)) {
                        if (layout.startsWith("Monday", i)) return at(i, Std.LONG_WEEKDAY, 6)
                        if (!startsWithLowerCase(layout.substring(i + 3))) return at(i, Std.WEEKDAY, 3)
                    }
                    if (layout.startsWith("MST", i)) return at(i, Std.TZ, 3)
                }
                '0' -> {
                    if (i + 1 < layout.length && layout[i + 1] in '1'..'6') {
                        val std = listOf(Std.ZERO_MONTH, Std.ZERO_DAY, Std.ZERO_HOUR12, Std.ZERO_MINUTE, Std.ZERO_SECOND, Std.YEAR)[layout[i + 1] - '1']
                        return at(i, std, 2)
                    }
                    if (layout.startsWith("002", i)) return at(i, Std.ZERO_YEAR_DAY, 3)
                }
                '1' -> return if (i + 1 < layout.length && layout[i + 1] == '5') at(i, Std.HOUR, 2) else at(i, Std.NUM_MONTH, 1)
                '2' -> return if (layout.startsWith("2006", i)) at(i, Std.LONG_YEAR, 4) else at(i, Std.DAY, 1)
                '_' -> {
                    if (i + 1 < layout.length && layout[i + 1] == '2') {
                        // `_2006` is a literal `_` followed by the year
                        if (layout.startsWith("2006", i + 1)) return Next(layout.substring(0, i + 1), Chunk("2006", Std.LONG_YEAR), layout.substring(i + 5))
                        return at(i, Std.UNDER_DAY, 2)
                    }
                    if (layout.startsWith("__2", i)) return at(i, Std.UNDER_YEAR_DAY, 3)
                }
                '3' -> return at(i, Std.HOUR12, 1)
                '4' -> return at(i, Std.MINUTE, 1)
                '5' -> return at(i, Std.SECOND, 1)
                'P' -> if (layout.startsWith("PM", i)) return at(i, Std.PM, 2)
                'p' -> if (layout.startsWith("pm", i)) return at(i, Std.LOWER_PM, 2)
                '-' -> {
                    if (layout.startsWith("-070000", i)) return at(i, Std.NUM_SECONDS_TZ, 7)
                    if (layout.startsWith("-07:00:00", i)) return at(i, Std.NUM_COLON_SECONDS_TZ, 9)
                    if (layout.startsWith("-0700", i)) return at(i, Std.NUM_TZ, 5)
                    if (layout.startsWith("-07:00", i)) return at(i, Std.NUM_COLON_TZ, 6)
                    if (layout.startsWith("-07", i)) return at(i, Std.NUM_SHORT_TZ, 3)
                }
                'Z' -> {
                    if (layout.startsWith("Z070000", i)) return at(i, Std.ISO8601_SECONDS_TZ, 7)
                    if (layout.startsWith("Z07:00:00", i)) return at(i, Std.ISO8601_COLON_SECONDS_TZ, 9)
                    if (layout.startsWith("Z0700", i)) return at(i, Std.ISO8601_TZ, 5)
                    if (layout.startsWith("Z07:00", i)) return at(i, Std.ISO8601_COLON_TZ, 6)
                    if (layout.startsWith("Z07", i)) return at(i, Std.ISO8601_SHORT_TZ, 3)
                }
                '.', ',' -> if (i + 1 < layout.length && (layout[i + 1] == '0' || layout[i + 1] == '9')) {
                    val ch = layout[i + 1]
                    var j = i + 1
                    while (j < layout.length && layout[j] == ch) j++
                    // the digits must end here: `.0001` is not a fraction
                    if (!isDigit(layout, j)) return at(i, if (ch == '0') Std.FRAC0 else Std.FRAC9, j - i, j - (i + 1), c)
                }
            }
        }
        return null
    }

    private fun pad(n: Int, width: Int, fill: Char = '0'): String = n.toString().padStart(width, fill)

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private fun format(chunk: Chunk, s: Sample): String {
        val hour12 = if (s.hour % 12 == 0) 12 else s.hour % 12
        val yearDay = s.date.dayOfYear
        val weekday = DAYS[s.date.dayOfWeek.value - 1]
        val utc = s.offsetSeconds == 0
        return when (chunk.std!!) {
            Std.LONG_YEAR -> pad(s.year, 4)
            Std.YEAR -> pad(s.year % 100, 2)
            Std.LONG_MONTH -> MONTHS[s.month - 1]
            Std.MONTH -> MONTHS[s.month - 1].substring(0, 3)
            Std.NUM_MONTH -> s.month.toString()
            Std.ZERO_MONTH -> pad(s.month, 2)
            Std.LONG_WEEKDAY -> weekday
            Std.WEEKDAY -> weekday.substring(0, 3)
            Std.DAY -> s.day.toString()
            Std.UNDER_DAY -> pad(s.day, 2, ' ')
            Std.ZERO_DAY -> pad(s.day, 2)
            Std.UNDER_YEAR_DAY -> pad(yearDay, 3, ' ')
            Std.ZERO_YEAR_DAY -> pad(yearDay, 3)
            Std.HOUR -> pad(s.hour, 2)
            Std.HOUR12 -> hour12.toString()
            Std.ZERO_HOUR12 -> pad(hour12, 2)
            Std.MINUTE -> s.minute.toString()
            Std.ZERO_MINUTE -> pad(s.minute, 2)
            Std.SECOND -> s.second.toString()
            Std.ZERO_SECOND -> pad(s.second, 2)
            Std.PM -> if (s.hour >= 12) "PM" else "AM"
            Std.LOWER_PM -> if (s.hour >= 12) "pm" else "am"
            Std.TZ -> s.zone.ifEmpty { offset(s.offsetSeconds, colon = false, withSeconds = false, short = true) }
            Std.ISO8601_TZ -> if (utc) "Z" else offset(s.offsetSeconds, colon = false, withSeconds = false, short = false)
            Std.ISO8601_SECONDS_TZ -> if (utc) "Z" else offset(s.offsetSeconds, colon = false, withSeconds = true, short = false)
            Std.ISO8601_SHORT_TZ -> if (utc) "Z" else offset(s.offsetSeconds, colon = false, withSeconds = false, short = true)
            Std.ISO8601_COLON_TZ -> if (utc) "Z" else offset(s.offsetSeconds, colon = true, withSeconds = false, short = false)
            Std.ISO8601_COLON_SECONDS_TZ -> if (utc) "Z" else offset(s.offsetSeconds, colon = true, withSeconds = true, short = false)
            Std.NUM_TZ -> offset(s.offsetSeconds, colon = false, withSeconds = false, short = false)
            Std.NUM_SECONDS_TZ -> offset(s.offsetSeconds, colon = false, withSeconds = true, short = false)
            Std.NUM_SHORT_TZ -> offset(s.offsetSeconds, colon = false, withSeconds = false, short = true)
            Std.NUM_COLON_TZ -> offset(s.offsetSeconds, colon = true, withSeconds = false, short = false)
            Std.NUM_COLON_SECONDS_TZ -> offset(s.offsetSeconds, colon = true, withSeconds = true, short = false)
            Std.FRAC0 -> chunk.separator + fraction(s.nano, chunk.digits).padEnd(chunk.digits, '0')
            Std.FRAC9 -> fraction(s.nano, chunk.digits).trimEnd('0').let { if (it.isEmpty()) "" else chunk.separator + it }
        }
    }

    /** The first [digits] digits of the nanoseconds (the fraction is truncated, not rounded). */
    private fun fraction(nano: Int, digits: Int): String = pad(nano, 9).take(minOf(digits, 9))

    private fun offset(seconds: Int, colon: Boolean, withSeconds: Boolean, short: Boolean): String {
        val abs = Math.abs(seconds)
        val sb = StringBuilder(if (seconds < 0) "-" else "+")
        sb.append(pad(abs / 3600, 2))
        if (short) return sb.toString()
        if (colon) sb.append(':')
        sb.append(pad(abs % 3600 / 60, 2))
        if (withSeconds) {
            if (colon) sb.append(':')
            sb.append(pad(abs % 60, 2))
        }
        return sb.toString()
    }

    // --- layouts written in the notation of other languages ---

    /**
     * The Go layout for [layout] written as `yyyy-MM-dd HH:mm:ss` (Java, .NET, moment: `yyyy`/`YYYY`, `yy`, `MM`, `dd`/`DD`, `HH`, `hh`,
     * `mm`, `ss`, `SSS` after a dot), or null when it holds no such token or already holds a Go element: a mix of both is not guessed at.
     */
    fun foreignToGo(layout: String): String? {
        if (elementCount(layout) > 0) return null
        val sb = StringBuilder()
        var converted = false
        var i = 0
        while (i < layout.length) {
            val c = layout[i]
            var j = i
            while (j < layout.length && layout[j] == c) j++
            val run = j - i
            val go = when {
                (c == 'y' || c == 'Y') && run == 4 -> "2006"
                c == 'y' && run == 2 -> "06"
                c == 'M' && run == 2 -> "01"
                (c == 'd' || c == 'D') && run == 2 -> "02"
                c == 'H' && run == 2 -> "15"
                c == 'h' && run == 2 -> "03"
                c == 'm' && run == 2 -> "04"
                c == 's' && run == 2 -> "05"
                c == 'S' && run == 3 && i > 0 && (layout[i - 1] == '.' || layout[i - 1] == ',') -> "000"
                else -> null
            }
            if (go != null) {
                sb.append(go)
                converted = true
            } else sb.append(layout, i, j)
            i = j
        }
        return if (converted) sb.toString() else null
    }

    /** Whether [layout] is the ISO date with the day before the month: `2006-02-01`, alone or followed by a space or `T` and the rest. */
    fun isSwappedIsoDate(layout: String): Boolean =
        layout.startsWith("2006-02-01") && (layout.length == 10 || layout[10] == ' ' || layout[10] == 'T')
}
