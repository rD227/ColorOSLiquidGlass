package net.z841973620.colorosliquidglass.glass

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import net.z841973620.colorosliquidglass.GlassConfig
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 注册表：同一通知窗口中的卡片、分组标题共用一个采样会话。 */
internal object SharedNotificationBackdrop {
    private val roots = WeakHashMap<View, ShadeSession>()
    private val clients = WeakHashMap<View, ShadeSession>()

    @JvmStatic fun bind(view: View, config: GlassConfig) {
        val root = view.rootView
        val source = roots.getOrPut(root) { ShadeSession(root, config) }
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
    @JvmStatic fun texture(view: View): Bitmap? {
        val source = clients[view] ?: return null
        return source.currentFrame()
    }

    /** 窗口会话：可见性决定何时开始/释放，采样频率决定何时再次请求绘制。 */
    private class ShadeSession(view: View, var config: GlassConfig) :
        ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener,
        View.OnAttachStateChangeListener, Runnable {
        private val root = WeakReference(view)
        val views = WeakHashMap<View, Boolean>()
        private var refreshScheduled = false
        private var captureSessionActive = false
        private val windowVisibilityObserver = if (Build.VERSION.SDK_INT >= 34)
            WindowVisibilityObserver(view) { stopCaptureSession() } else null
        init {
            view.viewTreeObserver.addOnPreDrawListener(this)
            view.viewTreeObserver.addOnGlobalLayoutListener(this)
            view.addOnAttachStateChangeListener(this)
        }

        override fun onPreDraw(): Boolean {
            val host = root.get() ?: return true
            if (views.isEmpty()) {
                close()
                return true
            }
            val visibleViews = views.keys.filter { visibleOnScreen(it) }
            if (visibleViews.isEmpty()) {
                stopCaptureSession()
                return true
            }

            requestCapture(host)
            publishNewFrame(host, visibleViews)
            scheduleNextRefresh(host)
            return true
        }

        fun currentFrame(): Bitmap? {
            val host = root.get() ?: return null
            return BehindDisplayCapture.currentFrame(host)
        }

        private fun requestCapture(host: View) {
            val blurRadiusPx = config.blurRadius * host.resources.displayMetrics.density
            BehindDisplayCapture.requestShade(host, blurRadiusPx, config.notificationBackdropFps)
            captureSessionActive = true
        }

        private fun publishNewFrame(host: View, visibleViews: List<View>) {
            if (!BehindDisplayCapture.hasNewFrame(host)) return
            BehindDisplayCapture.markPublished(host)
            for (view in visibleViews) view.invalidate()
        }

        private fun scheduleNextRefresh(host: View) {
            val rateHz = config.notificationBackdropFps
            if (rateHz == 0) {
                cancelScheduledRefresh(host)
                return
            }
            if (refreshScheduled) return
            // 向上取整，避免毫秒计时器在采样期限前被唤醒。
            val delayMillis = (1000L + rateHz - 1) / rateHz
            refreshScheduled = true
            host.postDelayed(this, delayMillis)
        }

        private fun cancelScheduledRefresh(host: View) {
            host.removeCallbacks(this)
            refreshScheduled = false
        }

        private fun visibleOnScreen(view: View): Boolean {
            if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE
                || !view.isShown || !view.getGlobalVisibleRect(Rect())) return false
            var ancestor: View? = view
            while (ancestor != null) {
                if (ancestor.alpha == 0f) return false
                ancestor = ancestor.parent as? View
            }
            return true
        }

        // 隐藏窗口可能不再触发 PreDraw，因此布局/窗口可见性回调也负责结束本次会话。
        override fun onGlobalLayout() {
            if (views.keys.none { visibleOnScreen(it) }) stopCaptureSession()
        }

        private fun stopCaptureSession() {
            val host = root.get() ?: return
            if (captureSessionActive) BehindDisplayCapture.stopStreaming(host)
            captureSessionActive = false
            cancelScheduledRefresh(host)
        }

        override fun run() {
            refreshScheduled = false
            root.get()?.invalidate()
        }

        fun close() {
            val host = root.get() ?: return
            cancelScheduledRefresh(host)
            if (host.viewTreeObserver.isAlive) {
                host.viewTreeObserver.removeOnPreDrawListener(this)
                host.viewTreeObserver.removeOnGlobalLayoutListener(this)
                windowVisibilityObserver?.remove(host)
            }
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

    /** API 34 才提供窗口可见性监听，把该接口的类型隔离在按版本创建的对象中。 */
    private class WindowVisibilityObserver(view: View, onHidden: () -> Unit) {
        private val listener = ViewTreeObserver.OnWindowVisibilityChangeListener { visibility ->
            if (visibility != View.VISIBLE) onHidden()
        }

        init { view.viewTreeObserver.addOnWindowVisibilityChangeListener(listener) }

        fun remove(view: View) {
            view.viewTreeObserver.removeOnWindowVisibilityChangeListener(listener)
        }
    }
}
