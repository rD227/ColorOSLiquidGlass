package net.z841973620.colorosliquidglass.glass

import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import dalvik.system.PathClassLoader

/** Resolve hook contracts from the phone's APK without installing hooks or restarting SystemUI. */
fun main(arguments: Array<String>) {
    // The other probe DEX deliberately contains a fake row; bypass it for this OEM check.
    val loader = PathClassLoader(arguments.single(), ClassLoader.getSystemClassLoader().parent)
    val row = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
    val header = loader.loadClass("com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp")
    header.getDeclaredMethod("getHeaderBgDrawable", View::class.java, Boolean::class.javaPrimitiveType, row)
    check(row.getMethod("getTopCornerRadius").returnType == Float::class.javaPrimitiveType)
    check(row.getMethod("getCollapsedHeight").returnType == Int::class.javaPrimitiveType)
    check(row.getMethod("isSummaryWithChildren").returnType == Boolean::class.javaPrimitiveType)
    NotificationGroupGeometry(
        loader.loadClass("com.android.systemui.statusbar.notification.row.ActivatableNotificationView"),
        loader.loadClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView"))
    val banner = loader.loadClass("com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBannerContainer")
    banner.getDeclaredMethod("onViewAttachedToWindow", View::class.java)
    check(View::class.java.isAssignableFrom(banner.getDeclaredField("mFullScreenBanner").type))
    banner.getDeclaredField("mFullScreenBannerBg")
    check(banner.getDeclaredField("mUseBlurBannerBg").type == Boolean::class.javaPrimitiveType)
    println("PASS: OEM group-header factory, row radius and fullscreen banner hook contracts")

    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    val headerType = Class.forName("android.view.NotificationHeaderView")
    val headerView = headerType.getConstructor(Context::class.java).newInstance(context) as ViewGroup
    val setBackground = headerType.getMethod("setHeaderBackgroundDrawable", Drawable::class.java)
    val content = View(context).apply { setBackgroundColor(Color.RED) }
    headerView.layout(0, 0, 128, 64)
    headerView.addView(content)
    content.layout(16, 16, 32, 32)
    val output = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
    setBackground.invoke(headerView, ColorDrawable(Color.BLUE))
    headerView.draw(Canvas(output))
    check(output.getPixel(64, 32) == Color.BLUE)
    setBackground.invoke(headerView, null)
    output.eraseColor(Color.TRANSPARENT)
    headerView.draw(Canvas(output))
    check(headerView.willNotDraw())
    check(output.getPixel(64, 32) == Color.TRANSPARENT) { "Null header retained an old plate" }
    check(output.getPixel(20, 20) == Color.RED) { "Suppressing the header plate hid its content" }
    check(headerView.childCount == 1 && content.visibility == View.VISIBLE)
    output.recycle()
    println("PASS: actual framework null header background removes only the plate and retains child drawing")
}
