package net.z841973620.colorosliquidglass.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import net.z841973620.colorosliquidglass.GlassConfig
import java.util.function.BooleanSupplier
import java.util.function.DoubleSupplier
import java.util.function.Consumer
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**  Composition: OEM header/banner owns its bounds; GlassDrawable owns the material.
 * 用于保存整个整个 GlassConfig 对象传入的config.refractionHeight  .refractionAmount  .glassIntensity等参数*/
class NotificationPlateDrawable(
    private val host: View,
    private val config: GlassConfig,
    private val headsUp: BooleanSupplier,
    private val radius: DoubleSupplier,
    private val header: Boolean
    //类 private val 接收参数，同时保存为这个对象的私有属性
) : Drawable(), View.OnAttachStateChangeListener {
    companion object { private val installed = WeakHashMap<View, WeakReference<NotificationPlateDrawable>>() }
    private var glass: GlassDrawable? = null
    private val source = NotificationGlassSource(host)
    private var plateAlpha = 255
    private val trace = NotificationGlassTrace(host, if (header) "header" else "banner")
    var refractionReferenceSize: DoubleSupplier? = null
    var groupGeometry: Consumer<GlassDrawable>? = null

    init {
        installed[host]?.get()?.let { previous ->
            previous.onViewDetachedFromWindow(host)
            host.removeOnAttachStateChangeListener(previous)
        }
        installed[host] = WeakReference(this)
        host.addOnAttachStateChangeListener(this)
    }

    override fun draw(canvas: Canvas) {
        val material = glass ?: GlassDrawable(host, config).also {
            it.callback = host
            it.setNotificationTrace(trace)
            glass = it
        }
        source.update(material, config, headsUp.asBoolean)
        material.setRefractionReferenceSize(refractionReferenceSize?.asDouble?.toFloat() ?: 0f)
        val geometry = groupGeometry
        material.setBottomRefractionEnabled(geometry != null || !header)
        if (geometry != null) {
            geometry.accept(material)
        } else {
            val corner = radius.asDouble.toFloat()
            material.setCornerRadii(corner, corner,
                if (header) 0f else corner, if (header) 0f else corner)
            material.bounds = bounds
        }
        material.alpha = plateAlpha
        val save = canvas.save()
        try {
            // Draw only the header slice of the full group lens, including its
            // original top edge. The header's lower seam is not a material boundary.
            canvas.clipRect(bounds)
            material.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) { plateAlpha = alpha; invalidateSelf() }
    override fun getAlpha() = plateAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) { glass?.colorFilter = colorFilter }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) {
        source.release()
        glass?.callback = null
        glass = null
        BackdropCapture.unregister(host)
    }
}
