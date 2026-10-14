package net.z841973620.colorosliquidglass.glass

import android.view.View

/** Group plates share the collapsed material scale, even while their draw bounds animate. */
class NotificationRefractionScale(loader: ClassLoader) {
    private val rowType = Class.forName(
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, loader)
    private val summaryWithChildren = rowType.getMethod("isSummaryWithChildren")
    private val collapsedHeight = rowType.getMethod("getCollapsedHeight")

    fun referenceSize(host: View): Float {
        var ancestor: View? = host
        while (ancestor != null) {
            if (rowType.isInstance(ancestor) && summaryWithChildren.invoke(ancestor) as Boolean) {
                return minOf(ancestor.width, collapsedHeight.invoke(ancestor) as Int).toFloat()
            }
            ancestor = ancestor.parent as? View
        }
        // Ungrouped cards and banners keep their ordinary bounds-relative material.
        return 0f
    }
}
