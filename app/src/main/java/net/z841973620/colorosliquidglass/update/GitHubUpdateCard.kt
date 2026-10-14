package net.z841973620.colorosliquidglass.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidButton

private const val DOWNLOAD_URL =
    "https://github.com/rD227/ColorOSLiquidGlass/releases/latest/download/app-release.apk"

/** Reuses the settings glass; the browser owns the download. */
@Composable
fun GitHubUpdateCard(backdrop: Backdrop) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText("GitHub 更新", style = TextStyle(Color.Black, 18.sp, fontWeight = FontWeight.Medium))
        BasicText("在浏览器中下载 GitHub 最新正式版。", style = TextStyle(Color.Black, 14.sp, lineHeight = 20.sp))
        SelectionContainer {
            BasicText(DOWNLOAD_URL, style = TextStyle(Color.Black, 14.sp, lineHeight = 20.sp))
        }
        LiquidButton(
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
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
