package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View

private class PreviewItem(context: Context, source: Bitmap) {
    @JvmField val drawable = BitmapDrawable(context.resources, source).apply { setBounds(0, 0, 32, 32) }
    @JvmField val transX = 0f
    @JvmField val transY = 0f
    @JvmField val scale = 1f
    @JvmField val hidden = false
}

// No draw(Canvas): deliberately exercises the production folder-preview fallback.
private class PreviewWithoutDraw(context: Context, source: Bitmap) {
    @JvmField val mFirstPageParams = listOf(PreviewItem(context, source))
    fun getIntrinsicIconSize(): Int = 32
}

private class PreviewHost(context: Context, source: Bitmap) : View(context) {
    @JvmField val mPreviewItemManager = PreviewWithoutDraw(context, source)
}

fun main() {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    fun hardware(color: Int): Bitmap = GpuBackdropTexture.render(32, 32, 0f) { it.drawColor(color) }
    val red = hardware(Color.RED)
    val blue = hardware(Color.BLUE)
    val green = hardware(Color.GREEN)
    check(red.config == Bitmap.Config.HARDWARE)

    val cache = DesktopIconBitmapCache()
    val first = cache.softwareIcon(red)!!
    check(first.config == Bitmap.Config.ARGB_8888 && Color.red(first.getPixel(16, 16)) > 240)
    check(cache.softwareIcon(red) === first) { "Repeated icon sampling performed another readback" }
    red.density += 80
    val metadataUpdate = cache.softwareIcon(red)!!
    check(metadataUpdate !== first && metadataUpdate.density == red.density)
    check(!first.isRecycled) { "Replaced copy is still borrowed by a caller" }
    val changed = cache.softwareIcon(blue)!!
    check(changed !== metadataUpdate && Color.blue(changed.getPixel(16, 16)) > 240)

    val mutable = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    check(cache.softwareIcon(mutable) === mutable)
    mutable.eraseColor(Color.BLUE)
    check(Color.blue(cache.softwareIcon(mutable)!!.getPixel(16, 16)) == 255)

    val lru = DesktopIconBitmapCache(1024 * 1024, 2)
    val kept = lru.softwareIcon(red)
    val evicted = lru.softwareIcon(blue)!!
    check(lru.softwareIcon(red) === kept)
    lru.softwareIcon(green)
    check(lru.softwareIcon(blue) !== evicted) { "Entry budget did not evict the least recently used icon" }
    check(!evicted.isRecycled && !blue.isRecycled)
    val budget = DesktopIconBitmapCache(first.allocationByteCount, 8)
    val old = budget.softwareIcon(red)
    budget.softwareIcon(blue)
    check(budget.softwareIcon(red) !== old) { "Byte budget was not enforced" }
    val oversize = DesktopIconBitmapCache(first.allocationByteCount / 2, 8)
    check(oversize.softwareIcon(red) !== oversize.softwareIcon(red))
    cache.clear()
    check(!metadataUpdate.isRecycled && !red.isRecycled)

    val select = DesktopIconOverlay::class.java.getDeclaredMethod("bitmapForCanvas", Bitmap::class.java, Canvas::class.java)
        .apply { isAccessible = true }
    val preview = DesktopIconOverlay::class.java.getDeclaredMethod("paintFolderPreviewBitmaps", View::class.java, Canvas::class.java)
        .apply { isAccessible = true }
    val host = PreviewHost(context, red)
    val gpu = GpuBackdropTexture.render(32, 32, 0f) { canvas ->
        check(select.invoke(null, red, canvas) === red) { "Hardware canvas received a software copy" }
        preview.invoke(null, host, canvas)
    }
    val gpuPixels = gpu.copy(Bitmap.Config.ARGB_8888, false)!!
    check(Color.red(gpuPixels.getPixel(16, 16)) > 240) { "Hardware folder-preview fallback was lost" }
    val output = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    preview.invoke(null, host, canvas)
    check(Color.red(output.getPixel(16, 16)) > 240) { "Software folder-preview fallback was lost" }
    val borrowed = select.invoke(null, red, canvas) as Bitmap
    check(select.invoke(null, red, canvas) === borrowed)
    val fresh = DesktopIconOverlay::class.java.getDeclaredMethod("softwareBitmap", Bitmap::class.java)
        .apply { isAccessible = true }
    val temporary = fresh.invoke(null, red) as Bitmap
    check(temporary !== borrowed) { "Pixel-inspection caller was given a shared copy it may recycle" }
    temporary.recycle()
    check(select.invoke(null, red, canvas) === borrowed && !borrowed.isRecycled)
    red.recycle()
    check(cache.softwareIcon(red) == null)
    check(!borrowed.isRecycled) { "Retiring the source recycled an outstanding borrowed copy" }
    println("PASS: hardware direct draw, CPU copy reuse, metadata/source updates, mutable passthrough, byte/entry LRU limits, oversize bypass, preview fallback pixels, temporary-copy ownership")
}
