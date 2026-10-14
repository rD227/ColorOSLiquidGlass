package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import net.z841973620.colorosliquidglass.GlassConfig

private class FolderIcon(context: Context) : FrameLayout(context) {
    @JvmField val mPreviewItemManager = PopupPreview()
}

private class PopupPreview {
    var color = Color.RED
    fun draw(canvas: Canvas) {
        canvas.drawRect(32f, 32f, 64f, 64f, Paint().apply { color = this@PopupPreview.color })
    }
}

private fun checkFolderHardwareComposite(context: Context, root: FrameLayout) {
    val folder = FolderIcon(context)
    val plate = View(context)
    root.addView(folder)
    folder.addView(plate)
    folder.layout(0, 0, 128, 128)
    plate.layout(0, 0, 128, 128)
    plate.background = GlassDrawable(plate, GlassConfig())
    val captures = BackdropCapture::class.java.getDeclaredField("CAPTURES").apply { isAccessible = true }.get(null) as Map<*, *>
    val capture = captures[plate] as BackdropCapture
    check(capture.usesSharedWallpaper())
    val composite = DesktopIconOverlay::class.java.getDeclaredMethod(
        "folderCompositeBitmap", View::class.java, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
    ).apply { isAccessible = true }
    fun render(): Bitmap = composite.invoke(null, folder, 128, 128, true, true) as Bitmap
    val first = render()
    check(Color.red(first.getPixel(48, 48)) > 240) { "Folder preview was not rendered" }
    for (name in listOf("frontBitmap", "backBitmap")) {
        check(BackdropCapture::class.java.getDeclaredField(name).apply { isAccessible = true }.get(capture) == null) {
            "Hardware folder composite unnecessarily materialized a software wallpaper: $name"
        }
    }
    folder.mPreviewItemManager.color = Color.BLUE
    val second = render()
    check(second !== first && Color.blue(second.getPixel(48, 48)) > 240) {
        "Shared wallpaper must not turn null plateSource into a stale folder cache hit"
    }
    // Software consumers still explicitly request a local bitmap.
    BackdropCapture.snapshotOf(plate)
    val front = BackdropCapture::class.java.getDeclaredField("frontBitmap").apply { isAccessible = true }.get(capture)
    val back = BackdropCapture::class.java.getDeclaredField("backBitmap").apply { isAccessible = true }.get(capture)
    check(front != null || back != null) { "Explicit software snapshot no longer materializes a bitmap" }
    // OEM may remove the live plate during a menu transition; retain the last glass composite.
    GlassInstaller.uninstall(plate)
    check(render() === second) { "OEM plate removal discarded retained glass" }
    DesktopIconOverlay.clearFolderSnaps()
    root.removeView(folder)
}

private object OpenFolderQueryFixture {
    @JvmStatic fun empty(launcher: Any): View? = null
    @JvmStatic fun fails(launcher: Any): View? = error("Expected probe failure")
}

