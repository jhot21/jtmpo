package me.jhot.jtmpo.audio

interface ClickOutput {
    fun start()
    fun stop()
    fun setClicksPerMinute(cpm: Double)

    /**
     * Told when each click starts. Defaulted away so an output that only needs to play, and the
     * fakes that stand in for one, need not implement it; [AudioTrackOutput] reports each click.
     */
    var onClickStart: (() -> Unit)?
        get() = null
        set(value) {}
}
