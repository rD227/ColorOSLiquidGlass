package net.z841973620.colorosliquidglass.glass

/** Pace idle content captures, while preserving every opening/geometry change and final frame. */
class PopupCaptureCadence {
    private val cadence = CaptureCadence()
    private var requested = true
    private var wasOpening = false

    fun request() { requested = true }

    fun isDue(nowNanos: Long, geometryChanged: Boolean, opening: Boolean): Boolean =
        cadence.isDue(nowNanos, immediate(geometryChanged, opening))

    // Called only when capture is about to run, not when a redraw is merely requested.
    fun started(nowNanos: Long, framesPerSecond: Int, geometryChanged: Boolean, opening: Boolean) {
        cadence.started(nowNanos, framesPerSecond, immediate(geometryChanged, opening))
        requested = false
        wasOpening = opening
    }

    fun delayMillis(nowNanos: Long, opening: Boolean): Long =
        if (immediate(false, opening)) 0L else cadence.delayMillis(nowNanos)

    fun reset() {
        cadence.reset()
        requested = true
        wasOpening = false
    }

    private fun immediate(geometryChanged: Boolean, opening: Boolean): Boolean =
        requested || geometryChanged || opening || wasOpening
}
