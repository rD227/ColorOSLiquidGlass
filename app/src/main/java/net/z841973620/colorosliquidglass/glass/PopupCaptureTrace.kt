package net.z841973620.colorosliquidglass.glass

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.util.WeakHashMap

/** Opt-in, first twelve captures per host. No notification content or pixels are logged. */
internal object PopupCaptureTrace {
    private const val TAG = "ColgPopupPerf"
    private val samples = WeakHashMap<View, Int>()
    // Launcher captures run on its main thread. Nested folder snapshots belong to the outer capture.
    private var active = false
    private var rasterCount = 0
    private var waitNanos = 0L
    private var readbackNanos = 0L
    private var captureCpuStart = 0L
    private var captureThread: Thread? = null
    private var currentStage: Stage? = null
    private val stages = linkedMapOf<String, Totals>()
    private val outcomes = linkedMapOf<String, Int>()

    /** Only fixed query identifiers and result categories; never log exception messages or content. */
    @JvmStatic fun outcome(query: String, result: String) {
        if (!active || Thread.currentThread() !== captureThread) return
        val key = "$query.$result"
        outcomes[key] = (outcomes[key] ?: 0) + 1
    }

    internal class Totals(var wall: Long = 0, var cpu: Long = 0, var calls: Int = 0)

    /** Exclusive times: nested work is charged to the child, never counted twice. */
    internal class Stage(val name: String, private val totals: Totals, private val parent: Stage?) : AutoCloseable {
        private val cpuStart = Debug.threadCpuTimeNanos()
        private val wallStart = SystemClock.elapsedRealtimeNanos()
        private var childrenWall = 0L
        private var childrenCpu = 0L

        override fun close() {
            val wall = SystemClock.elapsedRealtimeNanos() - wallStart
            val cpu = Debug.threadCpuTimeNanos() - cpuStart
            totals.wall += wall - childrenWall
            totals.cpu += cpu - childrenCpu
            totals.calls++
            parent?.let {
                it.childrenWall += wall
                it.childrenCpu += cpu
            }
            currentStage = parent
        }
    }

    @JvmStatic fun section(name: String): Stage? {
        if (!active || Thread.currentThread() !== captureThread) return null
        // Recursive tree searches are one operation, not one allocation per visited View.
        if (currentStage?.name == name) return null
        val stage = Stage(name, stages.getOrPut(name) { Totals() }, currentStage)
        currentStage = stage
        return stage
    }

    @JvmStatic fun installStart(): Long =
        if (Log.isLoggable(TAG, Log.DEBUG)) SystemClock.elapsedRealtimeNanos() else 0L

    @JvmStatic fun installEnd(start: Long, host: View) {
        if (start != 0L) Log.d(TAG, "install host=${System.identityHashCode(host)} totalMs=${(SystemClock.elapsedRealtimeNanos() - start) / 1e6}")
    }

    @JvmStatic fun begin(host: View, forced: Boolean): Long {
        if (active || host.tag != "colg_desktop_popup" || !Log.isLoggable(TAG, Log.DEBUG)) return 0L
        val count = samples[host] ?: 0
        if (count >= 12) return 0L
        samples[host] = count + 1
        active = true
        rasterCount = 0
        waitNanos = 0L
        readbackNanos = 0L
        stages.clear()
        outcomes.clear()
        captureThread = Thread.currentThread()
        Log.d(TAG, "begin host=${System.identityHashCode(host)} sample=${count + 1} forced=$forced size=${host.width}x${host.height}")
        captureCpuStart = Debug.threadCpuTimeNanos()
        return SystemClock.elapsedRealtimeNanos()
    }

    @JvmStatic fun stamp(): Long = if (active) SystemClock.elapsedRealtimeNanos() else 0L
    @JvmStatic fun gpuWait(start: Long) {
        if (start == 0L) return
        rasterCount++
        waitNanos += SystemClock.elapsedRealtimeNanos() - start
    }
    @JvmStatic fun readback(start: Long) {
        if (start != 0L) readbackNanos += SystemClock.elapsedRealtimeNanos() - start
    }
    @JvmStatic fun end(start: Long, host: View, published: Boolean) {
        if (start == 0L) return
        val total = SystemClock.elapsedRealtimeNanos() - start
        val cpu = Debug.threadCpuTimeNanos() - captureCpuStart
        active = false
        captureThread = null
        val detail = stages.entries.joinToString(" ") { (name, time) ->
            "$name=${time.wall / 1e6}/${time.cpu / 1e6}/${time.calls}"
        }
        val otherWall = total - stages.values.sumOf { it.wall }
        val otherCpu = cpu - stages.values.sumOf { it.cpu }
        val results = outcomes.entries.joinToString(" ") { (key, count) -> "$key=$count" }
        Log.d(TAG, "end host=${System.identityHashCode(host)} totalMs=${total / 1e6} cpuMs=${cpu / 1e6} offCpuMs=${(total - cpu) / 1e6} hwSnaps=$rasterCount gpuWaitMs=${waitNanos / 1e6} readbackMs=${readbackNanos / 1e6} published=$published stages(wallMs/cpuMs/calls): $detail other=${otherWall / 1e6}/${otherCpu / 1e6} outcomes: $results")
    }
}
