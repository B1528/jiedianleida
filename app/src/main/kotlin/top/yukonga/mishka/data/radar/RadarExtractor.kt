package top.yukonga.mishka.data.radar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从任意订阅容器里抽出节点。
 *
 * 双轨制：先按容器做结构化解析（保真，字段全），再无条件跑一遍正则兜底（保召回）。
 * 结构化解析失败不影响兜底，兜底捞到的也不影响结构化结果 —— 两条路的产物一起交给
 * [RadarDedupe] 按指纹合并。
 *
 * 支持：Clash YAML（`proxies:`）、JSON（`proxies[]` / `outbounds[]`）、base64（最多递归 2 层）、
 * 纯链接列表、HTML。识别不出来的当 UNKNOWN 处理，靠兜底捞出。
 */
internal object RadarExtractor {

    data class ExtractResult(
        val type: String,
        /** base64 展开后的正文，供上游排查 */
        val text: String,
        /** 结构化节点（YAML / JSON），字段名沿用 Clash 侧 */
        val nodes: List<Map<String, Any?>>,
        val links: List<String>,
        val note: String,
    )

    /* ---------- 容器嗅探 ---------- */

    fun detect(body: String): String {
        val raw = body.trim()
        if (raw.isEmpty()) return "EMPTY"

        val head = raw.take(4000)

        if (head.startsWith("{") || head.startsWith("[")) return "JSON"
        if (Regex("""^\s*(proxies|proxy-providers)\s*:""", RegexOption.MULTILINE).containsMatchIn(head)) {
            return "CLASH_YAML"
        }
        if (Regex("""^\s*outbounds\s*:""", RegexOption.MULTILINE).containsMatchIn(head)) return "SINGBOX_YAML"
        if (Regex("""<html|<!doctype|<body|<pre|<textarea""", RegexOption.IGNORE_CASE).containsMatchIn(head)) {
            return "HTML"
        }
        if (RadarText.looksBase64(body)) return "BASE64"
        if (RadarText.hasLink(body)) return "PLAIN"
        return "UNKNOWN"
    }

    /* ---------- 统一出口 ---------- */

    fun extract(body: String): ExtractResult {
        var type = detect(body)
        var text = body
        var note = ""
        var nodes: List<Map<String, Any?>> = emptyList()

        // 1) base64 展开（最多 2 层），展开后重新嗅探
        if (type == "BASE64") {
            val dec = RadarText.decodeBase64Chain(body)
            if (dec != null) {
                text = dec
                type = detect(text)
                note = "base64 已展开"
            } else {
                note = "base64 解码失败"
            }
        }

        // 2) 结构化解析（保真）
        when (type) {
            "CLASH_YAML" -> {
                nodes = parseClashProxies(text)
                note = "Clash YAML"
            }
            "JSON" -> {
                nodes = parseJsonContainer(text)
                note = "JSON 容器"
            }
            "SINGBOX_YAML" -> note = "sing-box YAML 暂未支持"
        }

        // 3) 正则兜底（保召回），无条件执行
        val links = RadarText.sweepLinks(text)
        if (note.isEmpty()) note = "正则直扫"

        return ExtractResult(type, text, nodes, links, note)
    }

    /* ---------- JSON 容器 ---------- */

    private val JSON = Json { ignoreUnknownKeys = true }

    private val JSON_ARRAY_KEYS = listOf("proxies", "outbounds", "Proxy", "proxyList")

    private fun parseJsonContainer(text: String): List<Map<String, Any?>> {
        val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() ?: return emptyList()

        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> JSON_ARRAY_KEYS.firstNotNullOfOrNull { root[it] as? JsonArray }
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { it as? JsonObject }.map(::jsonToMap)
    }

    private fun jsonToMap(o: JsonObject): Map<String, Any?> =
        o.mapValues { (_, v) -> jsonToAny(v) }

    private fun jsonToAny(v: JsonElement): Any? = when (v) {
        is JsonPrimitive -> when {
            v.isString -> v.content
            v.content == "true" -> true
            v.content == "false" -> false
            v.content == "null" -> null
            else -> v.content.toLongOrNull() ?: v.content.toDoubleOrNull() ?: v.content
        }
        is JsonObject -> jsonToMap(v)
        is JsonArray -> v.map(::jsonToAny)
        else -> null
    }

    /* ---------- Clash YAML 子集 ---------- */

