package me.jhot.jtmpo.domain

data class TimeSignature(val numerator: Int, val denominator: Int) {
    override fun toString() = "$numerator/$denominator"
}

data class ClickPattern(val noteDenominator: Int, val dotted: Boolean = false) {
    /** Length of one click's note in whole notes. */
    internal val wholeNotes: Double
        get() = (1.0 / noteDenominator) * (if (dotted) 1.5 else 1.0)
}

data class Rhythm(val tempo: Int, val timeSignature: TimeSignature, val pattern: ClickPattern)

object TempoLimits {
    const val MIN = 20
    const val MAX = 300
    const val MAX_CPM = 600.0
}

val COMMON_SIGNATURES: List<TimeSignature> = listOf(
    TimeSignature(4, 4),
    TimeSignature(3, 4),
    TimeSignature(6, 8),
    TimeSignature(5, 4),
    TimeSignature(2, 4),
    TimeSignature(12, 8),
    TimeSignature(7, 8),
)

/** 6, 9 or 12 over 8 or 16: the beat is a dotted note grouping three of the bottom note. */
val TimeSignature.isCompound: Boolean
    get() = denominator in listOf(8, 16) && numerator in listOf(6, 9, 12)

/** The note tempo counts: the dotted beat in compound meters, else the bottom-number note. */
val TimeSignature.tempoUnit: ClickPattern
    get() = if (isCompound) ClickPattern(denominator / 2, dotted = true) else ClickPattern(denominator)

/** Tempo counts [tempoUnit]s; the pattern scales that into clicks. */
fun Rhythm.clicksPerMinute(): Double =
    minOf(tempo * timeSignature.tempoUnit.wholeNotes / pattern.wholeNotes, TempoLimits.MAX_CPM)

fun patternsFor(ts: TimeSignature): List<ClickPattern> = when (ts.denominator) {
    2 -> listOf(ClickPattern(2), ClickPattern(4), ClickPattern(8))
    4 -> listOf(ClickPattern(4), ClickPattern(8), ClickPattern(16))
    8 -> buildList {
        add(ClickPattern(8))
        if (ts.numerator % 3 == 0) add(ClickPattern(4, dotted = true))
        add(ClickPattern(16))
    }
    16 -> buildList {
        add(ClickPattern(16))
        if (ts.numerator % 3 == 0) add(ClickPattern(8, dotted = true))
    }
    else -> error("Unsupported time signature denominator: ${ts.denominator}")
}

/** Closest note length in [to]'s patterns; ties go to the longer note. */
fun remapPattern(old: ClickPattern, from: TimeSignature, to: TimeSignature): ClickPattern =
    patternsFor(to).minWith(
        compareBy<ClickPattern> { kotlin.math.abs(it.wholeNotes - old.wholeNotes) }
            .thenByDescending { it.wholeNotes },
    )
