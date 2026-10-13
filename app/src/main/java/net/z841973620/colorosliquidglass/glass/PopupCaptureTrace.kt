package net.z841973620.colorosliquidglass.glass

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
        Log.d(TAG, "begin host=${System.identityHashCode(host)} sample=${count + 1} forced=$forced size=${host.width}x${host.height}")
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
        active = false
        Log.d(TAG, "end host=${System.identityHashCode(host)} totalMs=${total / 1e6} hwSnaps=$rasterCount gpuWaitMs=${waitNanos / 1e6} readbackMs=${readbackNanos / 1e6} published=$published")
    }
}
