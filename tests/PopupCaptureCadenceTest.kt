package net.z841973620.colorosliquidglass.glass

/** Host JVM regression checks; no Android device, sleeps or wall-clock timing required. */
fun main() {
    for (fps in listOf(10, 15, 30, 60, 90, 120)) {
        val cadence = PopupCaptureCadence()
        var captures = 0
        repeat(165) { frame ->
            val now = frame * 1_000_000_000L / 165
            if (cadence.isDue(now, false, false)) {
                cadence.started(now, fps, false, false)
                captures++
            }
        }
        check(captures == fps) { "165 Hz idle traversal produced $captures captures at $fps FPS" }
        println("idle 165 Hz traversals -> $captures captures/sec (configured $fps)")
    }
    val cadence = PopupCaptureCadence()
    check(cadence.delayMillis(0, false) == 0L)
    check(cadence.isDue(0, false, false))
    cadence.started(0, 30, false, false)
    check(cadence.delayMillis(0, false) == 34L)
    check(cadence.delayMillis(33_333_332, false) == 1L)
    check(cadence.delayMillis(33_333_333, false) == 0L)
    check(!cadence.isDue(8_000_000, false, false))
    check(cadence.isDue(8_000_000, true, false))
    cadence.started(8_000_000, 30, true, false)
    check(!cadence.isDue(9_000_000, false, false))
    repeat(50) { frame ->
        val now = 10_000_000L + frame * 1_000_000_000L / 165
        check(cadence.delayMillis(now, true) == 0L)
        check(cadence.isDue(now, false, true)) { "Opening animation was throttled" }
        cadence.started(now, 30, false, true)
    }
    val finalFrame = 308_000_000L
    check(cadence.isDue(finalFrame, false, false)) { "Final settled frame was throttled" }
    cadence.started(finalFrame, 30, false, false)
    check(!cadence.isDue(finalFrame + 1, false, false))
    check(cadence.delayMillis(finalFrame + 1, false) == 34L)
    cadence.request()
    check(cadence.isDue(finalFrame + 2, false, false)) { "Explicit refresh was lost" }
    cadence.started(finalFrame + 2, 30, false, false)
    val afterStall = 5_000_000_000L
    check(cadence.isDue(afterStall, false, false))
    cadence.started(afterStall, 30, false, false)
    check(!cadence.isDue(afterStall, false, false)) { "Late capture caused catch-up burst" }
    cadence.reset()
    check(cadence.isDue(afterStall, false, false)) { "Reopened menu retained old deadline" }
    println("PASS: first frame, deadline rounding, geometry, opening/final frame, explicit refresh, no catch-up burst, reset")
}
