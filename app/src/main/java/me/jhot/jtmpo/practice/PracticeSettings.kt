package me.jhot.jtmpo.practice

import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TempoLimits
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.domain.patternsFor
import me.jhot.jtmpo.domain.tempoUnit
import kotlin.math.floor
import kotlin.math.round

enum class RampDirection { Down, Off, Up }

object PracticeLimits {
    val STEP = 1..50
    val BARS = 1..64
}

data class PracticeSettings(
    val startTempo: Int = 60,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val pattern: ClickPattern = ClickPattern(4),
    val direction: RampDirection = RampDirection.Off,
    val stepBpm: Int = 5,
    val everyBars: Int = 8,
    val flipped: Boolean = false,
) {
    fun sanitized(): PracticeSettings {
        val ts = if (timeSignature.denominator in SUPPORTED_DENOMINATORS) timeSignature else DEFAULT_SIGNATURE
        val offered = patternsFor(ts)
        val pattern = if (pattern in offered) pattern else offered.first()
        return copy(
            startTempo = startTempo.coerceIn(TempoLimits.MIN, maxPracticeTempo(ts, pattern)),
            timeSignature = ts,
            pattern = pattern,
            stepBpm = stepBpm.coerceIn(PracticeLimits.STEP),
            everyBars = everyBars.coerceIn(PracticeLimits.BARS),
        )
    }
}

private val SUPPORTED_DENOMINATORS = listOf(2, 4, 8, 16)
private val DEFAULT_SIGNATURE = TimeSignature(4, 4)

/** Clicks the pattern puts in one bar of [ts], at least one. */
fun clicksPerBar(ts: TimeSignature, pattern: ClickPattern): Int =
    round((ts.numerator.toDouble() / ts.denominator) / pattern.wholeNotes).toInt().coerceAtLeast(1)

/** Fastest tempo whose clicks stay under [TempoLimits.MAX_CPM]. */
fun maxPracticeTempo(ts: TimeSignature, pattern: ClickPattern): Int =
    floor(TempoLimits.MAX_CPM * pattern.wholeNotes / ts.tempoUnit.wholeNotes)
        .toInt()
        .coerceIn(TempoLimits.MIN, TempoLimits.MAX)
