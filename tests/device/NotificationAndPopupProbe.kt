package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import net.z841973620.colorosliquidglass.GlassConfig

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
    BackdropCapture.unregister(card)
    println("PASS: shared card/header ownership, cleanup, coalesced popup requests, GPU blur, zero radius, live updates, no CPU blur")
}
