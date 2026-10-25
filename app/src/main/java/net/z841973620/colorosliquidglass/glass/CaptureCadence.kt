package net.z841973620.colorosliquidglass.glass

/** Limits background work without changing the host View's animation cadence. */
class CaptureCadence {
    private var nextCaptureNanos: Long? = null
    private var singleCaptureStarted = false

    fun isDue(nowNanos: Long, force: Boolean): Boolean {
        if (force) return true
        if (singleCaptureStarted) return false
        val deadline = nextCaptureNanos
        return deadline == null || nowNanos >= deadline
    }

    // Advance only after a task is accepted; missed deadlines never enqueue catch-up frames.
    fun started(nowNanos: Long, framesPerSecond: Int, force: Boolean) {
        require(framesPerSecond >= 0)
        // Zero Hz consumes one accepted task until reset, even if that task produces no frame.
        singleCaptureStarted = framesPerSecond == 0
        if (singleCaptureStarted) {
            nextCaptureNanos = null
            return
        }
        val period = 1_000_000_000L / framesPerSecond
        val deadline = nextCaptureNanos
        nextCaptureNanos = if (force || deadline == null) nowNanos + period
        else deadline + ((nowNanos - deadline) / period + 1) * period
    }

    fun reset() {
        nextCaptureNanos = null
        singleCaptureStarted = false
    }

    /** Round up to the deadline; Long.MAX_VALUE means no periodic capture at zero Hz. */
    fun delayMillis(nowNanos: Long): Long {
        if (singleCaptureStarted) return Long.MAX_VALUE
        val deadline = nextCaptureNanos ?: return 0L
        val remaining = deadline - nowNanos
        return if (remaining <= 0L) 0L else (remaining + 999_999L) / 1_000_000L
    }
}