    /**
     * 手写子集解析器而不是引 snakeyaml：只要 `proxies:` 这一段，
     * 而且必须容忍零缩进序列项（`proxies:` 下一行直接 `- name:`），
     * 标准库解析器对残缺 YAML 会整体抛异常，得不偿失。
     */
    fun parseClashProxies(text: String): List<Map<String, Any?>> {
        val lines = text.split('\n').map { it.trimEnd('\r') }
        var start = -1

        for (i in lines.indices) {
            if (Regex("""^\s*proxies\s*:""").containsMatchIn(stripComment(lines[i]))) {
                start = i
                break
            }
        }
        if (start < 0) return emptyList()

        val block = ArrayList<String>()
        for (j in start + 1 until lines.size) {
            val raw = stripComment(lines[j])
            if (raw.isBlank()) {
                block.add(raw)
                continue
            }
            // 顶层键即止；但零缩进的 "- " 是序列项不是键
            val isSeqItem = raw.startsWith("-") && (raw.length == 1 || raw[1].isWhitespace())
            if (!raw[0].isWhitespace() && !isSeqItem) break
            block.add(raw)
        }

        val entries = ArrayList<MutableList<String>>()
        var cur: MutableList<String>? = null
        var itemIndent = -1

        for (raw in block) {
            if (raw.isBlank()) {
                cur?.add(raw)
                continue
            }
            val ind = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }
            val body = raw.trim()
            if (body.startsWith("- ") || body == "-") {
                if (itemIndent < 0) itemIndent = ind
                if (ind == itemIndent) {
                    val list = ArrayList<String>()
                    list.add(raw)
                    entries.add(list)
                    cur = list
                    continue
                }
            }
            cur?.add(raw)
        }

        return entries.mapNotNull { parseEntry(it) }
    }

    /** 去注释，但不动引号里的 # */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == '\\') { i++; i++; continue }
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '#') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2) {
            val a = t.first()
            val b = t.last()
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) return t.substring(1, t.length - 1)
        }
        return t
    }

    /** 按 sep 切分，跳过引号内与 {} [] 内 */
    private fun splitTop(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        val cur = StringBuilder()

        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                cur.append(c)
                if (c == '\\' && i + 1 < s.length) { cur.append(s[i + 1]); i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            when {
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                c == '{' || c == '[' -> { depth++; cur.append(c) }
                c == '}' || c == ']' -> { depth--; cur.append(c) }
                c == sep && depth == 0 -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    private fun parseFlowMap(t: String): Map<String, Any?> {
        val obj = LinkedHashMap<String, Any?>()
        val inner = t.trim().removeSurrounding("{", "}")
        for (part in splitTop(inner, ',')) {
            val i = part.indexOf(':')
            if (i < 0) continue
            obj[unquote(part.substring(0, i))] = parseScalar(part.substring(i + 1))
        }
        return obj
    }

    private fun parseScalar(s: String): Any? {
        val t = s.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("{")) return parseFlowMap(t)
        if (t.startsWith("[")) {
            return splitTop(t.substring(1, t.length - 1), ',').map(::parseScalar)
        }
        return when (t) {
            "true" -> true
            "false" -> false
            "null", "~" -> null
            else -> t.toLongOrNull() ?: t.toDoubleOrNull() ?: unquote(t)
        }
    }

    /** 单条目：首行 "- " 换成两空格对齐，之后按缩进压栈建树 */
    private fun parseEntry(rawLines: List<String>): Map<String, Any?>? {
        if (rawLines.isEmpty()) return null

        val lines = ArrayList(rawLines)
        lines[0] = lines[0].replaceFirst(Regex("""^(\s*)- """), "$1  ")

        val root = LinkedHashMap<String, Any?>()
        // 栈里每一项记「这一层的缩进」与「往哪个 map 写」
        val stack = ArrayList<Pair<Int, MutableMap<String, Any?>>>()
        stack.add(-1 to root)

        for (line in lines) {
            if (line.isBlank()) continue
            val ind = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
            val body = line.trim()

            while (stack.size > 1 && ind <= stack.last().first) stack.removeAt(stack.size - 1)
            val top = stack.last().second

            val ci = body.indexOf(':')
            if (ci < 0) continue
            val k = unquote(body.substring(0, ci))
            val v = body.substring(ci + 1).trim()

            if (v.isEmpty()) {
                val child = LinkedHashMap<String, Any?>()
                top[k] = child
                stack.add(ind to child)
            } else {
                top[k] = parseScalar(v)
            }
        }

        return root
    }
}
