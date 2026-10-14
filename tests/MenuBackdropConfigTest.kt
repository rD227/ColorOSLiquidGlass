package net.z841973620.colorosliquidglass

import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** Exercise real config read/write on the host using an in-memory preferences interface. */
fun main() {
    val values = mutableMapOf<String, Any>()
    val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, arguments ->
        when {
            method.name.startsWith("put") -> {
                values[arguments[0] as String] = arguments[1]
                proxy
            }
            method.name == "commit" -> true
            else -> error("Unexpected preferences edit: ${method.name}")
        }
    } as SharedPreferences.Editor
    val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, arguments ->
        when (method.name) {
            "edit" -> editor
            "getInt", "getFloat", "getBoolean", "getLong" -> values[arguments[0]] ?: arguments[1]
            else -> error("Unexpected preferences read: ${method.name}")
        }
    } as SharedPreferences
    check(GlassConfig().menuBackdropFps == 1) { "New config must default to 1 Hz" }
    check(GlassConfig.read(preferences).menuBackdropFps == 1) { "Missing preference must default to 1 Hz" }
    for (fps in listOf(1, 2, 5, 10, 15, 30, 60, 90, 120)) {
        val config = GlassConfig().apply { menuBackdropFps = fps; volumeBackdropFps = 15 }
        check(config.write(preferences))
        val restored = GlassConfig.read(preferences)
        check(restored.menuBackdropFps == fps && restored.volumeBackdropFps == 15)
        check(values["menu_backdrop_fps"] == fps)
    }
    for (unsupported in listOf(-1, 0, 24, Int.MAX_VALUE)) {
        values["menu_backdrop_fps"] = unsupported
        check(GlassConfig.read(preferences).menuBackdropFps == 1)
    }
    println("PASS: 1 Hz default, nine menu FPS round trips including saved 30 FPS, volume independence, unsupported imported values")
}