private fun checkCaptureTrace(host: View) {
    if (!android.util.Log.isLoggable("ColgPopupPerf", android.util.Log.DEBUG)) {
        check(PopupCaptureTrace.section("disabled") == null)
        return
    }
    val start = PopupCaptureTrace.begin(host, true)
    check(start != 0L)
    PopupCaptureTrace.section("test_outer").use {
        check(PopupCaptureTrace.section("test_outer") == null) { "Recursive search allocated another timer" }
        PopupCaptureTrace.section("test_inner").use {
            android.os.SystemClock.sleep(15)
        }
    }
    // Exercise the real reflection query: a successful null result differs from API failure.
    val query = DesktopIconOverlay::class.java.getDeclaredMethod(
        "invokeOpenFolder", Any::class.java, ClassLoader::class.java, String::class.java, String::class.java
    ).apply { isAccessible = true }
    val fixture = OpenFolderQueryFixture::class.java
    for (method in listOf("empty", "fails", "missing")) {
        check(query.invoke(null, Any(), fixture.classLoader, fixture.name, method) == null)
    }
    check(query.invoke(null, Any(), fixture.classLoader, fixture.name + "Absent", "empty") == null)
    PopupCaptureTrace.end(start, host, true)
    val resultsField = PopupCaptureTrace::class.java.getDeclaredField("outcomes").apply { isAccessible = true }
    val results = resultsField.get(null) as Map<*, *>
    check(results[fixture.name + ".returned_null"] == 1)
    check(results[fixture.name + ".invoke_failed"] == 1)
    check(results[fixture.name + ".method_missing"] == 1)
    check(results[fixture.name + "Absent.class_missing"] == 1)
    val field = PopupCaptureTrace::class.java.getDeclaredField("stages").apply { isAccessible = true }
    val stages = field.get(null) as Map<*, *>
    val outer = stages["test_outer"] as PopupCaptureTrace.Totals
    val inner = stages["test_inner"] as PopupCaptureTrace.Totals
    check(outer.calls == 1 && inner.calls == 1)
    check(inner.wall >= 10_000_000L) { "Nested timer lost the measured wait" }
    check(PopupCaptureTrace.section("finished") == null)
    println("PASS: opt-in stage timers, nested scopes, recursion collapse and cleanup")
    val next = PopupCaptureTrace.begin(host, true)
    check(next != 0L && results.isEmpty()) { "Query outcomes leaked into another sample" }
    PopupCaptureTrace.end(next, host, true)
    println("PASS: successful null query, missing class/method, failed invocation and per-sample reset")
}

