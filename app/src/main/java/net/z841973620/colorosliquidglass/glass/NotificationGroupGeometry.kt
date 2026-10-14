package net.z841973620.colorosliquidglass.glass

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/** Projects the group's existing plate geometry into a header's local coordinates. */
class NotificationGroupGeometry(ownerType: Class<*>, backgroundType: Class<*>) {
    private val background = ownerType.getDeclaredField("mBackgroundNormal").apply { isAccessible = true }
    private val actualHeight = backgroundType.getDeclaredMethod("getActualHeight").apply { isAccessible = true }
    private val actualWidth = backgroundType.getDeclaredMethod("getActualWidth").apply { isAccessible = true }
    private val alignedRight = backgroundType.getDeclaredMethod("isAlignedToRight").apply { isAccessible = true }
    private val expanding = backgroundType.getDeclaredField("mExpandAnimationRunning").apply { isAccessible = true }
    private val bottomRounded = backgroundType.getDeclaredField("mBottomIsRounded").apply { isAccessible = true }
    private val bottomClips = backgroundType.getDeclaredField("mBottomAmountClips").apply { isAccessible = true }
    private val clipBottom = backgroundType.getDeclaredField("mClipBottomAmount").apply { isAccessible = true }
    private val radii = backgroundType.getDeclaredField("mCornerRadii").apply { isAccessible = true }
    private val headerPosition = Rect()
    private val backgroundPosition = Rect()

    fun apply(host: View, owner: ViewGroup, material: GlassDrawable) {
        val plate = background.get(owner) as View
        val width = actualWidth.invoke(plate) as Int
        var height = actualHeight.invoke(plate) as Int
        val animation = expanding.getBoolean(plate)
        var left = if (alignedRight.invoke(plate) as Boolean) plate.width - width else 0
        if (animation) left = (plate.width - width) / 2
        if (!animation && bottomRounded.getBoolean(plate) && bottomClips.getBoolean(plate)) {
            height -= clipBottom.getInt(plate)
        }
        // Both are descendants of the same notification row. Common row/window
        // animation transforms cancel; include each child's layout and scroll offsets.
        headerPosition.set(0, 0, 0, 0)
        backgroundPosition.set(0, 0, 0, 0)
        owner.offsetDescendantRectToMyCoords(host, headerPosition)
        owner.offsetDescendantRectToMyCoords(plate, backgroundPosition)
        left += backgroundPosition.left - headerPosition.left
        val top = backgroundPosition.top - headerPosition.top
        material.setBounds(left, top, left + width, top + height)
        val corners = radii.get(plate) as FloatArray
        material.setCornerRadii(corners[0], corners[2], corners[4], corners[6])
    }
}
