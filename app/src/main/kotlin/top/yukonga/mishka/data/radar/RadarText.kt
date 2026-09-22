package top.yukonga.mishka.data.radar

import java.io.ByteArrayOutputStream

/**
 * 雷达的文本底层工具：base64、百分号编码、分享链正则。
 *
 * 不复用 [android.util.Base64] / [java.util.Base64]，原因有二：一是要容忍机场常用的
 * URL-safe 字母表与缺失 padding，标准库两个入口都不直接支持；二是本文件保持零 Android
 * 依赖，雷达的解析链路可以在纯 JVM 单测里跑完。
 */
internal object RadarText {

    /* ---------- 分享链正则 ---------- */

    private const val SCHEME = "vmess|vless|ssr?|trojan|hysteria2?|hy2|tuic|socks5?|https?"

    /** 末尾排除空白与引号，避免把 HTML/JSON 里的尾随标点吞进链接 */
    private val LINK_REGEX = Regex(
        """\b(?:$SCHEME)://[^\s"'<>\\]+""",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_LINKS = 200_000

    fun hasLink(s: String): Boolean = LINK_REGEX.containsMatchIn(s)

    fun sweepLinks(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (match in LINK_REGEX.findAll(text)) {
            out.add(match.value)
            if (out.size >= MAX_LINKS) break
        }
        return out
    }

    /* ---------- base64 ---------- */

    private const val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val B64_LOOKUP = IntArray(128) { -1 }.apply {
        B64_CHARS.forEachIndexed { i, c -> this[c.code] = i }
    }

    private val B64_SHAPE = Regex("^[A-Za-z0-9+/\\-_]+={0,2}$")

    /** 整块是否「看起来像 base64」：去掉空白后必须全是 base64 字符 */
    fun looksBase64(s: String): Boolean {
        val t = s.filterNot { it.isWhitespace() }
        if (t.length < 16) return false
        if (t.length % 4 == 1) return false
        return B64_SHAPE.matches(t)
    }

    /** 容忍 URL-safe(-_) 与缺失 padding；非法字符返回 null 而不是抛异常 */
    fun decodeBase64(raw: String): String? {
        val out = ByteArrayOutputStream(raw.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        var meaningful = 0

        for (c in raw) {
            if (c == '=') break
            val v = when (c) {
                '-', '_' -> if (c == '-') 62 else 63
                ' ', '\t', '\r', '\n' -> continue
                else -> if (c.code < 128) B64_LOOKUP[c.code] else -1
            }
            if (v < 0) return null
            meaningful++
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }

        if (meaningful == 0) return null
        if (meaningful % 4 == 1) return null
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    fun encodeBase64(raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0

            sb.append(B64_CHARS[b0 shr 2])
            sb.append(B64_CHARS[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) B64_CHARS[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) B64_CHARS[b2 and 0x3F] else '=')

            i += 3
        }
        return sb.toString()
    }

    /**
     * 最多递归解 2 层，返回第一段「解出来含分享链」的文本。
     * 有些源把链接列表再整体 base64 一次，所以单层解码不够。
     */
    fun decodeBase64Chain(body: String): String? {
        var cur = body
        repeat(2) {
            if (!looksBase64(cur)) return null
            val dec = decodeBase64(cur) ?: return null
            // 只认分享链会把 base64 包着的 Clash YAML / JSON 整份丢掉——解开了却没链接，白丢
            if (hasLink(dec) || dec.contains("proxies:") || dec.startsWith("{")) return dec
            cur = dec
        }
        return null
    }

    /* ---------- 百分号编码 ---------- */

    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    private const val HEX = "0123456789ABCDEF"

    fun encodeComponent(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 128 && UNRESERVED.indexOf(c) >= 0) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
            }
        }
        return sb.toString()
    }

    /** 与 JS 的 decodeURIComponent 对齐：不把 '+' 当空格 */
    fun decodeComponent(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
