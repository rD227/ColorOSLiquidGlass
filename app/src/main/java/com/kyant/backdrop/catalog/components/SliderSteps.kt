package com.kyant.backdrop.catalog.components

import kotlin.math.roundToInt

/** steps counts intermediate ticks; zero preserves the existing continuous slider. */
internal fun snapSliderValue(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    require(steps >= 0)
    if (steps == 0) return value
    require(range.endInclusive > range.start)
    val intervals = steps + 1
    val fraction = (value - range.start) / (range.endInclusive - range.start)
    return range.start + (fraction * intervals).roundToInt() *
        (range.endInclusive - range.start) / intervals
}
