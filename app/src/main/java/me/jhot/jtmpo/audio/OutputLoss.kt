package me.jhot.jtmpo.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** The routing facts [OutputLoss] needs from an [AudioDeviceInfo]; [address] identifies a Bluetooth device. */
internal data class RouteDevice(val id: Int, val type: Int, val address: String = "")

internal fun AudioDeviceInfo.toRouteDevice(): RouteDevice =
    RouteDevice(id, type, if (Build.VERSION.SDK_INT >= 28) address.orEmpty() else "")

/**
 * Decides when the click must pause because its output went away. Only the device the click is
 * actually playing through counts: an unrelated speaker or a call's SCO link disconnecting must not
 * silence wired in-ears, and the click must never move to another output (or the phone speaker) by itself.
 */
internal object OutputLoss {
    // Output devices a performer plugs in or pairs. Internal sinks (speaker, earpiece, telephony,
    // buses) are excluded so a call's routing can't pause us.
    private val EXTERNAL_OUTPUTS = buildSet {
        addAll(
            listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            ),
        )
        if (Build.VERSION.SDK_INT >= 28) add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= 31) {
            addAll(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }

    private val BLUETOOTH = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= 28) add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= 31) {
            addAll(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }

    private val RouteDevice.external get() = type in EXTERNAL_OUTPUTS

    /**
     * Sinks were removed while playing through [routed]. With no known route (the track has not
     * reported one yet), any external sink going away is treated as ours, as the safe side.
     */
    fun onRemoved(routed: RouteDevice?, removedSinks: List<RouteDevice>): Boolean =
        if (routed == null) removedSinks.any { it.external } else removedSinks.any { it.id == routed.id }

    private val SPEAKERS = buildSet {
        add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        if (Build.VERSION.SDK_INT >= 30) add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
    }

    fun isSpeaker(device: RouteDevice) = device.type in SPEAKERS

    // Audio modes in which the phone sets up or holds a call and may move audio between outputs itself.
    private val CALL_MODES = buildSet {
        addAll(listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE))
        if (Build.VERSION.SDK_INT >= 30) add(AudioManager.MODE_CALL_SCREENING)
        if (Build.VERSION.SDK_INT >= 33) {
            addAll(listOf(AudioManager.MODE_CALL_REDIRECT, AudioManager.MODE_COMMUNICATION_REDIRECT))
        }
    }

    fun isCallMode(mode: Int) = mode in CALL_MODES

    /**
     * The track's route moved from [start] to [now]. Only two moves pause: onto the phone speaker
     * (the click must never blare from it), or onto a different external device than the one it started
     * on, unless a call is set up ([inCall]), when the phone may move audio between headsets itself.
     * A move to the earpiece or a telephony sink never pauses by itself: a call is not a loss, and a
     * real loss is caught by the device-removed callback and BECOMING_NOISY.
     */
    fun onRouteChanged(start: RouteDevice?, now: RouteDevice?, inCall: Boolean = false): Boolean {
        if (start == null || now == null || now.id == start.id) return false
        if (now.type in SPEAKERS) return start.type !in SPEAKERS
        if (!now.external) return false
        if (start.external && samePhysicalDevice(start, now)) return false
        return !inCall
    }

    // One Bluetooth device switching profiles (A2DP <-> SCO during a call). A blank address (API < 28,
    // some OEMs) cannot prove otherwise, so it counts as the same device rather than pausing the click.
    private fun samePhysicalDevice(a: RouteDevice, b: RouteDevice): Boolean =
        a.type in BLUETOOTH && b.type in BLUETOOTH &&
            (a.address.isEmpty() || b.address.isEmpty() || a.address == b.address)
}

/**
 * The route baseline of one playback session. Some devices report a transient default route (the
 * speaker) right after play() and the real one in the first routing callback, so that first callback,
 * if it comes within [SETTLE_MS] of the start (or the start route was unknown), only sets the baseline.
 */
internal class RouteWatch(start: RouteDevice?, private val startedAtMs: Long) {
    companion object {
        const val SETTLE_MS = 1_000L
    }

    private val original = start
    var route: RouteDevice? = start
        private set
    private var settled = false

    /** True when the click must pause; otherwise the baseline may have been updated. */
    fun onRouteChanged(now: RouteDevice?, atMs: Long, inCall: Boolean): Boolean {
        // No route reported yet: nothing to judge, and it must not use up the settle window.
        if (now == null) return false
        val first = !settled
        settled = true
        val current = route
        // The window is only for a transient speaker followed by the real device, never the reverse:
        // the speaker after a real (or unknown) start is a fallback, judged and paused.
        if (OutputLoss.isSpeaker(now) && (current == null || !OutputLoss.isSpeaker(current))) {
            return current == null || OutputLoss.onRouteChanged(current, now, inCall)
        }
        if (current == null || (first && atMs - startedAtMs <= SETTLE_MS)) {
            route = now
            return false
        }
        return OutputLoss.onRouteChanged(current, now, inCall)
    }

    /** Losing the device the click started on, or the one it settled on, pauses it. */
    fun onRemoved(removedSinks: List<RouteDevice>): Boolean =
        OutputLoss.onRemoved(route, removedSinks) ||
            (original != null && original != route && OutputLoss.onRemoved(original, removedSinks))
}
