package top.yukonga.mishka.data.radar

import top.yukonga.mishka.domain.model.RadarNode

/**
 * L0 静态过滤 + 归一化 + 指纹去重。
 *
 * L0 只淘汰「明显不可能可用」的条目（内网地址、端口越界、缺凭证），不做任何网络探测 ——
 * 它的判据全部来自节点自身字段，因此可以放在去重之前跑，避免给垃圾算指纹。
 *
 * 去重按 [ShareLinkCodec.fingerprint]，它只取连接参数、不含 name —— 同一个节点在不同源里
 * 名字往往不一样，按名字去重等于没去。副产品 sourceCount（命中几个源）是白捡的质量分，
 * 出现在越多源里的节点越可能是活的，默认按它降序，供上层决定先测谁。
 */
internal object RadarDedupe {

    data class L0Drop(val node: RadarNode, val reason: String)

    data class DedupeResult(
        val input: Int,
        val l0Dropped: List<L0Drop>,
        val unique: List<RadarNode>,
        val duplicates: Int,
        val multiSource: Int,
    )

    data class Summary(
        val input: Int,
        val l0Dropped: Int,
        val unique: Int,
        val duplicates: Int,
        val multiSource: Int,
        val serverCount: Int,
        val sharedServers: Int,
        val byProtocol: Map<String, Int>,
        val bySource: Map<String, Int>,
    )

    /* ---------- L0：只淘汰，不判定 ---------- */

    private val BAD_HOST = Regex("^(localhost|localhost\\.localdomain)$", RegexOption.IGNORE_CASE)
    private val NET_0 = Regex("^0\\.")
    private val NET_10 = Regex("^10\\.")
    private val NET_127 = Regex("^127\\.")
    private val NET_192 = Regex("^192\\.168\\.")
    private val NET_172 = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.")
    private val NET_169 = Regex("^169\\.254\\.")
    // ULA 是 fc00::/7，必须带上首组的 4 位十六进制和冒号；裸 fc/fd 前缀会把
    // fcdn.example.com 这类正常域名一起判成内网
    private val NET_V6 = Regex("^(::1|fe80:|f[cd][0-9a-f]{2}:)", RegexOption.IGNORE_CASE)

    private fun isBadHost(h: String): Boolean =
        BAD_HOST.containsMatchIn(h) ||
            NET_0.containsMatchIn(h) ||
            NET_10.containsMatchIn(h) ||
            NET_127.containsMatchIn(h) ||
            NET_192.containsMatchIn(h) ||
            NET_172.containsMatchIn(h) ||
            NET_169.containsMatchIn(h) ||
            h.startsWith(":") ||
            NET_V6.containsMatchIn(h)

    private fun needsUuid(proto: String) = proto == "vmess" || proto == "vless" || proto == "tuic"

    private fun needsPassword(proto: String) =
        proto == "trojan" || proto == "hysteria2" || proto == "ss" || proto == "tuic"

    /** 返回 null 表示通过；否则是淘汰原因 */
    fun l0Check(n: RadarNode?): String? {
        if (n == null) return "空节点"

        val proto = n.protocol.lowercase()
        if (proto.isEmpty()) return "无协议"

        val host = n.server.trim()
        if (host.isEmpty()) return "无服务器"
        if (host.any { it.isWhitespace() }) return "服务器含空白"
        if (isBadHost(host)) return "内网/保留地址"

        if (n.port < 1 || n.port > 65535) return "端口非法"

        if (needsUuid(proto) && n.uuid.isEmpty()) return "缺 uuid"
        if (needsPassword(proto) && n.password.isEmpty()) return "缺密码"
        if (proto == "ss" && n.method.isEmpty()) return "缺加密方式"

        return null
    }

    /* ---------- 归一化 ---------- */

    fun normalize(n: RadarNode): RadarNode = n.copy(
        protocol = n.protocol.lowercase(),
        server = n.server.trim().lowercase(),
        network = n.network.ifEmpty { "tcp" }.lowercase(),
        name = n.name.trim(),
        method = n.method.lowercase(),
        uuid = n.uuid.trim(),
    )

    /* ---------- 同指纹择优 ---------- */

    private fun richness(n: RadarNode): Int {
        var c = 0
        if (n.uuid.isNotEmpty()) c++
        if (n.password.isNotEmpty()) c++
        if (n.method.isNotEmpty()) c++
        if (n.flow.isNotEmpty()) c++
        if (n.network.isNotEmpty() && n.network != "tcp") c++
        if (n.tls != null) c += 3
        if (n.ws != null) c += 2
        if (n.grpc != null) c += 2
        if (n.name.isNotEmpty()) c++
        return c
    }

    private fun pickBetter(a: RadarNode, b: RadarNode): RadarNode {
        val best = if (richness(b) > richness(a)) b else a
        val other = if (best === a) b else a

        var out = best
        // 名字取更长的：各源命名风格不同，长的那条通常带地区/编号，信息更多
        if (other.name.length > best.name.length) out = out.copy(name = other.name)
        if (out.raw.isEmpty()) out = out.copy(raw = other.raw)
        return out
    }

    /* ---------- 主入口 ---------- */

    fun dedupe(
        nodes: List<RadarNode>,
        doL0: Boolean = true,
        sort: Boolean = true,
    ): DedupeResult {
        val dropped = ArrayList<L0Drop>()

        val kept = if (!doL0) {
            nodes
        } else {
            nodes.filter { n ->
                val why = l0Check(n)
                if (why != null) dropped.add(L0Drop(n, why))
                why == null
            }
        }

        val byFp = LinkedHashMap<String, RadarNode>()
        val srcMap = LinkedHashMap<String, MutableSet<String>>()

        for (raw in kept) {
            val n = normalize(raw)
            val key = ShareLinkCodec.fingerprint(n)

            val existing = byFp[key]
            byFp[key] = if (existing == null) n else pickBetter(existing, n)

            val set = srcMap.getOrPut(key) { LinkedHashSet() }
            if (n.origin.isNotEmpty()) set.add(n.origin)
        }

        var unique = byFp.map { (key, n) ->
            val srcs = srcMap[key].orEmpty().toList()
            n.copy(sources = srcs, sourceCount = srcs.size)
        }

        if (sort) unique = unique.sortedByDescending { it.sourceCount }

        return DedupeResult(
            input = nodes.size,
            l0Dropped = dropped,
            unique = unique,
            duplicates = kept.size - unique.size,
            multiSource = unique.count { it.sourceCount > 1 },
        )
    }

    /* ---------- 弱分组：同 server:port ---------- */

    fun groupByServer(nodes: List<RadarNode>): Map<String, List<RadarNode>> =
        nodes.groupBy { "${it.server}:${it.port}" }

    /* ---------- 汇总 ---------- */

    fun summarize(r: DedupeResult): Summary {
        val byProtocol = LinkedHashMap<String, Int>()
        val byServer = LinkedHashMap<String, Int>()
        val bySource = LinkedHashMap<String, Int>()

        for (n in r.unique) {
            byProtocol[n.protocol] = (byProtocol[n.protocol] ?: 0) + 1

            val sp = "${n.server}:${n.port}"
            byServer[sp] = (byServer[sp] ?: 0) + 1

            for (s in n.sources) bySource[s] = (bySource[s] ?: 0) + 1
        }

        return Summary(
            input = r.input,
            l0Dropped = r.l0Dropped.size,
            unique = r.unique.size,
            duplicates = r.duplicates,
            multiSource = r.multiSource,
            serverCount = byServer.size,
            sharedServers = byServer.count { it.value > 1 },
            byProtocol = byProtocol,
            bySource = bySource,
        )
    }
}
