package net.z841973620.colorosliquidglass

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.BackdropDemoScaffold
import com.kyant.backdrop.catalog.Block
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import net.z841973620.colorosliquidglass.settings.PlaygroundSettings
import net.z841973620.colorosliquidglass.settings.PlaygroundSettingsState
import net.z841973620.colorosliquidglass.update.GitHubUpdateDialog
import net.z841973620.colorosliquidglass.update.GitHubReleaseChecker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Direct adaptation of AndroidLiquidGlass GlassPlaygroundContent.
 * The corner slider alone is intentionally removed; injected Views derive their own corner radius.
 */
@Composable
fun GlassPlaygroundContent() {
    val context = LocalContext.current
    val initial = remember { GlassConfig.read(context) }
    val animationScope = rememberCoroutineScope()
    val offsetAnimation = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoomAnimation = remember { Animatable(1f) }
    val rotationAnimation = remember { Animatable(0f) }

    var isSheetExpanded by remember { mutableStateOf(true) }
    var availableUpdate by remember { mutableStateOf<GitHubReleaseChecker.ReleaseStatus?>(null) }
    val settings = remember { PlaygroundSettingsState(initial) }
    var isRestarting by remember { mutableStateOf(false) }

    fun restartSystemComponents() {
        if (isRestarting) return
        isRestarting = true
        RootController.saveAndRestart(context, settings.toConfig()) { success, message ->
            isRestarting = false
            Toast.makeText(context, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    BackdropDemoScaffold(overlay = { dialogBackdrop ->
        availableUpdate?.let { release ->
            GitHubUpdateDialog(dialogBackdrop, release) { availableUpdate = null }
        }
    }) { backdrop ->
        Box(
            Modifier
                .padding(top = 48.dp)
                .statusBarsPadding()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(256.dp / 2f * 0.5f) },
                    effects = {
                        val minDimension = size.minDimension
                        vibrancy()
                        blur(settings.blurRadiusDp.dp.toPx())
                        lens(
                            refractionHeight = settings.refractionHeightFrac * settings.glassIntensity * minDimension * 0.5f,
                            refractionAmount = settings.refractionAmountFrac * settings.glassIntensity * minDimension,
                            depthEffect = true,
                            chromaticAberration = settings.chromaticAberration > 0f
                        )
                    },
                    highlight = { if (settings.highlightIntensity > 0f && settings.glassIntensity > 0f) Highlight.Plain else null },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 0.08f))
                    },
                    layerBlock = {
                        val offset = offsetAnimation.value
                        val zoom = zoomAnimation.value
                        val rotation = rotationAnimation.value
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = zoom
                        scaleY = zoom
                        rotationZ = rotation
                    }
                )
                .pointerInput(animationScope) {
                    fun Offset.rotateBy(angle: Float): Offset {
                        val radians = angle * (PI / 180)
                        return Offset(
                            (x * cos(radians) - y * sin(radians)).toFloat(),
                            (x * sin(radians) + y * cos(radians)).toFloat()
                        )
                    }
                    detectTransformGestures { _, pan, gestureZoom, gestureRotate ->
                        val zoom = zoomAnimation.value
                        val targetRotation = rotationAnimation.value + gestureRotate
                        val targetOffset = offsetAnimation.value + pan.rotateBy(targetRotation) * (zoom * gestureZoom)
                        animationScope.launch {
                            offsetAnimation.snapTo(targetOffset)
                            zoomAnimation.snapTo(zoom * gestureZoom)
                            rotationAnimation.snapTo(targetRotation)
                        }
                    }
                }
                .size(256.dp)
                .align(Alignment.TopCenter)
        )

        Block {
            if (isSheetExpanded) {
                val sheetBackdrop = rememberLayerBackdrop()
                val controlsScroll = rememberScrollState()
                Column(
                    Modifier
                        .padding(16.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(controlsScroll)
                        .padding(bottom = 72.dp)
                        .navigationBarsPadding()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedRectangle(32.dp) },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(16.dp.toPx(), 32.dp.toPx())
                            },
                            highlight = { Highlight.Plain },
                            exportedBackdrop = sheetBackdrop,
                            onDrawSurface = { drawRect(Color.White.copy(alpha = 0.5f)) }
                        )
                        .padding(24.dp)
                        .align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PlaygroundSettings(settings, sheetBackdrop) { availableUpdate = it }
                }
            }
        }

        Block {
            LiquidButton(
                { isSheetExpanded = !isSheetExpanded }, backdrop,
                Modifier.padding(20.dp).navigationBarsPadding().align(Alignment.BottomStart),
                tint = Color(0xFFFF8D28)
            ) { BasicText(if (isSheetExpanded) "🔽" else "🔼", style = TextStyle(Color.White, 15.sp)) }

            LiquidButton(
                {
                    animationScope.launch {
                        launch { offsetAnimation.animateTo(Offset.Zero) }
                        launch { zoomAnimation.animateTo(1f) }
                        launch { rotationAnimation.animateTo(0f) }
                    }
                    settings.resetAppearance()
                }, backdrop,
                Modifier.padding(20.dp).navigationBarsPadding().align(Alignment.BottomEnd),
                tint = Color(0xFFFF8D28)
            ) { BasicText("重置", style = TextStyle(Color.White, 15.sp)) }
        }

        LiquidButton(
            { restartSystemComponents() },
            backdrop,
            Modifier
                .padding(horizontal = 16.dp)
                .statusBarsPadding()
                .align(Alignment.TopCenter),
            tint = Color(0xFF0088FF)
        ) {
            BasicText(
                if (isRestarting) "正在重启…" else "重启系统组件",
                style = TextStyle(Color.White, 14.sp)
            )
        }
    }
}
