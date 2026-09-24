package top.yukonga.mishka.ui.screen.radar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分享页：局域网订阅二维码 + 链接 + v2rayN/Clash 格式切换 + 关闭分享。
 *
 * 页面开着 = [RadarViewModel.startShare] 起的 HTTP 服务在跑；点关闭 = [RadarViewModel.stopShare]
 * 停掉服务，链接随之失效——「分享那几分钟」的语义。二维码用 ZXing 本地生成，不联网。
 */
@Composable
fun RadarSharePage(
    url: String?,
    format: String,
    onFormatChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val qrBitmap by produceState<Bitmap?>(null, url) {
        value = url?.let { withContext(Dispatchers.Default) { makeQr(it) } }
    }

    // 页面离开组合树（按返回键 / 被替换）时停掉分享服务，链接随之失效
    DisposableEffect(Unit) {
        onDispose { onClose() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))

        // 格式切换：v2rayN / Clash
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .padding(4.dp),
        ) {
            FormatTab("v2rayN", format, onFormatChange, Modifier.weight(1f))
            FormatTab("Clash", format, onFormatChange, Modifier.weight(1f))
        }

        Spacer(Modifier.height(28.dp))

        // 二维码
        val qr = qrBitmap
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .width(240.dp)
                    .aspectRatio(1f),
            )
        } else {
            Box(Modifier.width(240.dp).height(240.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.radar_share_no_url),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.radar_share_qr_tip),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )

        Spacer(Modifier.height(20.dp))

        // 链接 + 复制
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = url ?: "",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    if (copied) R.string.radar_share_copied else R.string.radar_share_copy,
                ),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable {
                        url?.let { copy(context, it) }
                        copied = true
                    },
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClose)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.radar_share_close),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE5484D),
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun FormatTab(
    label: String,
    current: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = label == current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            )
            .clickable { onChange(label) }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MiuixTheme.colorScheme.onSurface
                else MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
    }
}

/** 复制到系统剪贴板。用 Context API，不依赖 Compose 的 Clipboard 版本差异 */
private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("radar", text))
}

/** 用 ZXing 把文本编码成二维码 Bitmap（480×480） */
private fun makeQr(content: String): Bitmap {
    val size = 480
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bmp
}