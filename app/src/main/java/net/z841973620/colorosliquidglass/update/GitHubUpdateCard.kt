package net.z841973620.colorosliquidglass.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

private const val DOWNLOAD_URL =
    "https://github.com/rD227/ColorOSLiquidGlass/releases/latest/download/app-release.apk"

/** Manual flow mirrors AboutScreen: only a newer release opens the modal. */
@Composable
internal fun GitHubUpdateCard(backdrop: Backdrop, onUpdateAvailable: (GitHubReleaseChecker.ReleaseStatus) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val currentVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
    }
    LiquidButton(
        onClick = {
            if (!checking) {
                checking = true
                scope.launch {
                    try {
                        val release = withContext(Dispatchers.IO) { GitHubReleaseChecker.checkRelease(currentVersion) }
                        if (release.comparison == 1) onUpdateAvailable(release)
                        else Toast.makeText(context, release.message, Toast.LENGTH_LONG).show()
                    } catch (error: IOException) {
                        Toast.makeText(context, "检查失败：${error.localizedMessage ?: "请检查网络连接。"}", Toast.LENGTH_LONG).show()
                    } catch (_: JSONException) {
                        Toast.makeText(context, "检查失败：无法解析版本信息，请稍后重试。", Toast.LENGTH_LONG).show()
                    } finally {
                        checking = false
                    }
                }
            }
        }, backdrop = backdrop, modifier = Modifier.fillMaxWidth(),
        surfaceColor = Color.White.copy(alpha = 0.5f)
    ) { BasicText(if (checking) "正在检查…" else "检查更新", style = TextStyle(Color.Black, 15.sp)) }
}

/** Same-window overlay so glass can sample the existing (including custom) wallpaper. */
@Composable
internal fun GitHubUpdateDialog(
    backdrop: Backdrop,
    release: GitHubReleaseChecker.ReleaseStatus,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val dialogBackdrop = rememberLayerBackdrop()
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss)
            .safeDrawingPadding().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(max = 520.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(32.dp) },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                        lens(16.dp.toPx(), 32.dp.toPx())
                    },
                    highlight = { Highlight.Plain },
                    exportedBackdrop = dialogBackdrop,
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.5f)) }
                )
                .clickable(interactionSource = null, indication = null) { }
                .semantics { dialog(); paneTitle = "检查更新" }
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BasicText("发现新版本 ${release.latestVersion}", style = TextStyle(Color.Black, 20.sp, fontWeight = FontWeight.Medium))
            BasicText(release.message, style = TextStyle(Color.Black, 15.sp, lineHeight = 22.sp))
            LiquidButton(
                onClick = {
                    try {
                        val intent = defaultBrowserIntent(context.packageManager, DOWNLOAD_URL)
                        if (intent == null) {
                            Toast.makeText(context, "请先设置默认浏览器，或复制下方链接下载。", Toast.LENGTH_LONG).show()
                        } else {
                            context.startActivity(intent)
                            onDismiss()
                        }
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "找不到浏览器，可复制下方链接下载。", Toast.LENGTH_LONG).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(context, "无法打开浏览器，可复制下方链接下载。", Toast.LENGTH_LONG).show()
                    }
                }, backdrop = dialogBackdrop, modifier = Modifier.fillMaxWidth(),
                surfaceColor = Color.White.copy(alpha = 0.5f)
            ) { BasicText("前往 GitHub 下载", style = TextStyle(Color.Black, 15.sp)) }
            SelectionContainer {
                BasicText(DOWNLOAD_URL, style = TextStyle(Color.Black, 12.sp, lineHeight = 18.sp))
            }
            LiquidButton(
                onClick = onDismiss, backdrop = dialogBackdrop, modifier = Modifier.fillMaxWidth(),
                surfaceColor = Color.White.copy(alpha = 0.5f)
            ) { BasicText("稍后再说", style = TextStyle(Color.Black, 15.sp)) }
        }
    }
}

/** Query only public SDK APIs. A chooser/resolver is not a selected browser activity. */
internal fun defaultBrowserIntent(packageManager: PackageManager, url: String): Intent? {
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val resolved = packageManager.resolveActivity(webIntent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
    val browsers = packageManager.queryIntentActivities(webIntent, PackageManager.MATCH_DEFAULT_ONLY)
    val selected = browsers.any {
        it.activityInfo.packageName == resolved.activityInfo.packageName &&
            it.activityInfo.name == resolved.activityInfo.name
    }
    if (!selected) return null
    return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(resolved.activityInfo.packageName)
}
