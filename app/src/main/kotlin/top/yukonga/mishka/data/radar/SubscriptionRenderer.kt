package top.yukonga.mishka.data.radar

import top.yukonga.mishka.domain.model.RadarNode

/**
 * 把统一模型渲染成各家客户端能吃的订阅格式。
 *
 * 只在导出这一刻才分流：内部模型是唯一真相源，v2rayN / Clash / sing-box 都只是渲染器。
 * 新增一种目标格式不需要碰解析和去重，加一个函数就行。
 */
internal object SubscriptionRenderer {

    private val PLAIN_YAML = Regex("^[A-Za-z0-9_.\\-/]+$")

    /** YAML 会把这些裸标量解析成布尔/空，而不是字符串 */
    private val YAML_LITERAL = Regex(
        "^(?:y|n|yes|no|true|false|on|off|null|nan|inf|~)$",
        RegexOption.IGNORE_CASE,
    )

    /** Clash 的代理名必须全局唯一，否则内核直接拒绝加载整份配置 */
    fun uniqueNames(nodes: List<RadarNode>): List<String> {
        val used = HashSet<String>()
        val out = ArrayList<String>(nodes.size)

        for (n in nodes) {
            val base = clean(n.name).trim().ifEmpty { "${n.server}:${n.port}" }
            var name = base
            if (!used.add(name)) {
                var i = 2
                while (true) {
                    name = "$base-$i"
                    if (used.add(name)) break
                    i++
                }
            }
            out.add(name)
        }
        return out
    }

    /* ---------- v2rayN / 通用：base64(链接按行拼接) ---------- */

    fun v2rayN(nodes: List<RadarNode>): String {
        val links = ArrayList<String>(nodes.size)
        for (n in nodes) {
            val link = ShareLinkCodec.toLink(n)
            if (link.isNotEmpty()) links.add(link)
            else if (n.raw.isNotEmpty()) links.add(n.raw)
        }
        return RadarText.encodeBase64(links.joinToString("\n"))
    }

    /* ---------- Clash / mihomo ---------- */

    fun clash(nodes: List<RadarNode>): String {
        val names = uniqueNames(nodes)
        val sb = StringBuilder()
        sb.append("proxies:\n")

        val emitted = ArrayList<String>(nodes.size)
        for ((i, n) in nodes.withIndex()) {
            val p = toClashProxy(n, names[i]) ?: continue
            emitted.add(names[i])
            writeProxy(sb, p)
        }

        sb.append("\nproxy-groups:\n")
        writeGroup(sb, "🚀 节点选择", "select", null, emitted, listOf("♻️ 自动选择", "DIRECT"))
        sb.append('\n')
        writeGroup(
            sb, "♻️ 自动选择", "url-test",
            "http://www.gstatic.com/generate_204", emitted, emptyList(),
        )

        sb.append("\nrules:\n")
        sb.append("  - GEOIP,CN,DIRECT\n")
        sb.append("  - MATCH,🚀 节点选择\n")

        return sb.toString()
    }

    /**
     * 只输出 `proxies:` 段 —— 这是 mihomo file provider 的文件格式。
     * 带上 proxy-groups / rules 属于多余键，FileProvider 不需要。
     */
    fun clashProvider(nodes: List<RadarNode>): String {
        val names = uniqueNames(nodes)
        val sb = StringBuilder()
        sb.append("proxies:\n")
        for ((i, n) in nodes.withIndex()) {
            val p = toClashProxy(n, names[i]) ?: continue
            writeProxy(sb, p)
        }
        return sb.toString()
    }

    private fun writeGroup(
        sb: StringBuilder,
        name: String,
        type: String,
        url: String?,
        members: List<String>,
        prefix: List<String>,
    ) {
        sb.append("  - name: ").append(yamlScalar(name)).append('\n')
        sb.append("    type: ").append(type).append('\n')
        if (url != null) {
            sb.append("    url: ").append(yamlScalar(url)).append('\n')
            sb.append("    interval: 300\n")
        }
        sb.append("    proxies:\n")
        for (p in prefix) sb.append("      - ").append(yamlScalar(p)).append('\n')
        for (m in members) sb.append("      - ").append(yamlScalar(m)).append('\n')
    }

    private fun writeProxy(sb: StringBuilder, p: Map<String, Any?>) {
        val keys = p.keys.toList()
        if (keys.isEmpty()) return

        sb.append("  - ").append(keys[0]).append(": ").append(yamlScalar(p[keys[0]])).append('\n')
        for (k in keys.drop(1)) emitNode(sb, k, p[k], 4)
    }

