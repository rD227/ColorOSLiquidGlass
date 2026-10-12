import net.z841973620.colorosliquidglass.glass.CaptureCadence
import com.kyant.backdrop.catalog.components.snapSliderValue

fun main() {
    for (fps in listOf(10, 15, 30, 60, 90, 120)) {
        val cadence = CaptureCadence()
        var count = 0
        for (tick in 0 until 240) {
            val now = tick * 1_000_000_000L / 240
            if (cadence.isDue(now, false)) {
                cadence.started(now, fps, false)
                count++
            }
        }
        check(count == fps) { "240Hz host: expected $fps background frames, got $count" }
        println("$fps FPS: $count captures in one second on a 240Hz host")
    }
    val cadence = CaptureCadence()
    check(cadence.isDue(0, false))
    cadence.started(0, 30, false)
    check(!cadence.isDue(1_000_000, false))
    check(cadence.isDue(1_000_000, true))
    // A busy worker rejects a submission: isDue must not consume the capture opportunity.
    check(cadence.isDue(100_000_000, false))
    check(cadence.isDue(100_000_000, false))
    cadence.started(1_000_000_000, 30, false)
    check(!cadence.isDue(1_000_000_001, false)) // no catch-up burst after long work
    cadence.reset()
    check(cadence.isDue(1_000_000_001, false))
    for (index in 0..5) {
        check(snapSliderValue(index.toFloat(), 0f..5f, 4) == index.toFloat())
    }
    check(snapSliderValue(2.49f, 0f..5f, 4) == 2f)
    check(snapSliderValue(2.51f, 0f..5f, 4) == 3f)
    check(snapSliderValue(.1234f, 0f..1f, 0) == .1234f)
    println("PASS: force, busy worker, missed deadlines, reset, discrete and continuous sliders")
}
