package net.z841973620.colorosliquidglass.hook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import net.z841973620.colorosliquidglass.GlassConfig
import java.lang.reflect.Executable
import java.util.function.Supplier

/** 每个模块入口共享一份 Hook 注册表、配置来源和主线程调度器。 */
class HookContext(
    private val module: XposedModule,
    private val preferences: Supplier<SharedPreferences?>
) {
    private val hooked = HashSet<Executable>()
    @JvmField val mainHandler = Handler(Looper.getMainLooper())

    fun currentConfig(): GlassConfig {
        return try {
            val stored = preferences.get()
            if (stored == null) GlassConfig() else GlassConfig.read(stored)
        } catch (error: Throwable) {
            log(5, TAG, "Remote preferences refresh failed", error)
            GlassConfig()
        }
    }

    fun enabled(): Boolean = currentConfig().enabled

    fun menuStyleEnabled(): Boolean {
        val config = currentConfig()
        return config.enabled && config.modifyMenuStyle
    }

    fun hookOnce(executable: Executable, callback: XposedInterface.Hooker) {
        synchronized(hooked) {
            if (!hooked.add(executable)) return
            executable.isAccessible = true
            module.hook(executable).intercept(callback)
            log(4, TAG, "Hooked: ${executable.declaringClass.name}#${executable.name}")
        }
    }

    fun after(className: String, loader: ClassLoader, methodName: String, action: HookAction) {
        try {
            val type = Class.forName(className, false, loader)
            var found = false
            for (method in type.declaredMethods) {
                if (method.name != methodName || method.isBridge || method.isSynthetic) continue
                found = true
                hookOnce(method) { chain ->
                    val result = chain.proceed()
                    try {
                        action.run(chain.thisObject)
                    } catch (error: Throwable) {
                        log(5, TAG, "$className.$methodName apply failed", error)
                    }
                    result
                }
            }
            if (!found) log(5, TAG, "Method not found: $className.$methodName")
        } catch (error: Throwable) {
            log(5, TAG, "Class unavailable: $className", error)
        }
    }

    fun log(level: Int, tag: String, message: String) {
        module.log(level, tag, message)
    }

    fun log(level: Int, tag: String, message: String, error: Throwable) {
        module.log(level, tag, message, error)
    }

    // 旧版接入保持原来的参数顺序，业务 Hook 无需为了共用注册器而重写。
    fun after(loader: ClassLoader, className: String, methodName: String, action: HookAction) {
        after(className, loader, methodName, action)
    }

    fun log(level: Int, message: String) = log(level, TAG, message)

    fun log(level: Int, message: String, error: Throwable) = log(level, TAG, message, error)

    fun interface HookAction {
        @Throws(Throwable::class)
        fun run(instance: Any?)
    }

    companion object {
        const val TAG = "ColorOSLiquidGlass"
    }
}
