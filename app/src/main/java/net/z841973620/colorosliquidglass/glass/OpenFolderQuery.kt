package net.z841973620.colorosliquidglass.glass

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Cache the OEM API contract, never the changing open-folder state. */
internal object OpenFolderQuery {
    private const val QUERY_CLASS = "com.android.launcher3.AbstractFloatingView"
    private const val CONTEXT_CLASS = "com.android.launcher3.views.ActivityContext"
    private const val FOLDER_CLASS = "com.android.launcher3.folder.Folder"

    class Result(val available: Boolean, val folder: View?)

    @SuppressLint("StaticFieldLeak")
    private val unavailable = Result(false, null)
    @SuppressLint("StaticFieldLeak")
    private val noOpenFolder = Result(true, null)
    private class Lookup(val method: Method?, val failure: String)

    // ClassValue separates different OEM class loaders without retaining a Launcher instance.
    private val methods = @RequiresApi(34)
    object : ClassValue<Lookup>() {
        override fun computeValue(type: Class<*>): Lookup {
            PopupCaptureTrace.outcome("folder_query_cache", "resolve")
            try {
                //注意：请ai还是写英文注释以便区分
                //这一段代码是用于解决长按菜单栏卡顿的问题的
                //具体作用是 确认“没有打开的文件夹”后结束查询，接口不可用时保留兼容扫描。同时缓存反射方法，避免每次采样都重新查找
                val context = Class.forName(CONTEXT_CLASS, false, type.classLoader)
                //我不太清楚这里的最后一个参数，但是这里并不是一个xposed模块，它应该能找到自己作用域里面的类？
                //所以最后一个参数type.classLoader  其实不是必要的？
                //ai解释说这里的 type 是传入的 launcher.javaClass，使用 type.classLoader 才能在宿主桌面的命名空间下找到这个类，那前提应该是老的xposed模块，而不是新的api？
                //不应该啊（（
                if (!context.isAssignableFrom(type)) return Lookup(null, "signature_mismatch")
                //ai说这里判断 type（即当前的 launcher 实例类，例如 QuickstepLauncher）是否实现了 context（ActivityContext）接口，相当于运行时的 launcher
                //  instanceof ActivityContext
                //简单地理解应该是，launcher 实例类必须实现 ActivityContext 接口，否则就返回 signature_mismatch
                // 就是判断当前实例类是否是 ActivityContext 的子类
                val owner = Class.forName(QUERY_CLASS, false, type.classLoader)
                //加载持有该方法的宿主类
                //**不理解宿主类**
                val method = owner.getMethod("getOpenFolder", context)
                //匹配形参
                if (!Modifier.isStatic(method.modifiers) || method.returnType.name != FOLDER_CLASS ||
                    !View::class.java.isAssignableFrom(method.returnType)) {
                    return Lookup(null, "signature_mismatch")
                }
                return Lookup(method, "")
            } catch (_: ClassNotFoundException) {
                return Lookup(null, "class_missing")
            } catch (_: NoSuchMethodException) {
                return Lookup(null, "method_missing")
            } catch (_: SecurityException) {
                return Lookup(null, "access_denied")
                //虽然不大可能....
            } catch (_: LinkageError) {
                // An OEM signature may reference a type absent from this process's class loader.
                return Lookup(null, "linkage_failed")
            }
        }
    }

    @JvmStatic fun query(launcher: Any): Result {
        val lookup = methods.get(launcher.javaClass)
        val method = lookup.method
        if (method == null) {
            PopupCaptureTrace.outcome(QUERY_CLASS, lookup.failure)
            return unavailable
        }
        try {
            val folder = method.invoke(null, launcher) as View?
            PopupCaptureTrace.outcome(QUERY_CLASS, if (folder == null) "returned_null" else "found")
            return if (folder == null) noOpenFolder else Result(true, folder)
        } catch (_: IllegalAccessException) {
            PopupCaptureTrace.outcome(QUERY_CLASS, "access_denied")
        } catch (_: InvocationTargetException) {
            // The OEM query can fail during initialization/teardown. Use the existing scan for
            // this sample; keep the Method so the next sample can query current state again.
            PopupCaptureTrace.outcome(QUERY_CLASS, "invoke_failed")
        } catch (_: LinkageError) {
            PopupCaptureTrace.outcome(QUERY_CLASS, "linkage_failed")
        }
        return unavailable
    }
}
