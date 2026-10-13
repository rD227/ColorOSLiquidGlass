package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.folder.Folder
import com.android.launcher3.views.ActivityContext

private class QueryLauncher : ActivityContext
private class Workspace(context: Context, @JvmField val mLauncher: Any) : FrameLayout(context)

fun main(arguments: Array<String>) {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val activityThread = Class.forName("android.app.ActivityThread")
    val thread = activityThread.getMethod("systemMain").invoke(null)
    val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
    val launcher = QueryLauncher()
    val workspace = Workspace(context, launcher)
    workspace.layout(0, 0, 256, 256)
    val scannedFolder = Folder(context).apply { layout(0, 0, 128, 128) }
    workspace.addView(scannedFolder)
    // Deliberately conflicting legacy tree: proves that authoritative absence skips fallback.
    val search = DesktopIconOverlay::class.java.getDeclaredMethod("findOpenFolderView", View::class.java)
        .apply { isAccessible = true }
    fun find(seed: View = workspace): View? = search.invoke(null, seed) as View?
    check(find() == null) { "Successful null still fell through to the View-tree scan" }
    check(AbstractFloatingView.calls == 1 && Folder.legacyCalls == 0)

    val first = Folder(context).apply { layout(0, 0, 128, 128) }
    AbstractFloatingView.folder = first
    check(find() === first) { "Opening a folder reused cached null state" }
    val second = Folder(context).apply { layout(0, 0, 128, 128) }
    AbstractFloatingView.folder = second
    check(find() === second) { "Switching folders reused an old View" }
    AbstractFloatingView.folder = null
    check(find() == null) { "Closing a folder reused the old open state" }
    check(AbstractFloatingView.calls == 4 && Folder.legacyCalls == 0)

    AbstractFloatingView.fail = true
    check(find() === scannedFolder) { "Failed OEM query must retain compatibility scanning" }
    check(Folder.legacyCalls > 0)
    AbstractFloatingView.fail = false
    check(find() == null) { "An invocation failure poisoned later queries" }

    val unsupported = Workspace(context, Any()).apply { layout(0, 0, 256, 256) }
    val fallback = Folder(context).apply { layout(0, 0, 128, 128) }
    unsupported.addView(fallback)
    val callsBefore = AbstractFloatingView.calls
    check(find(unsupported) === fallback) { "Unsupported Launcher contract lost fallback" }
    check(AbstractFloatingView.calls == callsBefore) { "Invoked a method with incompatible argument type" }

    // Each Launcher class retains the Method, not a Folder or Launcher instance.
    val cache = OpenFolderQuery::class.java.getDeclaredField("methods").apply { isAccessible = true }
        .get(null) as ClassValue<*>
    val lookup = cache.get(launcher.javaClass)
    repeat(20) { check(OpenFolderQuery.query(launcher).available) }
    check(cache.get(launcher.javaClass) === lookup) { "Reflection metadata was not reused" }
    println("PASS: authoritative null skips scans, live open/change/close, exact overload, failure recovery, unsupported fallback, Method cache")

    if (arguments.isNotEmpty()) {
        // Bypass the probe's fake OEM classes when checking the actual phone APK.
        val loader = dalvik.system.PathClassLoader(arguments.single(), ClassLoader.getSystemClassLoader().parent)
        val realLauncher = loader.loadClass("com.android.launcher.Launcher")
        val realLookup = cache.get(realLauncher)!!
        val method = realLookup.javaClass.getDeclaredField("method").apply { isAccessible = true }
            .get(realLookup) as java.lang.reflect.Method?
        check(method != null) { "The cached resolver rejected this phone's actual Launcher contract" }
        check(method.declaringClass.classLoader === loader)
        println("PASS: actual OEM contract: ${method.toGenericString()}")
    }
}
