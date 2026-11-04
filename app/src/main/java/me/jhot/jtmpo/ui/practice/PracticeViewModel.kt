package me.jhot.jtmpo.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.domain.patternsFor
import me.jhot.jtmpo.domain.remapPattern
import me.jhot.jtmpo.practice.PracticeController
import me.jhot.jtmpo.practice.PracticeSettings
import me.jhot.jtmpo.practice.PracticeState
import me.jhot.jtmpo.practice.RampDirection
import me.jhot.jtmpo.ui.player.tempoUnitNote

/**
 * What the Practice screen draws. No text that needs a `Context` is built here: the screen formats the
 * bar line from [bar], [everyBars], [nextTempo] and [atLimit] with its own strings.
 */
data class PracticeUi(
    val tempo: Int,
    val unitLabel: String,
    val bar: Int,
    val everyBars: Int,
    val nextTempo: Int?,
    val rampOn: Boolean,
    val atLimit: Boolean,
    val isPlaying: Boolean,
    /** The ramp has moved the tempo off the start tempo, so Reset would change something. */
    val canReset: Boolean,
    val settings: PracticeSettings,
    val patterns: List<ClickPattern>,
)

class PracticeViewModel(private val practice: PracticeController) : ViewModel() {
    val ui: StateFlow<PracticeUi> = practice.state
        .map { it.toUi() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, practice.state.value.toUi())

    fun togglePlay() = practice.toggle()

    fun reset() = practice.reset()

    /** The tempo card: the live tempo mid-ramp, the start tempo until the ramp has stepped. */
    fun nudgeTempo(tempo: Int) = practice.nudgeTempo(tempo)

    /** The new signature's grid, with the current subdivision carried over where it still fits. */
    fun setSignature(timeSignature: TimeSignature) = practice.update { settings ->
        settings.copy(
            timeSignature = timeSignature,
            pattern = remapPattern(settings.pattern, settings.timeSignature, timeSignature),
        )
    }

    fun setPattern(pattern: ClickPattern) = practice.update { it.copy(pattern = pattern) }

    fun setDirection(direction: RampDirection) = practice.update { it.copy(direction = direction) }

    fun setStep(stepBpm: Int) = practice.update { it.copy(stepBpm = stepBpm) }

    fun setEveryBars(everyBars: Int) = practice.update { it.copy(everyBars = everyBars) }

    fun toggleFlip() = practice.update { it.copy(flipped = !it.flipped) }

    class Factory(private val practice: PracticeController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PracticeViewModel(practice) as T
    }
}

private fun PracticeState.toUi(): PracticeUi {
    val rampOn = settings.direction != RampDirection.Off
    return PracticeUi(
        tempo = tempo,
        unitLabel = tempoUnitNote(settings.timeSignature),
        bar = bar,
        everyBars = settings.everyBars,
        nextTempo = nextTempo,
        rampOn = rampOn,
        // The ramp reports no next tempo when it is off and when a step would not move it: at a limit.
        atLimit = rampOn && nextTempo == null,
        isPlaying = isPlaying,
        canReset = rampOn && tempo != settings.startTempo,
        settings = settings,
        patterns = patternsFor(settings.timeSignature),
    )
}
