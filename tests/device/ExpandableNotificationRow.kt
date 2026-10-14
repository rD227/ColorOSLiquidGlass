package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.widget.FrameLayout

/** Probe-only OEM contract fixture, never packaged in the application. */
class ExpandableNotificationRow(context: Context) : FrameLayout(context) {
    var headsUp = false
    var disappearing = false
    var summaryWithChildren = false
    var collapsedHeight = 310
    fun isHeadsUpState(): Boolean = headsUp || disappearing
    fun isSummaryWithChildren(): Boolean = summaryWithChildren
}
