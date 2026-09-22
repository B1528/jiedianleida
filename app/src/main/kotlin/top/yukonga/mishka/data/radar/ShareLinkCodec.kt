package top.yukonga.mishka.data.radar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yukonga.mishka.domain.model.RadarGrpc
import top.yukonga.mishka.domain.model.RadarNode
import top.yukonga.mishka.domain.model.RadarTls
import top.yukonga.mishka.domain.model.RadarWs

/**
 * 分享链 ↔ [RadarNode] 双向编解码 + 指纹。
 *
 * 不用 android.net.Uri 解析：一是 ss:// 会把整段凭据嵌在 base64 里、Uri 解出来是乱的，
 * 二是本文件保持零 Android 依赖，往返自检可以在纯 JVM 单测里跑完。
 */
internal object ShareLinkCodec {

    data class ParseResult(
        val total: Int,
        val nodes: List<RadarNode>,
        val failed: List<String>,
    )

    data class RoundTripFailure(
        val protocol: String,
        val name: String,
        val server: String,
        val port: Int,
        val link: String,
    )

    data class RoundTripResult(
        val total: Int,
        val failed: List<RoundTripFailure>,
    )

    /* ---------- 入口：链接 → 节点 ---------- */

    fun parseLink(link: String): RadarNode? {
        val s = link.trim()
        val sep = s.indexOf("://")
        if (sep <= 0) return null

        return runCatching {
            when (s.substring(0, sep).lowercase()) {
                "vmess" -> parseVmess(s)
                "ss" -> parseSs(s)
                "ssr" -> parseSsr(s)
                "vless", "trojan", "hysteria2", "hy2", "tuic" -> parseUrlStyle(s)
                else -> null
            }
        }.getOrNull()
    }

    fun parseAll(links: List<String>): ParseResult {
        val nodes = ArrayList<RadarNode>(links.size)
        val failed = ArrayList<String>()
        for (l in links) {
            val n = parseLink(l)
            if (n != null) nodes.add(n) else failed.add(l)
        }
        return ParseResult(links.size, nodes, failed)
    }

    /* ---------- vmess ---------- */

    private fun parseVmess(link: String): RadarNode? {
        val body = link.substringAfter("://").substringBefore('#')
        val json = RadarText.decodeBase64(body) ?: return null
        val o = runCatching { JSON.parseToJsonElement(json) as JsonObject }.getOrNull() ?: return null

        val tlsMode = o.str("tls").lowercase()
        val sni = o.str("sni").ifEmpty { o.str("host") }
        val net = o.str("net").ifEmpty { "tcp" }.lowercase()

        return RadarNode(
            protocol = "vmess",
            server = o.str("add"),
            port = o.str("port").toIntOrNull() ?: 0,
            uuid = o.str("id"),
            alterId = o.str("aid").toIntOrNull() ?: 0,
            method = o.str("scy").ifEmpty { "auto" },
            network = net,
            name = o.str("ps"),
            tls = if (tlsMode == "tls" || tlsMode == "true" || tlsMode == "reality") {
                RadarTls(
                    sni = sni,
                    alpn = o.str("alpn"),
                    fp = o.str("fp"),
                    pbk = o.str("pbk"),
                    sid = o.str("sid"),
                    reality = tlsMode == "reality",
                )
            } else null,
            ws = if (net == "ws") RadarWs(o.str("path").ifEmpty { "/" }, o.str("host")) else null,
            grpc = if (net == "grpc") RadarGrpc(o.str("path")) else null,
            raw = link,
        )
    }

    /* ---------- vless / trojan / hysteria2 / tuic ---------- */

    private fun parseUrlStyle(link: String): RadarNode? {
        val u = parseRawUrl(link) ?: return null
        val proto = if (u.scheme == "hy2") "hysteria2" else u.scheme
        val network = (u.query["type"] ?: "tcp").lowercase()
        val security = (u.query["security"] ?: "").lowercase()
        val sni = u.query["sni"] ?: u.query["peer"] ?: ""
        val insecure = u.query["allowInsecure"] == "1" || u.query["insecure"] == "1"

        val userInfo = u.userInfo
        val user = userInfo.substringBefore(':').let(RadarText::decodeComponent)
        val pass = userInfo.substringAfter(':', "").let(RadarText::decodeComponent)

        return RadarNode(
            protocol = proto,
            server = u.host,
            port = u.port,
            uuid = if (proto == "vless" || proto == "tuic") user else "",
            password = when (proto) {
                "trojan", "hysteria2" -> user
                "tuic" -> pass
                else -> ""
            },
            flow = u.query["flow"] ?: "",
            network = network,
            name = u.fragment,
            tls = if (security == "tls" || security == "reality" || sni.isNotEmpty() || insecure) {
                RadarTls(
                    sni = sni,
                    alpn = u.query["alpn"] ?: "",
                    fp = u.query["fp"] ?: "",
                    insecure = insecure,
                    pbk = u.query["pbk"] ?: "",
                    sid = u.query["sid"] ?: "",
                    reality = security == "reality",
                )
            } else null,
            ws = if (network == "ws") {
                RadarWs(u.query["path"] ?: "/", u.query["host"] ?: "")
            } else null,
            grpc = if (network == "grpc") RadarGrpc(u.query["serviceName"] ?: "") else null,
            raw = link,
        )
    }

