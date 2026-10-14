package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow

fun main() {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    val group = ExpandableNotificationRow(context).apply { summaryWithChildren = true }
    val children = FrameLayout(context)
    val child = ExpandableNotificationRow(context)
    val card = View(context)
    val header = View(context)
    val childCard = View(context)
    group.addView(card)
    group.addView(children)
    children.addView(header)
    children.addView(child)
    child.addView(childCard)
    val scale = NotificationRefractionScale(group.javaClass.classLoader!!)

    // Start already expanded: correctness must not depend on a cached collapsed frame.
    for (height in listOf(799, 310, 432, 624, 798, 799, 310)) {
        group.layout(0, 0, 1090, height)
        card.layout(0, 0, 1090, height)
        header.layout(0, 0, 1090, 154)
        childCard.layout(0, 0, 1090, 214)
        for (plate in listOf(card, header, childCard)) {
            check(scale.referenceSize(plate) == 310f) {
                "Group material followed animated bounds: height=$height plate=${plate.javaClass}"
            }
        }
    }
    group.collapsedHeight = 350
    check(scale.referenceSize(header) == 350f) { "A real content change retained stale material size" }
    group.layout(0, 0, 280, 799)
    check(scale.referenceSize(card) == 280f && scale.referenceSize(header) == 280f)
    children.removeView(child)
    check(scale.referenceSize(childCard) == 0f) { "Ungrouping retained the old group's scale" }
    check(scale.referenceSize(View(context)) == 0f) { "Unrelated glass opted into group scaling" }
    println("PASS: shared group/header/child scale through expansion, already-expanded attachment, content/width changes and ungrouping")
}
