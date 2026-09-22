package top.yukonga.mishka.domain.model

import kotlinx.serialization.Serializable

/** 扫描输入：只带抓取需要的最小信息，不依赖 UI 层的模型 */
@Serializable
data class RadarSourceInput(
    val id: String = "",
    val name: String = "",
    val url: String = "",
)

/** 单个源抓取失败。失败不中断整轮扫描，只落进结果里让用户看见 */
@Serializable
data class RadarSourceFailure(
    val id: String = "",
    val name: String = "",
    val reason: String = "",
)

/** 一个源下回来的正文。[sourceId] 决定去重时 sourceCount 归属到哪个源，不能丢 */
@Serializable
data class RadarSourceBody(
    val sourceId: String = "",
    val body: String = "",
)

/**
 * 抓取阶段的产出：各源正文 + 失败列表。
 *
 * 抓取与解析之间要能断开：抓取要一个能翻墙的出口（源多半在 GitHub raw），拨测要一个干净
 * 出口（被别的 VPN 劫持时测出来的不是节点本身的可达性）。这两段的需求互相矛盾，中间必须
 * 留给用户切换网络的机会，所以正文先落在这里，等切好网再交给解析。
 */
@Serializable
data class RadarFetchResult(
    val bodies: List<RadarSourceBody> = emptyList(),
    val failures: List<RadarSourceFailure> = emptyList(),
) {
    /** 实际下回来的源数，暂停态提示用 */
    val fetchedSources: Int get() = bodies.size
}

/** 导出目标格式 */
@Serializable
enum class RadarExportTarget { V2rayN, Clash, SingBox }

/** 单个节点对某个服务的实测结果 */
@Serializable
data class RadarTestResult(
    /**
     * 在传入的节点列表里的下标。
     * 要做「每个节点 × 每个服务」的交集统计，就得有一个跨批次稳定的身份；
     * server:port 会把同机多配置混成一条，name 又不是唯一的。
     */
    val index: Int = 0,
    val server: String = "",
    val port: Int = 0,
    val name: String = "",
    /** 毫秒；-1 表示不通 */
    val delayMs: Int = -1,
)

/**
 * 一次扫描的产出。
 *
 * [fetched] 是去重前的条目总数，[deduped] 是去重后的唯一节点数 —— 两个数字一起看才能
 * 说明「抓了多少、有多少是重复的」。
 */
@Serializable
data class RadarScanResult(
    /** 去重前抽出来的条目总数 */
    val fetched: Int = 0,
    /** L0 静态过滤淘汰的条数 */
    val l0Dropped: Int = 0,
    /** 去重后的唯一节点数 */
    val deduped: Int = 0,
    /** 出现在 2 个以上源里的节点数。越高说明这批节点的可信度越好 */
    val multiSource: Int = 0,
    /** 独立 server:port 数，也是测速的真实工作量 */
    val serverCount: Int = 0,
    /** 去重后的节点本体，已按 sourceCount 降序 */
    val nodes: List<RadarNode> = emptyList(),
    val failures: List<RadarSourceFailure> = emptyList(),
    /** 往返自检失败条数。非 0 说明解析器读错了字段，而不是节点本身有问题 */
    val roundTripFailed: Int = 0,
)
