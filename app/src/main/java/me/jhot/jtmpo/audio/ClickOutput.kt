package me.jhot.jtmpo.audio

interface ClickOutput {
    fun start()
    fun stop()
    fun setClicksPerMinute(cpm: Double)
}
