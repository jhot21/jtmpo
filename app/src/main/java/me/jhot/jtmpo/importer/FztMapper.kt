package me.jhot.jtmpo.importer

import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TempoLimits
import me.jhot.jtmpo.domain.TimeSignature

/**
 * The single place the Tempo to jtmpo rhythm mapping lives. Always returns a valid signature and pattern.
 *
 * A song with 6 beats and no subdivision is read as 6/8. Its tempo counts dotted quarters per minute in both
 * `beatCode` groups, which is what jtmpo's 6/8 tempo counts, so the number is copied as is. `beatCode` only picks
 * the clicks: 1 subdivides (eighth clicks), 0 clicks the dotted quarter.
 */
fun mapFztSong(song: FztSong): Rhythm {
    val numerator = song.numBeats.coerceAtLeast(1)
    val sixEight = numerator == 6 && song.numSubBeats == 1
    val signature = TimeSignature(numerator, if (sixEight) 8 else 4)
    val pattern = when {
        sixEight && song.beatCode == 0 -> ClickPattern(4, dotted = true)
        sixEight || song.numSubBeats >= 2 -> ClickPattern(8)
        else -> ClickPattern(4)
    }
    // Clamped so a bad file can never store a zero or absurd tempo.
    val tempo = song.tempo.coerceIn(TempoLimits.MIN, TempoLimits.MAX)
    return Rhythm(tempo, signature, pattern)
}
