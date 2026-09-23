package top.yukonga.mishka.ui.screen.radar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.R
import top.yukonga.miuix.kmp.squircle.squircleBackground

/**
 * 目标行的品牌图标。
 *
 * 图形是**各品牌官方 SVG 的路径数据原样搬运**（Iconify `logos:` 集，CC0），落在
 * `res/drawable/ic_radar_*.xml` —— 不联网、不手绘。手绘的几何近似在四色 G、红底白三角、
 * 纸飞机这些复杂标上根本画不像，这是路线问题，调参救不回来。
 *
 * 底色默认浅色调：深色主题下就是一枚浅色徽章，不跟随 colorScheme。
 * X / Grok 例外，走官方黑底白字——这两个标本身就是白色字形，浅底会把字形吃掉。
 */
private val TileGithub = Color(0xFFEEF0F2)
private val TileYoutube = Color(0xFFFFE8EA)
private val TileGoogle = Color(0xFFEEF3FE)
private val TileTelegram = Color(0xFFE6F4FB)
private val TileOpenAi = Color(0xFFE8F6F1)
private val TileCloudflare = Color(0xFFFDF0E4)
private val TileDark = Color(0xFF1C1C1E)

@Composable
fun RadarServiceLogo(
    key: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    // X / Grok 的字形是白色，选中态底块不能跟着转白——白字白底会整块看不见
    val tile = if (selected && key != "x" && key != "grok") Color.White.copy(alpha = 0.94f) else tileColor(key)
    Box(
        modifier = modifier
            .size(size)
            // 32dp 方块配 10dp 圆角，与原先手写的 10.dp 保持一致
            .squircleBackground(tile, size * 0.3125f),
        contentAlignment = Alignment.Center,
    ) {
        // 各品牌 viewBox 宽高比差很多（Cloudflare 256×117、Grok 256×246），
        // 强制拉成正方形会变形，Fit 让它们各自按原比例缩进 16dp 方框
        Image(
            painter = painterResource(logoRes(key)),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

private fun tileColor(key: String): Color = when (key) {
    "google" -> TileGoogle
    "youtube" -> TileYoutube
    "github" -> TileGithub
    "chatgpt" -> TileOpenAi
    "cloudflare" -> TileCloudflare
    "x", "grok" -> TileDark
    "telegram" -> TileTelegram
    else -> TileGithub
}

/** 未知 key 不该出现，但真出现时也得有东西可画 —— 落到「全部通过」那个通用标上 */
private fun logoRes(key: String): Int = when (key) {
    "google" -> R.drawable.ic_radar_google
    "youtube" -> R.drawable.ic_radar_youtube
    "github" -> R.drawable.ic_radar_github
    "chatgpt" -> R.drawable.ic_radar_chatgpt
    "x" -> R.drawable.ic_radar_x
    "cloudflare" -> R.drawable.ic_radar_cloudflare
    "grok" -> R.drawable.ic_radar_grok
    "telegram" -> R.drawable.ic_radar_telegram
    else -> R.drawable.ic_radar_all
}
