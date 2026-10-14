package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import net.z841973620.colorosliquidglass.GlassConfig

/** Uses the direct BitmapShader draw and actual header geometry, not the popup RenderEffect. */
fun main() {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    for ((width, height) in listOf(128 to 64, 1090 to 154)) {
        val root = FrameLayout(context)
        val host = View(context)
        root.addView(host)
        root.layout(0, 0, width, height)
        host.layout(0, 0, width, height)
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            source.setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) Color.BLACK else Color.WHITE)
        }
        val config = GlassConfig().apply {
            glassIntensity = 1f
            blurRadius = 0f
            refractionHeight = 0.22880387f
            refractionAmount = 0.59187067f
            chromaticAberration = 0.6f
        }
        val glass = GlassDrawable(host, config)
        glass.setBounds(0, 0, width, height)
        glass.setCornerRadii(if (width > 128) 56f else 12f, if (width > 128) 56f else 12f, 0f, 0f)
        glass.setRefractionReferenceSize(if (width > 128) 310f else 64f)
        val captures = BackdropCapture::class.java.getDeclaredField("CAPTURES")
            .apply { isAccessible = true }.get(null) as Map<*, *>
        val capture = captures[host]!!
        BackdropCapture::class.java.getDeclaredField("frontBitmap")
            .apply { isAccessible = true }.set(capture, source)
        BackdropCapture::class.java.getDeclaredField("validFrame")
            .apply { isAccessible = true }.setBoolean(capture, true)
        fun render(): Bitmap {
            val gpu = GpuBackdropTexture.render(width, height, 0f) { glass.draw(it) }
            return gpu.copy(Bitmap.Config.ARGB_8888, false)!!.also { gpu.recycle() }
        }
        glass.setBottomRefractionEnabled(true)
        val full = render()
        glass.setBottomRefractionEnabled(false)
        val excluded = render()
        var upperChanged = 0
        var lowerChanged = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (full.getPixel(x, y) != excluded.getPixel(x, y)) {
                if (y < height / 2) upperChanged++ else lowerChanged++
            }
        }
        println("direct header ${width}x$height upperChanged=$upperChanged lowerChanged=$lowerChanged")
        check(upperChanged == 0) { "Disabling bottom optics changed the actual header's top half" }
        check(lowerChanged > 0) { "Header bottom optics were not disabled" }
        listOf(full, excluded).forEach { it.recycle() }
        BackdropCapture.unregister(host)
    }
}
