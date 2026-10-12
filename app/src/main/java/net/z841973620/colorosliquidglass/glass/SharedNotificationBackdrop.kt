package net.z841973620.colorosliquidglass.glass

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import net.z841973620.colorosliquidglass.GlassConfig
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One excluded-window capture per shade, shared by every card and group header. */
internal object SharedNotificationBackdrop {
    private val roots = WeakHashMap<View, Source>()
    private val clients = WeakHashMap<View, Source>()

    @JvmStatic fun bind(view: View, config: GlassConfig) {
        val root = view.rootView
        val source = roots.getOrPut(root) { Source(root, config) }
        if (clients[view] !== source) {
            unbind(view)
            source.views[view] = true
            clients[view] = source
        }
        source.config = config
    }

    @JvmStatic fun unbind(view: View) {
        val source = clients.remove(view) ?: return
        source.views.remove(view)
        if (source.views.isEmpty()) source.close()
    }

    @JvmStatic fun contains(view: View) = clients.containsKey(view)
    @JvmStatic fun texture(view: View): Bitmap? = clients[view]?.root?.get()?.let {
        BehindDisplayCapture.currentFrame(it)
    }

    private class Source(view: View, var config: GlassConfig) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener, Runnable {
        val root = WeakReference(view)
        val views = WeakHashMap<View, Boolean>()
        private var scheduled = false
        private var streaming = false
        init {
            view.viewTreeObserver.addOnPreDrawListener(this)
            view.addOnAttachStateChangeListener(this)
        }

        override fun onPreDraw(): Boolean {
            val host = root.get() ?: return true
            if (views.isEmpty()) { close(); return true }
            val visible = views.keys.filter { visibleOnScreen(it) }
            if (visible.isEmpty()) {
                if (streaming) BehindDisplayCapture.stopStreaming(host)
                streaming = false
                host.removeCallbacks(this)
                scheduled = false
                return true
            }
            BehindDisplayCapture.requestShade(host, config.blurRadius * host.resources.displayMetrics.density,
                config.notificationBackdropFps)
            streaming = true
            if (BehindDisplayCapture.hasNewFrame(host)) {
                BehindDisplayCapture.markPublished(host)
                visible.forEach { it.invalidate() }
            }
            if (!scheduled) {
                scheduled = true
                host.postDelayed(this, (1000L + config.notificationBackdropFps - 1) / config.notificationBackdropFps)
            }
            return true
        }

        private fun visibleOnScreen(view: View): Boolean {
            if (!view.isAttachedToWindow || !view.isShown || !view.getGlobalVisibleRect(Rect())) return false
            var ancestor: View? = view
            while (ancestor != null) {
                if (ancestor.alpha == 0f) return false
                ancestor = ancestor.parent as? View
            }
            return true
        }

        override fun run() {
            scheduled = false
            root.get()?.invalidate()
        }

        fun close() {
            val host = root.get() ?: return
            host.removeCallbacks(this)
            if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(this)
            host.removeOnAttachStateChangeListener(this)
            BehindDisplayCapture.stopStreaming(host)
            roots.remove(host)
        }

        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            views.keys.toList().forEach { clients.remove(it) }
            views.clear()
            close()
        }
    }
}