    /* ---------- ss ---------- */

    private fun parseSs(link: String): RadarNode? {
        var rest = link.substringAfter("://")

        var name = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            name = RadarText.decodeComponent(rest.substring(hash + 1))
            rest = rest.substring(0, hash)
        }

        var plugin = ""
        val q = rest.indexOf('?')
        if (q >= 0) {
            plugin = rest.substring(q + 1)
            rest = rest.substring(0, q)
        }

        var userInfo: String
        var hostPort: String

        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            userInfo = rest.substring(0, at)
            hostPort = rest.substring(at + 1)
            // SIP002 允许 userinfo 明文，也允许 base64(method:password)
            if (userInfo.indexOf(':') < 0) {
                userInfo = RadarText.decodeBase64(userInfo) ?: userInfo
            }
        } else {
            // 整串 base64：base64(method:password@host:port)
            val decoded = RadarText.decodeBase64(rest) ?: return null
            val at2 = decoded.lastIndexOf('@')
            if (at2 < 0) return null
            userInfo = decoded.substring(0, at2)
            hostPort = decoded.substring(at2 + 1)
        }

        val colon = userInfo.indexOf(':')
        if (colon < 0) return null

        val portColon = hostPort.lastIndexOf(':')
        if (portColon < 0) return null

        return RadarNode(
            protocol = "ss",
            server = hostPort.substring(0, portColon),
            port = hostPort.substring(portColon + 1).toIntOrNull() ?: 0,
            method = userInfo.substring(0, colon),
            password = userInfo.substring(colon + 1),
            plugin = plugin,
            name = name,
            raw = link,
        )
    }

    /* ---------- ssr ---------- */

    private fun parseSsr(link: String): RadarNode? {
        val decoded = RadarText.decodeBase64(link.substringAfter("://")) ?: return null
        val head = decoded.substringBefore("/?").split(':')
        if (head.size < 6) return null

        var name = ""
        val params = decoded.substringAfter("/?", "")
        if (params.isNotEmpty()) {
            val remarks = params.split('&')
                .firstOrNull { it.startsWith("remarks=") }
                ?.substringAfter('=')
            if (!remarks.isNullOrEmpty()) {
                name = RadarText.decodeBase64(remarks) ?: remarks
            }
        }

        return RadarNode(
            protocol = "ssr",
            server = head[0],
            port = head[1].toIntOrNull() ?: 0,
            method = head[3],
            password = RadarText.decodeBase64(head[5]) ?: head[5],
            network = head[2],
            name = name,
            raw = link,
        )
    }

    /* ---------- 出口：节点 → 链接 ---------- */

    fun toLink(n: RadarNode): String {
        val hp = "${n.server}:${n.port}"
        val frag = if (n.name.isNotEmpty()) "#" + RadarText.encodeComponent(n.name) else ""

        return when (n.protocol) {
            "vmess" -> {
                val sb = StringBuilder("{")
                sb.append("\"v\":\"2\",")
                sb.append("\"ps\":\"").append(escapeJson(n.name)).append("\",")
                sb.append("\"add\":\"").append(escapeJson(n.server)).append("\",")
                sb.append("\"port\":\"").append(n.port).append("\",")
                sb.append("\"id\":\"").append(escapeJson(n.uuid)).append("\",")
                sb.append("\"aid\":\"").append(n.alterId).append("\",")
                sb.append("\"scy\":\"").append(escapeJson(n.method.ifEmpty { "auto" })).append("\",")
                sb.append("\"net\":\"").append(escapeJson(n.network.ifEmpty { "tcp" })).append("\",")
                sb.append("\"type\":\"none\"")
                if (n.tls != null) {
                    sb.append(",\"tls\":\"").append(if (n.tls.reality) "reality" else "tls").append("\"")
                    sb.append(",\"sni\":\"").append(escapeJson(n.tls.sni)).append("\"")
                    if (n.tls.alpn.isNotEmpty()) sb.append(",\"alpn\":\"").append(escapeJson(n.tls.alpn)).append("\"")
                    if (n.tls.fp.isNotEmpty()) sb.append(",\"fp\":\"").append(escapeJson(n.tls.fp)).append("\"")
                    if (n.tls.pbk.isNotEmpty()) sb.append(",\"pbk\":\"").append(escapeJson(n.tls.pbk)).append("\"")
                    if (n.tls.sid.isNotEmpty()) sb.append(",\"sid\":\"").append(escapeJson(n.tls.sid)).append("\"")
                }
                if (n.ws != null) {
                    sb.append(",\"path\":\"").append(escapeJson(n.ws.path)).append("\"")
                    sb.append(",\"host\":\"").append(escapeJson(n.ws.host)).append("\"")
                }
                if (n.grpc != null) {
                    sb.append(",\"path\":\"").append(escapeJson(n.grpc.serviceName)).append("\"")
                }
                sb.append("}")
                "vmess://" + RadarText.encodeBase64(sb.toString())
            }

            "vless" -> "vless://${RadarText.encodeComponent(n.uuid)}@$hp${buildQuery(n)}$frag"
            "trojan" -> "trojan://${RadarText.encodeComponent(n.password)}@$hp${buildQuery(n)}$frag"
            "hysteria2" -> "hysteria2://${RadarText.encodeComponent(n.password)}@$hp${buildQuery(n)}$frag"
            "tuic" -> "tuic://${RadarText.encodeComponent(n.uuid)}:" +
                "${RadarText.encodeComponent(n.password)}@$hp${buildQuery(n)}$frag"

            "ss" -> "ss://" + RadarText.encodeBase64("${n.method}:${n.password}") + "@$hp" +
                (if (n.plugin.isNotEmpty()) "?${n.plugin}" else "") + frag

            else -> n.raw
        }
    }

    private fun buildQuery(n: RadarNode): String {
        val p = ArrayList<String>()
        val net = n.network
        if (net.isNotEmpty() && net != "tcp") p.add("type=" + RadarText.encodeComponent(net))

        n.tls?.let { t ->
            p.add("security=" + if (t.reality) "reality" else "tls")
            if (t.sni.isNotEmpty()) p.add("sni=" + RadarText.encodeComponent(t.sni))
            if (t.alpn.isNotEmpty()) p.add("alpn=" + RadarText.encodeComponent(t.alpn))
            if (t.fp.isNotEmpty()) p.add("fp=" + RadarText.encodeComponent(t.fp))
            if (t.insecure) p.add("allowInsecure=1")
            if (t.pbk.isNotEmpty()) p.add("pbk=" + RadarText.encodeComponent(t.pbk))
            if (t.sid.isNotEmpty()) p.add("sid=" + RadarText.encodeComponent(t.sid))
        }

        n.ws?.let { w ->
            if (w.path.isNotEmpty()) p.add("path=" + RadarText.encodeComponent(w.path))
            if (w.host.isNotEmpty()) p.add("host=" + RadarText.encodeComponent(w.host))
        }

        n.grpc?.let { g ->
            if (g.serviceName.isNotEmpty()) p.add("serviceName=" + RadarText.encodeComponent(g.serviceName))
        }

        if (n.flow.isNotEmpty()) p.add("flow=" + RadarText.encodeComponent(n.flow))

        return if (p.isEmpty()) "" else "?" + p.joinToString("&")
    }

    /* ---------- 指纹 ---------- */

    /** 只取「怎么连」相关的字段。名字与来源不同不构成不同节点，这是跨源去重的依据 */
    fun fingerprint(n: RadarNode): String = listOf(
        n.protocol, n.server, n.port.toString(),
        n.uuid, n.password, n.method, n.alterId.toString(), n.flow,
        n.network,
        n.tls?.sni ?: "", n.tls?.pbk ?: "", n.tls?.sid ?: "", if (n.tls?.reality == true) "1" else "",
        n.ws?.path ?: "", n.ws?.host ?: "",
        n.grpc?.serviceName ?: "",
    ).joinToString("|")

    /* ---------- 往返自检 ---------- */

    /** node → link → node，指纹必须一致。用来抓「解析器读错字段」这类静默 bug */
    fun roundTrip(nodes: List<RadarNode>): RoundTripResult {
        val failed = ArrayList<RoundTripFailure>()
        for (n in nodes) {
            val link = toLink(n)
            val back = if (link.isEmpty()) null else parseLink(link)
            if (back == null || fingerprint(back) != fingerprint(n)) {
                failed.add(RoundTripFailure(n.protocol, n.name, n.server, n.port, link))
            }
        }
        return RoundTripResult(nodes.size, failed)
    }

    /* ---------- Clash 对象 → 节点 ---------- */

    fun fromClash(o: Map<String, Any?>): RadarNode? {
        val proto = (o["type"] as? String)?.lowercase().orEmpty()
        if (proto.isEmpty()) return null

        val sni = (o["servername"] as? String)
            ?: (o["sni"] as? String)
            ?: (o["peer"] as? String)
            ?: ""
        val insecure = o["skip-cert-verify"] == true
        @Suppress("UNCHECKED_CAST")
        val ro = o["reality-opts"] as? Map<String, Any?>

        val network = (o["network"] as? String).orEmpty().lowercase().ifEmpty { "tcp" }

        @Suppress("UNCHECKED_CAST")
        val ws = o["ws-opts"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val wsHeaders = ws?.get("headers") as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val gr = o["grpc-opts"] as? Map<String, Any?>

        return RadarNode(
            protocol = proto,
            server = (o["server"] as? String).orEmpty(),
            port = (o["port"] as? Number)?.toInt() ?: (o["port"] as? String)?.toIntOrNull() ?: 0,
            uuid = (o["uuid"] as? String).orEmpty(),
            password = (o["password"] as? String).orEmpty(),
            method = ((o["cipher"] as? String) ?: (o["method"] as? String)).orEmpty(),
            alterId = (o["alterId"] as? Number)?.toInt() ?: (o["alterId"] as? String)?.toIntOrNull() ?: 0,
            flow = (o["flow"] as? String).orEmpty(),
            network = network,
            name = (o["name"] as? String).orEmpty(),
            tls = if (o["tls"] == true || sni.isNotEmpty() || insecure || ro != null ||
                proto == "trojan" || proto == "hysteria2"
            ) {
                RadarTls(
                    sni = sni,
                    alpn = when (val a = o["alpn"]) {
                        is List<*> -> a.joinToString(",") { it.toString() }
                        is String -> a
                        else -> ""
                    },
                    fp = (o["client-fingerprint"] as? String).orEmpty(),
                    insecure = insecure,
                    pbk = (ro?.get("public-key") as? String).orEmpty(),
                    sid = (ro?.get("short-id") as? String).orEmpty(),
                    reality = ro != null,
                )
            } else null,
            ws = ws?.let {
                RadarWs(
                    path = (it["path"] as? String) ?: "/",
                    host = ((wsHeaders?.get("Host") ?: wsHeaders?.get("host")) as? String).orEmpty(),
                )
            },
            grpc = gr?.let { RadarGrpc((it["grpc-service-name"] as? String).orEmpty()) },
        )
    }

    /* ---------- 私有工具 ---------- */

    private val JSON = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()

    private fun escapeJson(s: String): String = buildString(s.length) {
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

    private class RawUrl(
        val scheme: String,
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
    )

    /**
     * 手写而不是用 java.net.URI：URI 对缺 scheme 的分享链会抛异常，
     * 而且 IPv6 字面量与转义后的 userInfo 处理跟浏览器不一致。
     */
    private fun parseRawUrl(link: String): RawUrl? {
        val sep = link.indexOf("://")
        if (sep <= 0) return null
        val scheme = link.substring(0, sep).lowercase()
        var rest = link.substring(sep + 3)

        var fragment = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            fragment = RadarText.decodeComponent(rest.substring(hash + 1))
            rest = rest.substring(0, hash)
        }

        val query = LinkedHashMap<String, String>()
        val q = rest.indexOf('?')
        if (q >= 0) {
            for (pair in rest.substring(q + 1).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq < 0) query[RadarText.decodeComponent(pair)] = ""
                else query[RadarText.decodeComponent(pair.substring(0, eq))] =
                    RadarText.decodeComponent(pair.substring(eq + 1))
            }
            rest = rest.substring(0, q)
        }

        var userInfo = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            userInfo = rest.substring(0, at)
            rest = rest.substring(at + 1)
        }

        var host = rest
        var port = 0
        val colon = rest.lastIndexOf(':')
        if (colon >= 0) {
            host = rest.substring(0, colon)
            port = rest.substring(colon + 1).toIntOrNull() ?: 0
        }

        return RawUrl(scheme, userInfo, host, port, query, fragment)
    }
}
