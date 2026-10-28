package me.jhot.jtmpo.ui.player

import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Test

class TempoUnitTest {
    @Test
    fun simpleSignaturesShowTheBottomNote() {
        assertEquals("\uD834\uDD57\uD834\uDD65", tempoUnitNote(TimeSignature(2, 2)))
        assertEquals("\u2669", tempoUnitNote(TimeSignature(4, 4)))
        assertEquals("\u266A", tempoUnitNote(TimeSignature(7, 8)))
        assertEquals("\u266C", tempoUnitNote(TimeSignature(7, 16)))
    }

    @Test
    fun compoundSignaturesShowTheDottedBeat() {
        assertEquals("\u2669.", tempoUnitNote(TimeSignature(6, 8)))
        assertEquals("\u266A.", tempoUnitNote(TimeSignature(6, 16)))
    }
}
