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
    check(GlassConfig.read(preferences).volumeBackdropFps == 30)
    println("PASS: old preference defaults, all FPS round trips, invalid import default")
}
