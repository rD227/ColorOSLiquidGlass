package net.z841973620.colorosliquidglass.glass

import android.view.View

/** OEM boundary: resolve the row API once, including its disappearing heads-up animation. */
class HeadsUpState(loader: ClassLoader) {
    private val rowType = Class.forName(
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, loader)
    private val headsUp = rowType.getMethod("isHeadsUpState")

    fun isHeadsUp(background: View): Boolean {
        var ancestor: View? = background
        while (ancestor != null) {
            if (rowType.isInstance(ancestor)) return headsUp.invoke(ancestor) as Boolean
            ancestor = ancestor.parent as? View
        }
        return false
    }
}
