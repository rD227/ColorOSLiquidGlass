package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import net.z841973620.colorosliquidglass.GlassConfig
import java.util.IdentityHashMap
import kotlin.math.abs

private class BatchBackground(@JvmField val mBgView: View)
private class BatchPreviewItem(context: Context, icon: Bitmap) {
    @JvmField val drawable = BitmapDrawable(context.resources, icon).apply { setBounds(0, 0, 32, 32) }
    @JvmField val transX = 16f
    @JvmField val transY = 16f
    @JvmField val scale = 0.8f
    @JvmField val hidden = false
}
private class BatchPreview(context: Context, icon: Bitmap) {
    @JvmField val mFirstPageParams = listOf(BatchPreviewItem(context, icon))
    fun getIntrinsicIconSize() = 32
}
private class BatchFolderIcon(context: Context, icon: Bitmap) : FrameLayout(context) {
    @JvmField val mBackground = BatchBackground(View(context))
    @JvmField val mPreviewItemManager = BatchPreview(context, icon)
    @JvmField val mFolderName = TextView(context).apply {
        text = "Folder"
        textSize = 12f
        setTextColor(Color.BLACK)
    }
    init {
        addView(mBackground.mBgView)
        addView(mFolderName)
    }
}

/** Compare real AGSL folder rendering before/after batching, including CPU crops and stacking. */
fun main() {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    // app_process bypasses application binding's font-map setup. Initialize the real
    // system defaults before any TextView or text Paint is constructed.
    Typeface::class.java.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null)
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    val config = GlassConfig().apply {
        refractionHeight = 0.18f
        refractionAmount = 0.2f
        chromaticAberration = 0.35f
    }
    val captures = BackdropCapture::class.java.getDeclaredField("CAPTURES")
        .apply { isAccessible = true }.get(null) as Map<*, *>
    val oldRender = FolderCompositeRenderer::class.java.getDeclaredMethod("hwFolderComposite",
        View::class.java, View::class.java, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    val draw = FolderCompositeRenderer::class.java.getDeclaredMethod("drawFolderComposite",
        View::class.java, View::class.java, Boolean::class.javaPrimitiveType, Canvas::class.java)
        .apply { isAccessible = true }
    val retain = FolderCompositeRenderer::class.java.getDeclaredMethod("retainFolderComposite",
        View::class.java, Bitmap::class.java, Bitmap::class.java, Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    val source = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
    for (y in 0 until 180) for (x in 0 until 180) source.setPixel(x, y,
        Color.rgb((x * 3) % 256, (y * 3) % 256, if ((x / 17 + y / 17) % 2 == 0) 40 else 210))
    val icon = GpuBackdropTexture.render(32, 32, 0f) { it.drawColor(Color.RED) }
    val folders = List(6) { index ->
        BatchFolderIcon(context, icon).apply {
            val w = 98 + index * 13
            val h = 112 + index * 7
            layout(0, 0, w, h)
            mBackground.mBgView.layout(3, 2, w - 3, h - 25)
            mFolderName.layout(0, h - 24, w, h)
            val host = mBackground.mBgView
            val material = GlassDrawable(host, config).apply {
                setBounds(0, 0, host.width, host.height)
                setCornerRadii(22f, 22f, 22f, 22f)
            }
            host.background = material
            val capture = captures[host]!!
            fun field(name: String) = BackdropCapture::class.java.getDeclaredField(name)
                .apply { isAccessible = true }
            field("explicitWindowSample").setBoolean(capture, true)
            field("frontBitmap").set(capture, source)
            field("validFrame").setBoolean(capture, true)
        }
    }
    fun destination(index: Int, scale: Float) = RectF(20f + index % 3 * 122f,
        15f + index / 3 * 140f, 20f + index % 3 * 122f + folders[index].width * scale,
        15f + index / 3 * 140f + folders[index].height * scale)
    val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val overlay = Paint().apply { color = 0x8044AA22.toInt() }
    fun old(scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        folders.forEachIndexed { index, folder ->
            val rendered = oldRender.invoke(null, folder, folder.mBackground.mBgView,
                folder.width, folder.height, true) as Bitmap
            canvas.drawBitmap(rendered, null, destination(index, scale), bitmapPaint)
            rendered.recycle()
            if (index == 1) canvas.drawRect(0f, 110f, 512f, 175f, overlay)
        }
        return bitmap
    }
    fun batched(scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val batch = PopupFolderBatch(340) // Force several rows and differently sized slots.
        val recordingCanvases = IdentityHashMap<Canvas, Boolean>()
        folders.forEachIndexed { index, folder ->
            batch.addFolder(folder.width, folder.height, destination(index, scale),
                {
                    check(it.isHardwareAccelerated)
                    recordingCanvases[it] = true
                    draw.invoke(null, folder, folder.mBackground.mBgView, true, it)
                },
                { retain.invoke(null, folder, it, null, true, folder.width, folder.height, true, true) })
            if (index == 1) batch.addSoftware { canvas.drawRect(0f, 110f, 512f, 175f, overlay) }
        }
        check(batch.paintInto(canvas)) { "Folder atlas GPU render failed" }
        check(recordingCanvases.size == 1) { "Folders used separate GPU recording canvases" }
        return bitmap
    }
    var first: Bitmap? = null
    for (scale in listOf(1f, 0.67f, 1.2f)) {
        val expected = old(scale)
        val actual = batched(scale)
        var mismatches = 0
        var maxError = 0
        for (y in 0 until 400) for (x in 0 until 512) {
            val a = expected.getPixel(x, y)
            val b = actual.getPixel(x, y)
            val error = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)),
                abs(Color.blue(a) - Color.blue(b)), abs(Color.alpha(a) - Color.alpha(b)))
            if (error > 3) mismatches++
            maxError = maxOf(maxError, error)
        }
        println("scale=$scale differingPixels(>3)=$mismatches maxChannelError=$maxError")
        check(mismatches < 512 * 400 / 200) { "Atlas changed folder pixels/coordinates/stacking" }
        expected.recycle()
        if (scale == 1f) first = actual else actual.recycle()
    }
    source.eraseColor(Color.BLUE)
    val changed = batched(1f)
    check(changed.getPixel(60, 60) != first!!.getPixel(60, 60)) { "Batch froze an old background" }
    val retained = FolderCompositeRenderer::class.java.getDeclaredField("FOLDER_SNAPS")
        .apply { isAccessible = true }.get(null) as Map<*, *>
    for (folder in folders) {
        val entry = retained[folder]!!
        val crop = entry.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }
            .get(entry) as Bitmap
        check(!crop.isRecycled && crop.width == folder.width && crop.height == folder.height)
    }
    var called = false
    val empty = PopupFolderBatch(340)
    empty.addSoftware { called = true }
    check(empty.paintInto(Canvas(changed)) && called)
    first!!.recycle()
    changed.recycle()
    icon.recycle()
    DesktopIconOverlay.clearFolderSnaps()
    for (folder in folders) BackdropCapture.unregister(folder.mBackground.mBgView)
    println("PASS: six live AGSL folders, atlas row packing, clipping/filtering at three scales, interleaved software stacking, fresh source, retained crop ownership, apps-only path")
}
