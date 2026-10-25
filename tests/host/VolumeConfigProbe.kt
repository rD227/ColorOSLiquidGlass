import android.content.SharedPreferences
import net.z841973620.colorosliquidglass.GlassConfig
import java.lang.reflect.Proxy

fun main() {
    val stored = mutableMapOf<String, Any>()
    val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, arguments ->
        when {
            method.name.startsWith("put") -> {
                stored[arguments[0] as String] = arguments[1]
                proxy
            }
            method.name == "commit" -> true
            else -> error("Unexpected Editor method: ${method.name}")
        }
    } as SharedPreferences.Editor
    val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, arguments ->
        when {
            method.name == "edit" -> editor
            method.name.startsWith("get") -> stored[arguments[0] as String] ?: arguments[1]
            else -> error("Unexpected preferences method: ${method.name}")
        }
    } as SharedPreferences
    check(GlassConfig.read(preferences).volumeBackdropFps == 30)
    check(GlassConfig().notificationBackdropFps == 0)
    check(GlassConfig.read(preferences).notificationBackdropFps == 0)
    for (fps in listOf(10, 15, 30, 60, 90, 120)) {
        val config = GlassConfig()
        config.volumeBackdropFps = fps
        config.headsUpGlassEnabled = fps == 30
        config.menuBackdropEnabled = fps == 60
        check(config.write(preferences))
        check(stored["volume_backdrop_fps"] == fps)
        check(GlassConfig.read(preferences).volumeBackdropFps == fps)
        check(GlassConfig.read(preferences).headsUpGlassEnabled == config.headsUpGlassEnabled)
        check(GlassConfig.read(preferences).menuBackdropEnabled == config.menuBackdropEnabled)
    }
    stored["volume_backdrop_fps"] = 0
    check(!GlassConfig().notificationAppBackdrop)
    for (fps in listOf(0, 1, 2, 5, 10, 15, 30)) {
        val config = GlassConfig().apply { notificationAppBackdrop = true; notificationBackdropFps = fps }
        check(config.write(preferences))
        check(GlassConfig.read(preferences).notificationAppBackdrop)
        check(GlassConfig.read(preferences).notificationBackdropFps == fps)
    }
    for (fps in listOf(-1, 24, Int.MAX_VALUE)) {
        stored["notification_backdrop_fps"] = fps
        check(GlassConfig.read(preferences).notificationBackdropFps == 0)
    }
    stored["volume_backdrop_fps"] = 0
    check(GlassConfig.read(preferences).volumeBackdropFps == 30)
    println("PASS: old preference defaults, all FPS round trips, invalid import default")
}
