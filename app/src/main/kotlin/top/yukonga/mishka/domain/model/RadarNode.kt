package top.yukonga.mishka.domain.model

import kotlinx.serialization.Serializable

/**
 * 雷达从订阅容器里解析出来的「通用节点」。
 *
 * 与 [ProxyNode] 的分工：[ProxyNode] 是 mihomo 运行时的节点视图（延迟、历史、选中态），
 * 只能描述已经在跑的节点；这里描述的是「怎么连」——不管原始包装是分享链、Clash YAML
 * 还是 base64，解析后都收敛成这一个形状，与客户端格式无关。导出时才由渲染器分流成
 * v2rayN / Clash / sing-box。
 *
 * [origin] / [sources] / [sourceCount] 是解析与去重的产物，不参与指纹计算。
 */
@Serializable
data class RadarNode(
    val protocol: String = "",
    val server: String = "",
    val port: Int = 0,
    val uuid: String = "",
    val password: String = "",
    /** SS 加密方式（Clash 侧叫 cipher） */
    val method: String = "",
    val alterId: Int = 0,
    /** VLESS flow */
    val flow: String = "",
    /** SS 的 plugin 原始查询串，原样透传 */
    val plugin: String = "",
    val network: String = "tcp",
    val tls: RadarTls? = null,
    val ws: RadarWs? = null,
    val grpc: RadarGrpc? = null,
    /** 展示名。同一个节点在不同源里名字不同，所以不参与指纹 */
    val name: String = "",
    /** 原始分享链。导出目标与来源同族时直接回写，避免二次序列化丢字段 */
    val raw: String = "",
    /** 来自哪个源（源 id 或文件名） */
    val origin: String = "",
    /** 去重后命中过的源列表 */
    val sources: List<String> = emptyList(),
    /** 去重后 == sources.size，给排序和 UI 直接读，省一次 size */
    val sourceCount: Int = 0,
)

@Serializable
data class RadarTls(
    val sni: String = "",
    /** 逗号分隔，保持与分享链查询串一致，避免往返时被重新拼接 */
    val alpn: String = "",
    val fp: String = "",
    val insecure: Boolean = false,
    /** REALITY public key */
    val pbk: String = "",
    /** REALITY short id */
    val sid: String = "",
    val reality: Boolean = false,
)

@Serializable
data class RadarWs(
    val path: String = "/",
    val host: String = "",
)

@Serializable
data class RadarGrpc(
    val serviceName: String = "",
)
