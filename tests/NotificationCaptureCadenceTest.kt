package net.z841973620.colorosliquidglass.glass

fun main() {
    val cadence = CaptureCadence()
    // Rejected/busy submissions must leave the opening sample available.
    repeat(10) { check(cadence.isDue(it.toLong(), false)) }
    cadence.started(10, 0, false)
    repeat(2400) { frame ->
        check(!cadence.isDue(frame * 1_000_000_000L / 240, false)) {
            "Zero Hz submitted another capture during the same opening"
        }
    }
    check(!cadence.isDue(Long.MAX_VALUE, false))
    check(cadence.delayMillis(10) == Long.MAX_VALUE)
    // Completion, an empty/failed result and geometry changes do not reset the cadence.
    check(!cadence.isDue(20_000_000_000, false))
    cadence.reset()
    check(cadence.isDue(20_000_000_000, false)) { "Reopening must sample again" }
    cadence.started(20_000_000_000, 0, false)
    check(!cadence.isDue(20_000_000_001, false))

    for (fps in listOf(1, 2, 5, 10, 15, 30)) {
        cadence.reset()
        var captures = 0
        repeat(240) { frame ->
            val now = frame * 1_000_000_000L / 240
            if (cadence.isDue(now, false)) {
                cadence.started(now, fps, false)
                captures++
            }
        }
        check(captures == fps) { "Expected $fps captures, got $captures" }
    }
    println("PASS: zero Hz samples once, busy submissions, no timer, reopen reset, positive Hz cadence")
}
