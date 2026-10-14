package net.z841973620.colorosliquidglass.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

private const val DOWNLOAD_URL =
    "https://github.com/rD227/ColorOSLiquidGlass/releases/latest/download/app-release.apk"

/** Reuses the settings glass; the browser owns the download. */
@Composable
fun GitHubUpdateCard(backdrop: Backdrop) {
    val context = LocalContext.current
    val currentVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
    }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("当前版本：$currentVersion；点击检查最新正式版。") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText("GitHub 更新", style = TextStyle(Color.Black, 18.sp, fontWeight = FontWeight.Medium))
        BasicText(status, style = TextStyle(Color.Black, 14.sp, lineHeight = 20.sp))
        LiquidButton(
            onClick = {
                if (!checking) {
                    checking = true
                    status = "正在检查更新…"
                    scope.launch {
                        try {
                            status = withContext(Dispatchers.IO) { GitHubReleaseChecker.check(currentVersion) }
                        } catch (error: IOException) {
                            status = "检查失败：${error.localizedMessage ?: "请检查网络连接。"}"
                        } catch (_: JSONException) {
                            status = "检查失败：无法解析版本信息，可直接前往 GitHub 查看。"
                        } finally {
                            checking = false
                        }
                    }
                }
            },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            surfaceColor = Color.White.copy(alpha = 0.5f)
        ) { BasicText(if (checking) "正在检查…" else "检查更新", style = TextStyle(Color.Black, 15.sp)) }
        SelectionContainer {
            BasicText(DOWNLOAD_URL, style = TextStyle(Color.Black, 14.sp, lineHeight = 20.sp))
        }
        LiquidButton(
            onClick = {
                try {
                    // Resolve a generic HTTPS link so GitHub's verified app links cannot
                    // intercept the download. Respect the user's default web browser.
                    val browser = context.packageManager.resolveActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        },
                        PackageManager.MATCH_DEFAULT_ONLY
                    )
                    if (browser?.handleAllWebDataURI != true) {
                        Toast.makeText(context, "请先在系统设置中选择默认浏览器，或复制上方链接下载。", Toast.LENGTH_LONG).show()
                        return@LiquidButton
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        setPackage(browser.activityInfo.packageName)
                    }
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "找不到浏览器，可复制上方链接后下载。", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, "无法打开浏览器，可复制上方链接后下载。", Toast.LENGTH_LONG).show()
                }
            },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            surfaceColor = Color.White.copy(alpha = 0.5f)
        ) { BasicText("前往 GitHub 下载", style = TextStyle(Color.Black, 15.sp)) }
    }
}
