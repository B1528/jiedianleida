package top.yukonga.mishka.ui.screen.radar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.squircleBackground
import kotlin.math.cos
import kotlin.math.sin

/**
 * 目标行的品牌图标。
 *
 * 全部本地矢量、不联网。原型（`radar/demo.html`）用的就是内联 SVG，换成网络 favicon
 * 会带来三个问题：加载时先空一下再闪出来、代理没跑时拉不到、离线完全不可用。
 * 这里按原型的形状手绘几何近似，够识别就行，不追求像素级一致。
 *
 * 品牌色不属于主题语义色，没法从 `MiuixTheme.colorScheme` 派生，只能硬编码 ——
 * 但**只允许出现在本文件**，屏幕层不出现任何裸色值。
 */
private val GlyphGithub = Color(0xFF24292F)
private val GlyphYoutube = Color(0xFFFF0033)
private val GlyphGoogleBlue = Color(0xFF4285F4)
private val GlyphGoogleGreen = Color(0xFF34A853)
private val GlyphGoogleYellow = Color(0xFFFBBC05)
private val GlyphGoogleRed = Color(0xFFEA4335)
private val GlyphTelegram = Color(0xFF229ED9)
private val GlyphOpenAi = Color(0xFF10A37F)
private val GlyphCloudflare = Color(0xFFF38020)
private val GlyphX = Color(0xFF0B0B0B)
private val GlyphGrok = Color(0xFF1A1A1A)
private val GlyphMuted = Color(0xFF5B6470)

// 底色沿用原型的浅色调：深色主题下就是一枚浅色徽章，不跟随 colorScheme
private val TileGithub = Color(0xFFEEF0F2)
private val TileYoutube = Color(0xFFFFE8EA)
private val TileGoogle = Color(0xFFEEF3FE)
private val TileTelegram = Color(0xFFE6F4FB)
private val TileOpenAi = Color(0xFFE8F6F1)
private val TileCloudflare = Color(0xFFFDF0E4)
private val TileMonochrome = Color(0xFFEDEDED)

@Composable
fun RadarServiceLogo(
    key: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val tile = if (selected) Color.White.copy(alpha = 0.94f) else tileColor(key)
    Box(
        modifier = modifier
            .size(size)
            // 32dp 方块配 10dp 圆角，与原先手写的 10.dp 保持一致
            .squircleBackground(tile, size * 0.3125f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.5f)) { drawBrand(key) }
    }
}

private fun tileColor(key: String): Color = when (key) {
    "google" -> TileGoogle
    "youtube" -> TileYoutube
    "github" -> TileGithub
    "chatgpt" -> TileOpenAi
    "cloudflare" -> TileCloudflare
    "x", "grok" -> TileMonochrome
    "telegram" -> TileTelegram
    else -> TileGithub
}