    /** 递归发射，nested map 缩进 +2；list 一律走行内 flow 写法 */
    private fun emitNode(sb: StringBuilder, key: String, value: Any?, indent: Int) {
        val pad = " ".repeat(indent)
        when (value) {
            is Map<*, *> -> {
                sb.append(pad).append(key).append(":\n")
                for ((k, v) in value) emitNode(sb, k.toString(), v, indent + 2)
            }
            is List<*> -> {
                sb.append(pad).append(key).append(": [")
                sb.append(value.joinToString(", ") { yamlScalar(it) })
                sb.append("]\n")
            }
            else -> sb.append(pad).append(key).append(": ").append(yamlScalar(value)).append('\n')
        }
    }

    /**
     * go-yaml 撞上 C0/C1 控制字符、U+FFFE/U+FFFF 非字符或落单代理项，会整份拒绝并报
     * `yaml: control characters are not allowed`——provider 里哪怕只有一个字段中招，内核也会
     * 加载出 0 个代理，表现为全部节点「不可用」。这些码位在订阅源里不罕见（名字/密码常由
     * base64 或链接片段拼出），且肉眼与文本编辑器都看不出来，故在写出的最后一刻统一剥掉。
     */
    private fun clean(s: String): String {
        if (!hasForbidden(s)) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // 合法代理对（emoji）必须整对保留，只有落单的代理项才是非法码位
            if (isHighSurrogate(c) && i + 1 < s.length && isLowSurrogate(s[i + 1])) {
                sb.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (!isForbidden(c)) sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun hasForbidden(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (isHighSurrogate(c) && i + 1 < s.length && isLowSurrogate(s[i + 1])) {
                i += 2
                continue
            }
            if (isForbidden(c)) return true
            i++
        }
        return false
    }

    private fun isHighSurrogate(c: Char) = c.code in 0xD800..0xDBFF

    private fun isLowSurrogate(c: Char) = c.code in 0xDC00..0xDFFF

    /** 落单代理项（合法代理对已在上层整对放行）与 go-yaml 拒绝的不可见码位 */
    private fun isForbidden(c: Char): Boolean {
        val code = c.code
        return code < 0x20 || code == 0x7F || code in 0x80..0x9F ||
            code == 0xFFFE || code == 0xFFFF || code in 0xD800..0xDFFF
    }

    private fun yamlScalar(v: Any?): String = when (v) {
        null -> "\"\""
        is Boolean -> if (v) "true" else "false"
        is Number -> v.toString()
        else -> {
            val s = clean(v.toString())
            when {
                s.isEmpty() -> "\"\""
                isPlainSafe(s) -> s
                else -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
        }
    }

    /**
     * 裸标量只有在能以字符串解读时才安全：`name: 123` / `name: true` 会被 go-yaml 解析成
     * 整数/布尔，mihomo 再把它解码进 `Name string` 字段就失败，整份 provider 加载出 0 个代理。
     * 故要求首字符是字母或下划线（排除纯数字、`-1`、`0x1F`、时间戳等），并排除空/布尔字面量。
     */
    private fun isPlainSafe(s: String): Boolean {
        if (!PLAIN_YAML.matches(s)) return false
        val first = s[0]
        if (first != '_' && first !in 'A'..'Z' && first !in 'a'..'z') return false
        return !YAML_LITERAL.matches(s)
    }

    /**
     * ss 的 plugin 查询串（`plugin=obfs-local;obfs=http;obfs-host=x`）→ mihomo 的
     * `plugin` / `plugin-opts`。只认最常见的两种：mihomo 对未知 plugin 名会判该节点非法，
     * 认不出来时宁可不写 —— 少一个节点，也好过整份 provider 被带塌。
     */
    private fun applySsPlugin(p: LinkedHashMap<String, Any?>, raw: String) {
        val parts = raw.removePrefix("plugin=").split(';').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val name = parts.first().substringBefore('=')
        val kv = parts.drop(1).mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

        when (name) {
            "obfs-local", "simple-obfs", "obfs" -> {
                val opts = LinkedHashMap<String, Any?>()
                kv["obfs"]?.let { opts["mode"] = it }
                kv["obfs-host"]?.let { opts["host"] = it }
                p["plugin"] = "obfs"
                if (opts.isNotEmpty()) p["plugin-opts"] = opts
            }

            "v2ray-plugin" -> {
                val opts = LinkedHashMap<String, Any?>()
                kv["mode"]?.let { opts["mode"] = it }
                kv["host"]?.let { opts["host"] = it }
                kv["path"]?.let { opts["path"] = it }
                if ("tls" in kv) opts["tls"] = true
                p["plugin"] = "v2ray-plugin"
                if (opts.isNotEmpty()) p["plugin-opts"] = opts
            }
        }
    }

    /** 统一模型 → Clash proxy 字段。不支持的协议返回 null，由调用方跳过 */
    private fun toClashProxy(n: RadarNode, name: String): LinkedHashMap<String, Any?>? {
        val p = LinkedHashMap<String, Any?>()
        p["name"] = name
        p["type"] = n.protocol
        p["server"] = n.server
        p["port"] = n.port

        when (n.protocol) {
            "vmess" -> {
                p["uuid"] = n.uuid
                p["alterId"] = n.alterId
                p["cipher"] = n.method.ifEmpty { "auto" }
            }
            "vless" -> {
                p["uuid"] = n.uuid
                if (n.flow.isNotEmpty()) p["flow"] = n.flow
            }
            "trojan", "hysteria2" -> p["password"] = n.password
            "tuic" -> {
                p["uuid"] = n.uuid
                p["password"] = n.password
            }
            "ss" -> {
                p["cipher"] = n.method
                p["password"] = n.password
                applySsPlugin(p, n.plugin)
            }
            else -> return null
        }

        if (n.network.isNotEmpty() && n.network != "tcp") p["network"] = n.network

        n.tls?.let { t ->
            if (t.sni.isNotEmpty()) p["servername"] = t.sni
            if (t.insecure) p["skip-cert-verify"] = true
            if (t.alpn.isNotEmpty()) {
                p["alpn"] = t.alpn.split(',', ' ').filter { it.isNotEmpty() }
            }
            if (t.reality && t.pbk.isNotEmpty()) {
                val ro = LinkedHashMap<String, Any?>()
                ro["public-key"] = t.pbk
                if (t.sid.isNotEmpty()) ro["short-id"] = t.sid
                p["reality-opts"] = ro
                p["client-fingerprint"] = t.fp.ifEmpty { "chrome" }
            } else if (t.fp.isNotEmpty()) {
                p["client-fingerprint"] = t.fp
            }
        }

        n.ws?.let { w ->
            val ws = LinkedHashMap<String, Any?>()
            ws["path"] = w.path.ifEmpty { "/" }
            if (w.host.isNotEmpty()) ws["headers"] = linkedMapOf<String, Any?>("Host" to w.host)
            p["ws-opts"] = ws
        }

        n.grpc?.let { g ->
            if (g.serviceName.isNotEmpty()) {
                p["grpc-opts"] = linkedMapOf<String, Any?>("grpc-service-name" to g.serviceName)
            }
        }

        return p
    }

    /* ---------- sing-box ---------- */

    fun singbox(nodes: List<RadarNode>): String {
        val out = ArrayList<String>(nodes.size)

        for (n in nodes) {
            val o = toSingboxOutbound(n) ?: continue
            out.add(o)
        }

        return "{\n  \"outbounds\": [\n" + out.joinToString(",\n") + "\n  ]\n}\n"
    }

    private fun toSingboxOutbound(n: RadarNode): String? {
        val tag = n.name.ifEmpty { "${n.server}:${n.port}" }
        val sb = StringBuilder()
        sb.append("    {")
        sb.append("\"type\":\"").append(esc(n.protocol)).append("\",")
        sb.append("\"tag\":\"").append(esc(tag)).append("\",")
        sb.append("\"server\":\"").append(esc(n.server)).append("\",")
        sb.append("\"server_port\":").append(n.port)

        when (n.protocol) {
            "vmess" -> {
                sb.append(",\"uuid\":\"").append(esc(n.uuid)).append("\"")
                sb.append(",\"alter_id\":").append(n.alterId)
                sb.append(",\"security\":\"").append(esc(n.method.ifEmpty { "auto" })).append("\"")
            }
            "vless" -> {
                sb.append(",\"uuid\":\"").append(esc(n.uuid)).append("\"")
                if (n.flow.isNotEmpty()) sb.append(",\"flow\":\"").append(esc(n.flow)).append("\"")
            }
            "trojan", "hysteria2" -> {
                sb.append(",\"password\":\"").append(esc(n.password)).append("\"")
            }
            "ss" -> {
                sb.append(",\"method\":\"").append(esc(n.method)).append("\"")
                sb.append(",\"password\":\"").append(esc(n.password)).append("\"")
            }
            else -> return null
        }

        n.ws?.let { w ->
            sb.append(",\"transport\":{\"type\":\"ws\",\"path\":\"").append(esc(w.path.ifEmpty { "/" })).append("\"")
            if (w.host.isNotEmpty()) {
                sb.append(",\"headers\":{\"Host\":\"").append(esc(w.host)).append("\"}")
            }
            sb.append("}")
        }

        n.tls?.let { t ->
            if (t.sni.isNotEmpty() || t.insecure) {
                sb.append(",\"tls\":{\"enabled\":true")
                if (t.sni.isNotEmpty()) sb.append(",\"server_name\":\"").append(esc(t.sni)).append("\"")
                if (t.insecure) sb.append(",\"insecure\":true")
                sb.append("}")
            }
        }

        sb.append("}")
        return sb.toString()
    }

    /** JSON 字符串转义。控制字符已由 [clean] 剥掉，这里只剩引号与反斜杠要处理 */
    private fun esc(s: String): String = buildString(s.length) {
        for (c in clean(s)) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(c)
            }
        }
    }
}
