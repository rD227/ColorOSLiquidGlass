package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import net.z841973620.colorosliquidglass.GlassConfig

private class GeometryBackground(context: Context) : View(context) {
    var actualHeight = 799
    var actualWidth = 1090
    var alignedRight = false
    @JvmField var mExpandAnimationRunning = false
    @JvmField var mBottomIsRounded = true
    @JvmField var mBottomAmountClips = true
    @JvmField var mClipBottomAmount = 0
    @JvmField val mCornerRadii = floatArrayOf(56f, 56f, 56f, 56f, 56f, 56f, 56f, 56f)
    fun isAlignedToRight() = alignedRight
}

private class GeometryOwner(context: Context) : FrameLayout(context) {
    @JvmField val mBackgroundNormal = GeometryBackground(context)
}

fun main() {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    val owner = GeometryOwner(context)
    val background = owner.mBackgroundNormal
    val container = FrameLayout(context)
    val header = View(context)
    owner.addView(background)
    owner.addView(container)
    container.addView(header)
    owner.layout(0, 0, 1090, 850)
    background.layout(0, 0, 1090, 850)
    container.layout(0, 0, 1090, 850)
    header.layout(0, 0, 1090, 154)
    val geometry = NotificationGroupGeometry(GeometryOwner::class.java, GeometryBackground::class.java)
    val config = GlassConfig().apply {
        glassIntensity = 1f
        blurRadius = 0f
        refractionHeight = 0.22880387f
        refractionAmount = 0.59187067f
        chromaticAberration = 0.6f
    }
    val source = Bitmap.createBitmap(1090, 799, Bitmap.Config.ARGB_8888)
    for (y in 0 until 799) for (x in 0 until 1090) {
        source.setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) Color.BLACK else Color.WHITE)
    }
    val captures = BackdropCapture::class.java.getDeclaredField("CAPTURES")
        .apply { isAccessible = true }.get(null) as Map<*, *>
    fun material(host: View): GlassDrawable {
        val glass = GlassDrawable(host, config)
        glass.setRefractionReferenceSize(310f)
        val capture = captures[host]!!
        BackdropCapture::class.java.getDeclaredField("frontBitmap")
            .apply { isAccessible = true }.set(capture, source)
        BackdropCapture::class.java.getDeclaredField("validFrame")
            .apply { isAccessible = true }.setBoolean(capture, true)
        return glass
    }
    fun render(glass: GlassDrawable, height: Int): Bitmap {
        // Both plates ultimately draw into the same SystemUI window. Keep the GPU
        // viewport identical so this checks clipping rather than viewport precision.
        val gpu = GpuBackdropTexture.render(1090, 799, 0f) {
            it.clipRect(0, 0, 1090, height)
            glass.draw(it)
        }
        return gpu.copy(Bitmap.Config.ARGB_8888, false)!!.also { gpu.recycle() }
    }
    val fullMaterial = material(background)
    geometry.apply(background, owner, fullMaterial)
    val full = render(fullMaterial, 799)
    val sliceMaterial = material(header)
    // No preceding collapsed draw is needed: construct the slice while already expanded.
    geometry.apply(header, owner, sliceMaterial)
    val slice = render(sliceMaterial, 154)
    for (y in 0 until 154) for (x in 0 until 1090) {
        check(slice.getPixel(x, y) == full.getPixel(x, y)) {
            "Header slice changed the group's optical geometry at $x,$y"
        }
    }
    check(Color.alpha(slice.getPixel(545, 77)) > 0) { "Expanded header is transparent" }
    sliceMaterial.setBounds(0, 0, 1090, 154)
    sliceMaterial.setCornerRadii(56f, 56f, 0f, 0f)
    val independent = render(sliceMaterial, 154)
    var changed = 0
    for (y in 0 until 154) for (x in 0 until 1090) {
        if (independent.getPixel(x, y) != full.getPixel(x, y)) changed++
    }
    check(changed > 100) { "Probe did not expose the former header-sized lens change" }
    container.layout(3, 7, 1093, 857)
    container.scrollTo(4, 3)
    header.layout(10, 11, 1100, 165)
    geometry.apply(header, owner, sliceMaterial)
    check(sliceMaterial.bounds == Rect(-9, -15, 1081, 784))
    background.actualWidth = 1000
    background.alignedRight = true
    background.mClipBottomAmount = 10
    geometry.apply(header, owner, sliceMaterial)
    check(sliceMaterial.bounds == Rect(81, -15, 1081, 774))
    background.mExpandAnimationRunning = true
    geometry.apply(header, owner, sliceMaterial)
    check(sliceMaterial.bounds == Rect(36, -15, 1036, 784))
    listOf(full, slice, independent).forEach { it.recycle() }
    BackdropCapture.unregister(background)
    BackdropCapture.unregister(header)
    println("PASS: nonempty expanded header matches group pixels, old independent lens differs ($changed pixels), layout/scroll/alignment/clipping/animation geometry")
}