private fun DrawScope.drawBrand(key: String) {
    val s = size.minDimension
    when (key) {
        // 四宫格：识别的锚点是「四个方块」而不是任何字母
        "all" -> {
            val gap = s * 0.12f
            val cell = (s - gap) / 2f
            val radius = CornerRadius(cell * 0.30f)
            drawRoundRect(GlyphMuted, Offset(0f, 0f), Size(cell, cell), radius)
            drawRoundRect(GlyphMuted.copy(alpha = 0.5f), Offset(cell + gap, 0f), Size(cell, cell), radius)
            drawRoundRect(GlyphMuted.copy(alpha = 0.5f), Offset(0f, cell + gap), Size(cell, cell), radius)
            drawRoundRect(GlyphMuted, Offset(cell + gap, cell + gap), Size(cell, cell), radius)
        }

        // 四段 90° 圆弧拼成一个环，再补右侧横杠 —— 四色一眼认出是 Google
        "google" -> {
            val stroke = s * 0.22f
            val arcSize = Size(s - stroke, s - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val style = Stroke(width = stroke)
            drawArc(GlyphGoogleRed, -45f, 90f, false, topLeft, arcSize, style = style)
            drawArc(GlyphGoogleYellow, 45f, 90f, false, topLeft, arcSize, style = style)
            drawArc(GlyphGoogleGreen, 135f, 90f, false, topLeft, arcSize, style = style)
            drawArc(GlyphGoogleBlue, 225f, 90f, false, topLeft, arcSize, style = style)
            drawRect(GlyphGoogleBlue, Offset(s * 0.52f, s * 0.42f), Size(s * 0.48f, s * 0.18f))
        }

        "youtube" -> {
            val height = s * 0.68f
            drawRoundRect(
                GlyphYoutube,
                Offset(0f, (s - height) / 2f),
                Size(s, height),
                CornerRadius(height * 0.30f),
            )
            val play = Path().apply {
                moveTo(s * 0.40f, s * 0.33f)
                lineTo(s * 0.71f, s * 0.50f)
                lineTo(s * 0.40f, s * 0.67f)
                close()
            }
            drawPath(play, Color.White)
        }

        // 章鱼猫只留「圆身子 + 两只尖耳」，缩小到 16dp 仍然认得出
        "github" -> {
            drawCircle(GlyphGithub, s * 0.40f, Offset(s * 0.5f, s * 0.58f))
            val ears = Path().apply {
                moveTo(s * 0.16f, s * 0.36f)
                lineTo(s * 0.36f, s * 0.10f)
                lineTo(s * 0.44f, s * 0.42f)
                close()
                moveTo(s * 0.84f, s * 0.36f)
                lineTo(s * 0.64f, s * 0.10f)
                lineTo(s * 0.56f, s * 0.42f)
                close()
            }
            drawPath(ears, GlyphGithub)
        }

        // ChatGPT：正六边形轮廓 + 中心点，比原型的结扣简单但同样是六边形母题
        "chatgpt" -> {
            val stroke = s * 0.10f
            val r = s / 2f - stroke
            val hex = Path()
            repeat(6) { i ->
                val angle = Math.toRadians(60.0 * i - 90.0)
                val x = s / 2f + r * cos(angle).toFloat()
                val y = s / 2f + r * sin(angle).toFloat()
                if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
            }
            hex.close()
            drawPath(hex, GlyphOpenAi, style = Stroke(width = stroke, cap = StrokeCap.Round))
            drawCircle(GlyphOpenAi, s * 0.11f, Offset(s * 0.5f, s * 0.5f))
        }

        "x" -> {
            val stroke = s * 0.20f
            drawLine(GlyphX, Offset(s * 0.14f, s * 0.14f), Offset(s * 0.86f, s * 0.86f), stroke, StrokeCap.Round)
            drawLine(GlyphX, Offset(s * 0.86f, s * 0.14f), Offset(s * 0.14f, s * 0.86f), stroke, StrokeCap.Round)
        }

        "cloudflare" -> {
            drawCircle(GlyphCloudflare, s * 0.22f, Offset(s * 0.32f, s * 0.55f))
            drawCircle(GlyphCloudflare, s * 0.29f, Offset(s * 0.53f, s * 0.42f))
            drawCircle(GlyphCloudflare, s * 0.20f, Offset(s * 0.75f, s * 0.57f))
            drawRect(GlyphCloudflare, Offset(s * 0.32f, s * 0.60f), Size(s * 0.43f, s * 0.22f))
        }

        // Grok：圆环 + 一道斜杠
        "grok" -> {
            val stroke = s * 0.10f
            drawCircle(GlyphGrok, s * 0.45f, Offset(s * 0.5f, s * 0.5f), style = Stroke(width = stroke))
            drawLine(GlyphGrok, Offset(s * 0.26f, s * 0.76f), Offset(s * 0.74f, s * 0.24f), stroke * 1.4f, StrokeCap.Round)
        }

        "telegram" -> {
            val plane = Path().apply {
                moveTo(s * 0.06f, s * 0.52f)
                lineTo(s * 0.94f, s * 0.12f)
                lineTo(s * 0.64f, s * 0.90f)
                lineTo(s * 0.46f, s * 0.60f)
                close()
            }
            drawPath(plane, GlyphTelegram)
        }

        // 未知 key 不该出现，但一旦出现也不能画成空白 —— 留一个中性圆点
        else -> drawCircle(GlyphMuted, s * 0.28f, Offset(s * 0.5f, s * 0.5f))
    }
}