fun main() {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    val root = FrameLayout(context)
    root.layout(0, 0, 256, 256)
    val card = View(context)
    val header = View(context)
    root.addView(card)
    root.addView(header)
    card.layout(0, 32, 128, 96)
    header.layout(0, 0, 128, 32)
    val config = GlassConfig().apply { notificationAppBackdrop = true }
    SharedNotificationBackdrop.bind(card, config)
    SharedNotificationBackdrop.bind(header, config)
    val field = SharedNotificationBackdrop::class.java.getDeclaredField("clients").apply { isAccessible = true }
    val clients = field.get(null) as Map<*, *>
    check(clients[card] === clients[header]) { "Cards must share a single capture source" }
    SharedNotificationBackdrop.unbind(card)
    check(SharedNotificationBackdrop.contains(header))
    SharedNotificationBackdrop.unbind(header)
    val roots = SharedNotificationBackdrop::class.java.getDeclaredField("roots").apply { isAccessible = true }
    check((roots.get(null) as Map<*, *>).isEmpty()) { "Last detach must release shade source" }

    // Exercise the actual GlassDrawable branch, not just a standalone RenderEffect.
    val source = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
    for (y in 0 until 64) for (x in 0 until 128) source.setPixel(x, y, if (x < 64) Color.BLACK else Color.WHITE)
    card.tag = "colg_desktop_popup"
    card.layout(0, 0, 128, 64)
    config.glassIntensity = 0f
    config.blurRadius = 8f / context.resources.displayMetrics.density
    val glass = GlassDrawable(card, config)
    glass.setBounds(0, 0, 128, 64)
    val captures = BackdropCapture::class.java.getDeclaredField("CAPTURES").apply { isAccessible = true }.get(null) as Map<*, *>
    val capture = captures[card]!!
    BackdropCapture::class.java.getDeclaredField("frontBitmap").apply { isAccessible = true }.set(capture, source)
    BackdropCapture::class.java.getDeclaredField("validFrame").apply { isAccessible = true }.setBoolean(capture, true)
    val sourceRevision = source.generationId
    repeat(8) { GlassInstaller.requestPopupCapture(card) }
    check(BackdropCapture::class.java.getDeclaredField("backBitmap").apply { isAccessible = true }.get(capture) == null) {
        "Opening callbacks must not allocate or capture before PreDraw"
    }
    check(BackdropCapture::class.java.getDeclaredField("frontBitmap").apply { isAccessible = true }.get(capture) === source)
    check(source.generationId == sourceRevision)
    fun render(): Bitmap = GpuBackdropTexture.render(128, 64, 0f) { glass.draw(it) }.copy(Bitmap.Config.ARGB_8888, false)!!
    val blurred = render()
    val nearEdge = Color.red(blurred.getPixel(60, 32))
    check(nearEdge in 20..120) { "GPU popup blur missing: $nearEdge" }
    check(Color.red(blurred.getPixel(12, 32)) < 5)
    check(Color.red(blurred.getPixel(116, 32)) > 250)
    val cpuCache = GlassDrawable::class.java.getDeclaredField("cachedBlurredSnapshot").apply { isAccessible = true }
    check(cpuCache.get(glass) == null) { "Popup still allocated a CPU blur" }
    config.blurRadius = 0f
    val sharp = render()
    check(Color.red(sharp.getPixel(60, 32)) < 5) { "Zero radius must bypass blur" }
    source.eraseColor(Color.RED)
    val changed = render()
    check(Color.red(changed.getPixel(60, 32)) > 240 && Color.blue(changed.getPixel(60, 32)) < 5) {
        "GPU popup reused a stale source"
    }
    // A header excludes only its bottom seam. Check all pixels against independent
    // fully-refracting and zero-displacement renders through the production GPU path.
    for (y in 0 until 64) for (x in 0 until 128) {
        source.setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) Color.BLACK else Color.WHITE)
    }
    config.glassIntensity = 1f
    config.refractionHeight = 0.5f
    config.refractionAmount = 0.35f
    config.chromaticAberration = 0.6f
    val refracted = render()
    glass.setBottomRefractionEnabled(false)
    val bottomExcluded = render()
    check(config.refractionAmount == 0.35f) { "Header changed shared notification config" }
    config.refractionAmount = 0f
    val zeroAmount = render()
    var removedBottomPixels = 0
    var retainedEdgePixels = 0
    for (y in 0 until 64) for (x in 0 until 128) {
        val bottomDistance = 64f - (y + 0.5f)
        val sideDistance = minOf(x + 0.5f, 128f - (x + 0.5f))
        val actual = bottomExcluded.getPixel(x, y)
        if (y >= 32 && bottomDistance == sideDistance) {
            // On the exact corner bisector both edges are equally near. Raster transform
            // roundoff can choose either edge; neither choice may introduce a third color.
            check(actual == zeroAmount.getPixel(x, y) || actual == refracted.getPixel(x, y)) {
                "Header corner bisector produced an unrelated color at $x,$y"
            }
            continue
        }
        val onBottom = y >= 32 && bottomDistance < sideDistance
        val expected = if (onBottom) zeroAmount.getPixel(x, y) else refracted.getPixel(x, y)
        check(actual == expected) {
            "Header bottom exclusion changed an unexpected pixel at $x,$y bottom=$onBottom"
        }
        if (onBottom && refracted.getPixel(x, y) != expected) removedBottomPixels++
        if (!onBottom && expected != zeroAmount.getPixel(x, y)) retainedEdgePixels++
    }
    check(removedBottomPixels > 100) { "Pattern did not exercise bottom refraction/dispersion" }
    check(retainedEdgePixels > 100) { "Top and side refraction/dispersion disappeared" }
    config.refractionAmount = 0.35f
    glass.setBottomRefractionEnabled(true)
    val restored = render()
    for (y in 0 until 64) for (x in 0 until 128) {
        check(restored.getPixel(x, y) == refracted.getPixel(x, y)) {
            "Re-enabling optical displacement failed at $x,$y"
        }
    }
    listOf(refracted, bottomExcluded, zeroAmount, restored).forEach { it.recycle() }
    println("PASS: only header bottom optics removed; top/side optics, lighting/opacity, shared config and default behavior retained")
    BackdropCapture.unregister(card)
    checkCaptureTrace(card)
    checkFolderHardwareComposite(context, root)
    println("PASS: shared card/header ownership, cleanup, coalesced popup requests, GPU blur, zero radius, live updates, no CPU blur")
    println("PASS: no unused folder wallpaper bitmap, fresh previews, explicit software snapshots, retained OEM transition glass")
}
