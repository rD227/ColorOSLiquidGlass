package net.z841973620.colorosliquidglass.glass

import android.view.View
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
    val banner = loader.loadClass("com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBannerContainer")
    banner.getDeclaredMethod("onViewAttachedToWindow", View::class.java)
    check(View::class.java.isAssignableFrom(banner.getDeclaredField("mFullScreenBanner").type))
    banner.getDeclaredField("mFullScreenBannerBg")
    check(banner.getDeclaredField("mUseBlurBannerBg").type == Boolean::class.javaPrimitiveType)
    println("PASS: OEM group-header factory, row radius and fullscreen banner hook contracts")
}
