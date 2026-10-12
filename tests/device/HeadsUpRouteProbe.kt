package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import net.z841973620.colorosliquidglass.GlassConfig

fun main() {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    val root = FrameLayout(context)
    val row = ExpandableNotificationRow(context)
    val background = View(context)
    root.addView(row)
    row.addView(background)
    background.layout(0, 0, 128, 96)
    val detector = HeadsUpState(row.javaClass.classLoader!!)
    check(!detector.isHeadsUp(background))
    row.headsUp = true
    check(detector.isHeadsUp(background))
    row.headsUp = false
    row.disappearing = true
    check(detector.isHeadsUp(background))
    row.disappearing = false
    check(!detector.isHeadsUp(background))
    val config = GlassConfig().apply { volumeBackdropFps = 15; menuBackdropEnabled = false }
    val glass = GlassDrawable(background, config)
    val renderer = NotificationCardGlass(FakeNotificationBackground::class.java)
    config.headsUpGlassEnabled = false
    check(renderer.shouldDraw(background, config)) // ordinary cards retain glass
    row.headsUp = true
    check(!renderer.shouldDraw(background, config))
    config.headsUpGlassEnabled = true
    check(renderer.shouldDraw(background, config))
    BehindDisplayCapture.bindHeadsUp(background, glass)
    check(BehindDisplayCapture.isHeadsUpGlass(background))
    check(BehindDisplayCapture.isSysUiLiveGlass(background))
    check(glass.volumeBackdropFps() == 15)
    BehindDisplayCapture.unbindHeadsUp(background)
    check(!BehindDisplayCapture.isSysUiLiveGlass(background))
    background.tag = "colg_desktop_popup"
    check(!glass.backdropEnabled())
    background.background = glass
    BackdropCapture.forceCapture(background)
    val capturesField = BackdropCapture::class.java.getDeclaredField("CAPTURES").apply { isAccessible = true }
    val capture = (capturesField.get(null) as Map<*, *>)[background]!!
    for (name in listOf("frontBitmap", "backBitmap")) {
        val field = BackdropCapture::class.java.getDeclaredField(name).apply { isAccessible = true }
        check(field.get(capture) == null) { "Disabled menu allocated a capture buffer" }
    }
    config.menuBackdropEnabled = true
    check(glass.backdropEnabled())
    config.menuBackdropEnabled = false
    background.tag = null
    BehindDisplayCapture.tagHost(background)
    check(!glass.backdropEnabled())
    background.tag = null
    BehindDisplayCapture.tagVolumeHost(background)
    check(glass.backdropEnabled()) // menu switch must not disable volume
    BackdropCapture.unregister(background)
    println("PASS: heads-up/exit detection, independent toggle, live bind/unbind, shared FPS, menu isolation")
}

/** Matches the adapter's reflective geometry contract; no actual SystemUI classes are modified. */
class FakeNotificationBackground {
    @JvmField var mClipTopAmount = 0
    @JvmField var mClipBottomAmount = 0
    @JvmField var mExpandAnimationRunning = false
    @JvmField var mBottomIsRounded = true
    @JvmField var mBottomAmountClips = false
    @JvmField var mCornerRadii = FloatArray(8)
    @JvmField var mBackground: android.graphics.drawable.Drawable? = null
    fun getActualHeight() = 96
    fun getActualWidth() = 128
    fun isAlignedToRight() = false
}
