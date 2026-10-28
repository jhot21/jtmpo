package me.jhot.jtmpo.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.jhot.jtmpo.R

/**
 * Plays the [ClickRenderer] through a low-latency media-stream [AudioTrack].
 *
 * Deliberately never requests audio focus: notifications, other apps and calls must not pause
 * or duck the click. The only automatic stops are losing the output device (unplugged headphones
 * or adapter) or the track failing, reported through [onOutputLost] so the controller's state
 * stays the truth.
 */
class AudioTrackOutput internal constructor(
    context: Context,
    private val renderer: ClickRenderer,
    private val trackFactory: () -> AudioTrack,
) : ClickOutput {
    constructor(context: Context, renderer: ClickRenderer) : this(context, renderer, ::buildTrack)

    companion object {
        const val SAMPLE_RATE = 48000

        // 5 ms: rate changes and the pulse are applied at buffer granularity.
        private const val BUFFER_FRAMES = SAMPLE_RATE / 200
        private const val TIMESTAMP_REFRESH_NANOS = 250_000_000L
        // One tag for everything audio, so device checks are a single `adb logcat -s jtmpo.audio`.
        private const val TAG = "jtmpo.audio"

        private fun buildTrack(): AudioTrack {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minBytes, BUFFER_FRAMES * 2 * 2))
                .build()
        }

        /** Builds the renderer for the bundled click, which must match the output rate exactly. */
        fun loadClickRenderer(context: Context): ClickRenderer {
            val bytes = context.resources.openRawResource(R.raw.digital_tock_48k).use { it.readBytes() }
            val wav = decodeWav16Mono(bytes)
            require(wav.sampleRate == SAMPLE_RATE) { "click is ${wav.sampleRate} Hz, output runs at $SAMPLE_RATE Hz" }
            return ClickRenderer(wav.samples, wav.sampleRate)
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread when the output device goes away or the track fails to start or dies. */
    @Volatile var onOutputLost: (() -> Unit)? = null

    private val _pulses = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One emission per audible click (thinned to 12/s), timed to when it reaches the output. */
    val pulses: SharedFlow<Unit> = _pulses.asSharedFlow()

    /**
     * How early the flash fires before its click: the writer only checks after each write (up to
     * ~20 ms late) and the screen takes a frame or two to show it. Set from the user's setting.
     */
    @Volatile var pulseLeadMs = 40

    @Volatile private var cpm = 120.0
    private val generation = WriterGeneration()
    private var track: AudioTrack? = null
    private var writer: Thread? = null
    private var watchingOutputs = false

    // Created by start(); its baseline is settled by the first routing callback (see RouteWatch).
    @Volatile private var routeWatch: RouteWatch? = null

    override fun setClicksPerMinute(cpm: Double) {
        this.cpm = cpm
        renderer.setClicksPerMinute(cpm)
    }

    @Synchronized
    override fun start() {
        if (writer != null) return
        // Rate first: reset() makes the next render click at once and schedule the following
        // click with whatever interval is current.
        renderer.setClicksPerMinute(cpm)
        renderer.reset()
        // A fresh track per start: its playhead and timestamps begin at 0, and it opens on
        // whatever output is current.
        val t = try {
            trackFactory()
        } catch (e: RuntimeException) {
            return failStart(e, null)
        }
        try {
            t.play()
        } catch (e: RuntimeException) {
            return failStart(e, t)
        }
        track = t
        // The device the click starts on is the only one whose loss (or replacement) pauses it.
        // Some devices report the route only after play(); the routing listener fills it in then.
        val startRoute = t.routedDevice?.toRouteDevice()
        Log.i(TAG, "route at start: ${startRoute.describe()}")
        routeWatch = RouteWatch(startRoute, SystemClock.elapsedRealtime())
        t.addOnRoutingChangedListener(routingListener, mainHandler)
        watchOutputs()
        writer = Thread(Writer(t, generation.begin()), "jtmpo-click").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * The track couldn't be built or started (no output, audio server busy). Nothing is left
     * running; the controller is told after the current call returns, so play() ends paused
     * instead of throwing on the UI thread.
     */
    private fun failStart(e: RuntimeException, t: AudioTrack?) {
        Log.e(TAG, "could not start audio output", e)
        t?.let { runCatching { it.release() } }
        val stamp = generation.end()
        mainHandler.post { if (generation.isCurrent(stamp)) onOutputLost?.invoke() }
    }

    @Synchronized
    override fun stop() {
        val w = writer ?: return
        generation.end()
        // A playing track drains a 5 ms buffer quickly, so the writer exits within one write.
        w.join(200)
        val t = track
        if (t != null) {
            t.removeOnRoutingChangedListener(routingListener)
            // Acceptance check B2: a non-zero count after a long screen-off session means a bigger buffer.
            runCatching { Log.i(TAG, "stopped: underruns=${t.underrunCount} bufferSizeInFrames=${t.bufferSizeInFrames}") }
            runCatching { t.pause(); t.flush() }
            w.join(200)
            t.release()
        }
        // Retired by end(), a writer still stuck here can no longer render or report.
        if (w.isAlive) Log.w(TAG, "writer thread did not exit")
        writer = null
        track = null
        routeWatch = null
        unwatchOutputs()
    }

    /**
     * One playback session's writer. Its state is its own, allocated once per start, so a stale
     * writer from an earlier session never shares buffers or counters with the current one.
     */
    private inner class Writer(private val track: AudioTrack, private val token: Long) : Runnable {
        private val buffer = ShortArray(BUFFER_FRAMES)
        private val scheduler = PulseScheduler(SAMPLE_RATE)
        private val timestamp = AudioTimestamp()
        private var timestampValid = false
        private var lastTimestampQuery = 0L
        private var framesWritten = 0L
        private val onClickStart = ClickListener { i -> scheduler.onClick(framesWritten + i) }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (generation.isCurrent(token)) {
                renderer.render(buffer, BUFFER_FRAMES, onClickStart)
                var offset = 0
                while (offset < BUFFER_FRAMES && generation.isCurrent(token)) {
                    val n = track.write(buffer, offset, BUFFER_FRAMES - offset)
                    if (n < 0) {
                        reportFailure("AudioTrack.write failed: $n")
                        return
                    }
                    offset += n
                }
                framesWritten += offset
                scheduler.leadFrames = pulseLeadMs * SAMPLE_RATE / 1000L
                if (scheduler.poll(playhead()) && generation.isCurrent(token)) _pulses.tryEmit(Unit)
            }
        }

        private fun reportFailure(message: String) {
            val stamp = generation.fail(token)
            if (stamp == WriterGeneration.NONE) return // already stopped; nobody to tell
            Log.e(TAG, message)
            // Skip the report if playback was stopped or restarted before it ran.
            mainHandler.post { if (generation.isCurrent(stamp)) onOutputLost?.invoke() }
        }

        /** Frame currently leaving the device, from the track timestamp when it has one. */
        private fun playhead(): Long {
            val now = System.nanoTime()
            // Timestamps are costly to query; refresh a few times a second and extrapolate between.
            if (!timestampValid || now - lastTimestampQuery > TIMESTAMP_REFRESH_NANOS) {
                lastTimestampQuery = now
                timestampValid = track.getTimestamp(timestamp)
            }
            val position = if (timestampValid) {
                timestamp.framePosition + (now - timestamp.nanoTime) * SAMPLE_RATE / 1_000_000_000L
            } else {
                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            }
            return position.coerceAtMost(framesWritten)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val sinks = removedDevices.filter { it.isSink }.map { it.toRouteDevice() }
            val watch = routeWatch
            val lost = if (watch == null) OutputLoss.onRemoved(null, sinks) else watch.onRemoved(sinks)
            if (lost) onOutputLost?.invoke()
        }
    }

    // A device that connects and takes over the route would otherwise carry the click somewhere else.
    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        if (router !== track) return@OnRoutingChangedListener
        val watch = routeWatch ?: return@OnRoutingChangedListener
        val now = router.routedDevice?.toRouteDevice()
        val lost = watch.onRouteChanged(now, SystemClock.elapsedRealtime(), inCall())
        Log.i(TAG, "route changed: ${now.describe()} baseline=${watch.route.describe()} pause=$lost")
        if (lost) onOutputLost?.invoke()
    }

    // While a call is set up the phone moves audio between headsets itself; that is not a takeover.
    private fun inCall(): Boolean = OutputLoss.isCallMode(audioManager.mode)

    private fun RouteDevice?.describe() = if (this == null) "none" else "type=$type id=$id"

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onOutputLost?.invoke()
        }
    }

    private fun watchOutputs() {
        if (watchingOutputs) return
        watchingOutputs = true
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unwatchOutputs() {
        if (!watchingOutputs) return
        watchingOutputs = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        appContext.unregisterReceiver(noisyReceiver)
    }
}
