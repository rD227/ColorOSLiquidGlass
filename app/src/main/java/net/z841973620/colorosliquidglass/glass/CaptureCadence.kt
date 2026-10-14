package net.z841973620.colorosliquidglass.glass

/** Limits background work without changing the host View's animation cadence. */
class CaptureCadence {
    private var nextCaptureNanos: Long? = null

    fun isDue(nowNanos: Long, force: Boolean): Boolean {
        val deadline = nextCaptureNanos
        return force || deadline == null || nowNanos >= deadline
    }

    // Advance only after a task is accepted; missed deadlines never enqueue catch-up frames.
    fun started(nowNanos: Long, framesPerSecond: Int, force: Boolean) {
        require(framesPerSecond > 0)
        val period = 1_000_000_000L / framesPerSecond
        val deadline = nextCaptureNanos
        nextCaptureNanos = if (force || deadline == null) nowNanos + period
        else deadline + ((nowNanos - deadline) / period + 1) * period
    }

    fun reset() { nextCaptureNanos = null }

    /** Round up so a millisecond timer does not repeatedly wake before the deadline. */
    fun delayMillis(nowNanos: Long): Long {
        val deadline = nextCaptureNanos ?: return 0L
        val remaining = deadline - nowNanos
        return if (remaining <= 0L) 0L else (remaining + 999_999L) / 1_000_000L
    }
}
