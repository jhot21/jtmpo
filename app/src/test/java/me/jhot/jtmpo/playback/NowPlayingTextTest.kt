package me.jhot.jtmpo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTextTest {
    @Test
    fun positionNamesTheSetlistAndCountsFromOne() {
        assertEquals("Friday gig · 2/5", positionText("Friday gig", index = 1, size = 5))
    }

    @Test
    fun positionDropsAnUnknownSetlistName() {
        assertEquals("1/3", positionText(null, index = 0, size = 3))
        assertEquals("1/3", positionText("", index = 0, size = 3))
    }

    @Test
    fun subtitleIsTheArtistWhenThereIsOne() {
        assertEquals("Fleetwood Mac", nowPlayingSubtitle("Fleetwood Mac", "Friday gig · 2/5"))
    }

    @Test
    fun subtitleFallsBackToThePositionWithoutAnArtist() {
        assertEquals("Friday gig · 2/5", nowPlayingSubtitle(null, "Friday gig · 2/5"))
        assertEquals("Friday gig · 2/5", nowPlayingSubtitle("  ", "Friday gig · 2/5"))
    }
}
