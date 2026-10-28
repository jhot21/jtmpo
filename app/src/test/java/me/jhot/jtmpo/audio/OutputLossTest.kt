package me.jhot.jtmpo.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutputLossTest {
    private val wired = RouteDevice(10, AudioDeviceInfo.TYPE_WIRED_HEADSET)
    private val usb = RouteDevice(11, AudioDeviceInfo.TYPE_USB_HEADSET)
    private val btSpeaker = RouteDevice(20, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB")
    private val btSpeakerSco = RouteDevice(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB")
    private val otherBtSco = RouteDevice(22, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "CC:DD")
    private val blankA2dp = RouteDevice(23, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "")
    private val blankSco = RouteDevice(24, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "")
    private val speaker = RouteDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val earpiece = RouteDevice(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val telephony = RouteDevice(3, AudioDeviceInfo.TYPE_TELEPHONY)

    @Test
    fun anUnrelatedDeviceGoingAwayDoesNotPause() {
        // A paired Bluetooth speaker powering off, or a call's SCO link closing, while the click is on wired in-ears.
        assertFalse(OutputLoss.onRemoved(routed = wired, removedSinks = listOf(btSpeaker)))
        assertFalse(OutputLoss.onRemoved(routed = wired, removedSinks = listOf(otherBtSco)))
    }

    @Test
    fun theRoutedDeviceGoingAwayPauses() {
        assertTrue(OutputLoss.onRemoved(routed = wired, removedSinks = listOf(wired)))
        assertTrue(OutputLoss.onRemoved(routed = btSpeaker, removedSinks = listOf(otherBtSco, btSpeaker)))
    }

    @Test
    fun withoutAKnownRouteAnyExternalSinkGoingAwayPauses() {
        assertTrue(OutputLoss.onRemoved(routed = null, removedSinks = listOf(usb)))
        assertFalse(OutputLoss.onRemoved(routed = null, removedSinks = listOf(earpiece)))
    }

    @Test
    fun movingToThePhoneSpeakerPauses() {
        assertTrue(OutputLoss.onRouteChanged(start = wired, now = speaker))
        assertTrue(OutputLoss.onRouteChanged(start = btSpeaker, now = speaker))
        // Even during a call: the click must never blare from the speaker.
        assertTrue(OutputLoss.onRouteChanged(start = wired, now = speaker, inCall = true))
    }

    @Test
    fun aDifferentExternalDeviceTakingOverPauses() {
        assertTrue(OutputLoss.onRouteChanged(start = wired, now = btSpeaker))
        assertTrue(OutputLoss.onRouteChanged(start = speaker, now = usb))
        assertTrue(OutputLoss.onRouteChanged(start = btSpeaker, now = otherBtSco))
    }

    @Test
    fun duringACallAnotherExternalDeviceDoesNotPause() {
        assertFalse(OutputLoss.onRouteChanged(start = wired, now = btSpeakerSco, inCall = true))
        assertFalse(OutputLoss.onRouteChanged(start = speaker, now = usb, inCall = true))
    }

    @Test
    fun theEarpieceOrTelephonySinkNeverPausesByItself() {
        // A call moving the route is not blaring from the speaker; real loss is caught by removal / noisy.
        assertFalse(OutputLoss.onRouteChanged(start = btSpeaker, now = earpiece))
        assertFalse(OutputLoss.onRouteChanged(start = wired, now = earpiece))
        assertFalse(OutputLoss.onRouteChanged(start = wired, now = telephony))
        assertFalse(OutputLoss.onRouteChanged(start = speaker, now = earpiece))
    }

    @Test
    fun theSameRouteOrPhysicalDeviceDoesNotPause() {
        assertFalse(OutputLoss.onRouteChanged(start = wired, now = wired))
        assertFalse(OutputLoss.onRouteChanged(start = speaker, now = speaker))
        // A call can move one Bluetooth headset between its A2DP and SCO profiles.
        assertFalse(OutputLoss.onRouteChanged(start = btSpeaker, now = btSpeakerSco))
        // No route reported (the device is going away): the removal callback decides.
        assertFalse(OutputLoss.onRouteChanged(start = wired, now = null))
        assertFalse(OutputLoss.onRouteChanged(start = null, now = wired))
    }

    @Test
    fun bluetoothWithABlankAddressCountsAsTheSameDevice() {
        // API < 28 and some OEMs report no address: an A2DP -> SCO switch at call time must not pause.
        assertFalse(OutputLoss.onRouteChanged(start = blankA2dp, now = blankSco))
        assertFalse(OutputLoss.onRouteChanged(start = blankA2dp, now = btSpeakerSco))
        assertFalse(OutputLoss.onRouteChanged(start = btSpeaker, now = blankSco))
    }

    @Test
    fun theFirstRoutingCallbackSoonAfterPlayOnlySetsTheBaseline() {
        // Some devices report a transient speaker route right after play(), then the real one.
        val watch = RouteWatch(start = speaker, startedAtMs = 1_000)
        assertFalse(watch.onRouteChanged(wired, atMs = 1_400, inCall = false))
        assertEquals(wired, watch.route)
        // After that, the normal rules apply against the new baseline.
        assertFalse(watch.onRouteChanged(wired, atMs = 5_000, inCall = false))
        assertTrue(watch.onRouteChanged(speaker, atMs = 6_000, inCall = false))
    }

    @Test
    fun aLateFirstRoutingCallbackIsJudgedNormally() {
        val watch = RouteWatch(start = wired, startedAtMs = 1_000)
        assertTrue(watch.onRouteChanged(speaker, atMs = 3_000, inCall = false))
    }

    @Test
    fun anUnknownStartIsFilledInByTheFirstCallbackWhenever() {
        val watch = RouteWatch(start = null, startedAtMs = 1_000)
        assertFalse(watch.onRouteChanged(wired, atMs = 9_000, inCall = false))
        assertEquals(wired, watch.route)
        assertTrue(watch.onRouteChanged(speaker, atMs = 9_500, inCall = false))
    }

    @Test
    fun onlyTheFirstCallbackIsABaselineUpdate() {
        val watch = RouteWatch(start = wired, startedAtMs = 1_000)
        assertFalse(watch.onRouteChanged(wired, atMs = 1_100, inCall = false))
        assertTrue(watch.onRouteChanged(speaker, atMs = 1_200, inCall = false))
    }

    @Test
    fun theSpeakerRightAfterPlayOnARealDeviceIsAFallbackNotABaseline() {
        // An unplug right after Play: the speaker must never become the baseline.
        val watch = RouteWatch(start = wired, startedAtMs = 1_000)
        assertTrue(watch.onRouteChanged(speaker, atMs = 1_200, inCall = false))
        assertEquals(wired, watch.route)
    }

    @Test
    fun theSpeakerAsTheFirstRouteOfAnUnknownStartPauses() {
        val watch = RouteWatch(start = null, startedAtMs = 1_000)
        assertTrue(watch.onRouteChanged(speaker, atMs = 1_100, inCall = false))
    }

    @Test
    fun aTransientSpeakerThenTheRealDeviceIsAdopted() {
        val watch = RouteWatch(start = speaker, startedAtMs = 1_000)
        assertFalse(watch.onRouteChanged(wired, atMs = 1_300, inCall = false))
        assertEquals(wired, watch.route)
    }

    @Test
    fun removingTheOriginalStartDeviceAfterABaselineChangePauses() {
        val watch = RouteWatch(start = usb, startedAtMs = 1_000)
        assertFalse(watch.onRouteChanged(wired, atMs = 1_300, inCall = false))
        assertTrue(watch.onRemoved(listOf(usb)))
        assertTrue(watch.onRemoved(listOf(wired)))
        assertFalse(watch.onRemoved(listOf(otherBtSco)))
    }

    @Test
    fun aNullFirstCallbackDoesNotUseUpTheWindow() {
        val watch = RouteWatch(start = speaker, startedAtMs = 1_000)
        assertFalse(watch.onRouteChanged(null, atMs = 1_100, inCall = false))
        assertFalse(watch.onRouteChanged(wired, atMs = 1_300, inCall = false))
        assertEquals(wired, watch.route)
    }

    @Test
    fun everyCallSetupModeCountsAsInCall() {
        for (mode in listOf(
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE,
            AudioManager.MODE_CALL_SCREENING, AudioManager.MODE_CALL_REDIRECT, AudioManager.MODE_COMMUNICATION_REDIRECT,
        )) assertTrue("mode $mode", OutputLoss.isCallMode(mode))
        assertFalse(OutputLoss.isCallMode(AudioManager.MODE_NORMAL))
    }
}
