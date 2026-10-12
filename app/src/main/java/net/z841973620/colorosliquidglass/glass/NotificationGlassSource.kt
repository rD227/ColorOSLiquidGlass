package net.z841973620.colorosliquidglass.glass

import android.app.KeyguardManager
import android.view.View
import net.z841973620.colorosliquidglass.GlassConfig

/** Chooses content independently of the OEM geometry that owns each plate. */
internal class NotificationGlassSource(private val view: View) {
    private val keyguard = view.context.getSystemService(KeyguardManager::class.java)
    private var route: Route? = null
    private enum class Route { LOCK, WALLPAPER, HEADS_UP, SHADE }

    fun update(glass: GlassDrawable, config: GlassConfig, headsUp: Boolean) {
        val locked = keyguard.isKeyguardLocked
        val next = when {
            locked -> Route.LOCK
            headsUp -> Route.HEADS_UP
            config.notificationAppBackdrop -> Route.SHADE
            else -> Route.WALLPAPER
        }
        if (next != route) {
            release()
            when (next) {
                Route.HEADS_UP -> BehindDisplayCapture.bindHeadsUp(view, glass)
                Route.SHADE -> SharedNotificationBackdrop.bind(view, config)
                Route.LOCK, Route.WALLPAPER -> {
                    if (locked) GlassInstaller.markLockWallpaperOnly(view)
                    else GlassInstaller.markWallpaperOnly(view)
                    BackdropCapture.markSharedWallpaper(view)
                }
            }
            route = next
            // Shared shade capture is owned by its root, never force a local software draw.
            if (next != Route.SHADE) GlassInstaller.forceCapture(view)
        } else if (next == Route.SHADE) {
            SharedNotificationBackdrop.bind(view, config)
        }
        glass.setColorFilter(NotificationGlassAppearance.backdropFilter(
            locked, view.resources.configuration.uiMode))
    }

    fun release() {
        SharedNotificationBackdrop.unbind(view)
        BehindDisplayCapture.unbindHeadsUp(view)
        BackdropCapture.clearWallpaperOnly(view)
        route = null
    }
}
