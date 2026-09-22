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

    /** Clash 的代理名必须全局唯一，否则内核直接拒绝加载整份配置 */
    fun uniqueNames(nodes: List<RadarNode>): List<String> {
        val used = HashSet<String>()
        val out = ArrayList<String>(nodes.size)

        for (n in nodes) {
            val base = n.name.trim().ifEmpty { "${n.server}:${n.port}" }
            var name = base
            if (!used.add(name)) {
                var i = 2
                while (!used.add(name)) {
                    name = "$base #$i"
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

    private fun yamlScalar(v: Any?): String = when (v) {
        null -> "\"\""
        is Boolean -> if (v) "true" else "false"
        is Number -> v.toString()
        else -> {
            val s = v.toString()
            when {
                s.isEmpty() -> "\"\""
                PLAIN_YAML.matches(s) -> s
                else -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
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

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
