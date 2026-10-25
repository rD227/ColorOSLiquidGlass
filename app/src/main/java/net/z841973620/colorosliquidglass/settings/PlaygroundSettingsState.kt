package net.z841973620.colorosliquidglass.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.z841973620.colorosliquidglass.GlassConfig

/** 编辑中的设置；toConfig 生成保存快照，预览动画状态由页面自己持有。 */
internal class PlaygroundSettingsState(initial: GlassConfig) {
    var enabled by mutableStateOf(initial.enabled)
    var hideDesktopIcons by mutableStateOf(initial.hideDesktopIcons)
    var modifyMenuStyle by mutableStateOf(initial.modifyMenuStyle)
    var notificationGlassEnabled by mutableStateOf(initial.notificationGlassEnabled)
    var volumeBackdropFps by mutableStateOf(initial.volumeBackdropFps)
    var headsUpGlassEnabled by mutableStateOf(initial.headsUpGlassEnabled)
    var menuBackdropEnabled by mutableStateOf(initial.menuBackdropEnabled)
    var menuBackdropFps by mutableStateOf(initial.menuBackdropFps)
    var notificationAppBackdrop by mutableStateOf(initial.notificationAppBackdrop)
    var notificationBackdropFps by mutableStateOf(initial.notificationBackdropFps)
    var glassIntensity by mutableFloatStateOf(initial.glassIntensity)
    var blurRadiusDp by mutableFloatStateOf(initial.blurRadius)
    var refractionHeightFrac by mutableFloatStateOf(initial.refractionHeight)
    var refractionAmountFrac by mutableFloatStateOf(initial.refractionAmount)
    var chromaticAberration by mutableFloatStateOf(initial.chromaticAberration)
    var reflectionIntensity by mutableFloatStateOf(initial.reflectionIntensity)
    var highlightIntensity by mutableFloatStateOf(initial.highlightIntensity)

    fun toConfig() = GlassConfig().also {
        it.enabled = enabled
        it.hideDesktopIcons = hideDesktopIcons
        it.modifyMenuStyle = modifyMenuStyle
        it.notificationGlassEnabled = notificationGlassEnabled
        it.volumeBackdropFps = volumeBackdropFps
        it.headsUpGlassEnabled = headsUpGlassEnabled
        it.menuBackdropEnabled = menuBackdropEnabled
        it.menuBackdropFps = menuBackdropFps
        it.notificationAppBackdrop = notificationAppBackdrop
        it.notificationBackdropFps = notificationBackdropFps
        it.glassIntensity = glassIntensity
        it.blurRadius = blurRadiusDp
        it.refractionHeight = refractionHeightFrac
        it.refractionAmount = refractionAmountFrac
        it.chromaticAberration = chromaticAberration
        it.transparency = 0f
        it.reflectionIntensity = reflectionIntensity
        it.highlightIntensity = highlightIntensity
    }

    fun resetAppearance() {
        glassIntensity = 1f
        blurRadiusDp = 0f
        refractionHeightFrac = 0.2f
        refractionAmountFrac = 0.2f
        chromaticAberration = 0f
        reflectionIntensity = 1f
        highlightIntensity = 1f
    }
}
