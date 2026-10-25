package net.z841973620.colorosliquidglass.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle
import net.z841973620.colorosliquidglass.LauncherIconController
import net.z841973620.colorosliquidglass.RootController
import net.z841973620.colorosliquidglass.config.CaptureRates
import net.z841973620.colorosliquidglass.update.GitHubReleaseChecker
import net.z841973620.colorosliquidglass.update.GitHubUpdateCard

/** 功能开关和材质参数；页面只负责放置面板与展示实时预览。 */
@Composable
internal fun PlaygroundSettings(
    settings: PlaygroundSettingsState,
    backdrop: Backdrop,
    onUpdateAvailable: (GitHubReleaseChecker.ReleaseStatus) -> Unit
) {
    val context = LocalContext.current
    val backdropFpsOptions = CaptureRates.VOLUME
    val menuBackdropFpsOptions = CaptureRates.MENU
    val notificationFpsOptions = CaptureRates.NOTIFICATION
    PlaygroundToggle("启用修改", { settings.enabled }, backdrop) { settings.enabled = it }
    PlaygroundToggle("隐藏桌面图标", { settings.hideDesktopIcons }, backdrop) {
        settings.hideDesktopIcons = it
        LauncherIconController.applyHideDesktopIcons(context, it)
        RootController.saveConfig(context, settings.toConfig()) { _, _ -> }
    }
    PlaygroundToggle("修改菜单栏样式", { settings.modifyMenuStyle }, backdrop) {
        settings.modifyMenuStyle = it
        RootController.saveConfig(context, settings.toConfig()) { _, _ -> }
    }
    PlaygroundToggle("通知卡片与锁屏通知", { settings.notificationGlassEnabled }, backdrop) {
        settings.notificationGlassEnabled = it
        RootController.saveConfig(context, settings.toConfig()) { _, _ -> }
    }
    PlaygroundToggle("顶部悬浮通知玻璃", { settings.headsUpGlassEnabled }, backdrop) {
        settings.headsUpGlassEnabled = it
        RootController.saveConfig(context, settings.toConfig()) { _, _ -> }
    }
    PlaygroundToggle("通知卡片采样应用背景", { settings.notificationAppBackdrop }, backdrop) {
        settings.notificationAppBackdrop = it
        RootController.saveConfig(context, settings.toConfig()) { _, _ -> }
    }
    if (settings.notificationAppBackdrop) {
        val notificationUpdateLabel = if (settings.notificationBackdropFps == 0)
            "通知卡片背景更新：0 Hz（打开时采样一次）"
        else "通知卡片背景更新：${settings.notificationBackdropFps} Hz"
        PlaygroundSlider(notificationUpdateLabel,
            { notificationFpsOptions.indexOf(settings.notificationBackdropFps).toFloat() },
            0f..notificationFpsOptions.lastIndex.toFloat(), 0.01f, backdrop,
            steps = notificationFpsOptions.size - 2) {
            settings.notificationBackdropFps = notificationFpsOptions[it.toInt()]
        }
    }
    BasicText("作用于下拉通知卡片和分组标题；关闭应用采样时使用壁纸。0 Hz 每次打开仅采样一次，保持到关闭；其他档位定时更新背景，滑动保持流畅。")
    PlaygroundToggle("菜单采样背景", { settings.menuBackdropEnabled }, backdrop) {
        settings.menuBackdropEnabled = it
        RootController.saveConfig(context, settings.toConfig()) { _, _ -> }
    }
    if (settings.menuBackdropEnabled) {
        PlaygroundSlider("桌面长按菜单静止采样：${settings.menuBackdropFps} Hz",
            { menuBackdropFpsOptions.indexOf(settings.menuBackdropFps).toFloat() },
            0f..menuBackdropFpsOptions.lastIndex.toFloat(), 0.01f, backdrop,
            steps = menuBackdropFpsOptions.size - 2) {
            settings.menuBackdropFps = menuBackdropFpsOptions[it.toInt()]
        }
        BasicText("静止时减少背景采样；打开、关闭和几何变化仍及时更新。保存后重启系统组件生效")
    }
    BasicText("关闭悬浮通知玻璃使用系统原样式；关闭菜单背景采样保留简单玻璃外观....保存后重启生效")
    PlaygroundSlider("液态玻璃强度", { settings.glassIntensity }, 0f..1f, 0.001f, backdrop) { settings.glassIntensity = it }
    PlaygroundSlider("模糊半径", { settings.blurRadiusDp }, 0f..32f, 0.01f, backdrop) { settings.blurRadiusDp = it }
    PlaygroundSlider("音量条/悬浮通知背景更新：${settings.volumeBackdropFps} FPS",
        { backdropFpsOptions.indexOf(settings.volumeBackdropFps).toFloat() },
        0f..backdropFpsOptions.lastIndex.toFloat(), 0.01f, backdrop,
        steps = backdropFpsOptions.size - 2) {
        settings.volumeBackdropFps = backdropFpsOptions[it.toInt()]
    }
    BasicText("只调整背景更新频率；低帧率更省资源，快速画面可能稍有滞后....保存后重启系统组件生效")
    PlaygroundSlider("折射高度", { settings.refractionHeightFrac }, 0f..1f, 0.001f, backdrop) { settings.refractionHeightFrac = it }
    PlaygroundSlider("扭曲强度", { settings.refractionAmountFrac }, 0f..1f, 0.001f, backdrop) { settings.refractionAmountFrac = it }
    PlaygroundSlider("反射", { settings.reflectionIntensity }, 0f..1f, 0.001f, backdrop) { settings.reflectionIntensity = it }
    PlaygroundSlider("高光", { settings.highlightIntensity }, 0f..1f, 0.001f, backdrop) { settings.highlightIntensity = it }
    PlaygroundSlider("色差", { settings.chromaticAberration }, 0f..1f, 0.001f, backdrop) { settings.chromaticAberration = it }
    GitHubUpdateCard(backdrop, onUpdateAvailable)
}

@Composable
private fun PlaygroundToggle(
    label: String,
    selected: () -> Boolean,
    backdrop: Backdrop,
    onSelect: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(label)
        LiquidToggle(selected = selected, onSelect = onSelect, backdrop = backdrop)
    }
}

@Composable
private fun PlaygroundSlider(
    label: String,
    value: () -> Float,
    range: ClosedFloatingPointRange<Float>,
    threshold: Float,
    backdrop: Backdrop,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label)
        LiquidSlider(value, onChange, range, threshold, backdrop, steps = steps)
    }
}
