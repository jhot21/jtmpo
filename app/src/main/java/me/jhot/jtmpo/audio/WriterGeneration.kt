package me.jhot.jtmpo.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Says which writer thread may run. Each start hands its writer a fresh token; stopping or
 * starting again retires every older one, so a writer that outlives its stop (stuck in a
 * blocking write) can't keep rendering or report failures into a later session.
 */
internal class WriterGeneration {
    companion object {
        const val NONE = -1L
    }

    private val current = AtomicLong(0)

    fun begin(): Long = current.incrementAndGet()

    /** Retires the running writer; the returned stamp stays current until the next [begin] or [end]. */
    fun end(): Long = current.incrementAndGet()

    fun isCurrent(token: Long) = token != NONE && current.get() == token

    /**
     * Retires [token] if it is still the running writer and returns a stamp that stays current
     * until the next [begin] or [end], so a posted failure report can check it is still relevant.
     * Returns [NONE] for a stale or already-failed writer.
     */
    fun fail(token: Long): Long = if (current.compareAndSet(token, token + 1)) token + 1 else NONE
}
