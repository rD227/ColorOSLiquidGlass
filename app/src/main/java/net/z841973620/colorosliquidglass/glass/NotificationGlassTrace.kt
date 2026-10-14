package net.z841973620.colorosliquidglass.glass

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View

/** Temporary, opt-in geometry trace. Does not read notification text or change drawing. */
class NotificationGlassTrace(private val host: View, private val role: String) {
    companion object {
        private const val TAG = "ColgNotifTrace"

        @JvmStatic
        fun enabled(): Boolean = Log.isLoggable(TAG, Log.DEBUG)

        @JvmStatic
        fun headerCreated(host: View, owner: View, expanded: Boolean, plate: Any) {
            if (!enabled()) return
            Log.d(TAG, "headerCreated host=${identity(host)} owner=${identity(owner)} " +
                "plate=${identity(plate)} expanded=$expanded")
        }

        private fun identity(value: Any): String =
            "${value.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(value))}"
    }

    var context: String = ""
    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val clip = Rect()
    private var previousState: String? = null
    private var lastLoggedAt = 0L

    fun skipped(reason: String) {
        if (!enabled() || previousState == reason) return
        Log.d(TAG, "skip t=${SystemClock.uptimeMillis()} role=$role host=${identity(host)} reason=$reason")
        previousState = reason
    }

    @Suppress("DEPRECATION") // Diagnostic only: compare the OEM canvas transform between frames.
    fun record(glass: GlassDrawable, canvas: Canvas, heightPx: Float, amountPx: Float) {
        if (!enabled()) return
        val now = SystemClock.uptimeMillis()
        canvas.getMatrix(matrix)
        matrix.getValues(matrixValues)
        canvas.getClipBounds(clip)
        val state = "b=${glass.bounds.toShortString()} hPx=$heightPx amountPx=$amountPx " +
            "reference=${glass.refractionReferenceSize} " +
            "a=${glass.alpha}/${host.alpha} view=${host.width}x${host.height} " +
            "scale=${host.scaleX},${host.scaleY} translation=${host.translationX},${host.translationY} " +
            "matrix=${matrixValues.joinToString(",")} clip=${clip.toShortString()} " +
            "radii=${glass.cornerRadii.joinToString(",")} $context"
        // Keep every geometry/alpha change; stable frames only need a heartbeat.
        if (state == previousState && now - lastLoggedAt < 250L) return
        if (previousState == null) {
            val ancestors = mutableListOf<String>()
            var ancestor: View? = host
            repeat(6) {
                ancestor?.let { view ->
                    ancestors.add(identity(view))
                    ancestor = view.parent as? View
                }
            }
            Log.d(TAG, "attach role=$role glass=${identity(glass)} path=${ancestors.joinToString(" > ")}")
        }
        Log.d(TAG, "draw t=$now role=$role host=${identity(host)} glass=${identity(glass)} $state")
        previousState = state
        lastLoggedAt = now
    }
}
