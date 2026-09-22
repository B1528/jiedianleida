package top.yukonga.mishka.domain.repository

import top.yukonga.mishka.domain.model.RadarExportTarget
import top.yukonga.mishka.domain.model.RadarNode
import top.yukonga.mishka.domain.model.RadarScanResult
import top.yukonga.mishka.domain.model.RadarSourceInput
import top.yukonga.mishka.domain.model.RadarTestResult

/**
 * 雷达：抓源 → 抽取 → 解析 → 去重 → （可选）实测 → 按目标格式渲染。
 *
 * **实现不得向调用方抛异常**：单个源抓取失败要落进 [RadarScanResult.failures] 继续跑完
 * 其余源，一个挂掉的源不该让整轮扫描白费。取消（CancellationException）照常向上传播。
 */
interface RadarRepository {

    /**
     * 抓取 + 抽取 + 解析 + L0 + 去重。
     *
     * [onFetched] / [onDeduped] 在对应阶段完成时回调，用来推动扫描进度；回调在 IO 调度器
     * 上触发，UI 侧自行切回主线程。
     */
    suspend fun scan(
        sources: List<RadarSourceInput>,
        onFetched: (Int) -> Unit = {},
        onDeduped: (Int) -> Unit = {},
    ): RadarScanResult

    /**
     * 用 mihomo 实测这批节点能否真的访问 [serviceUrl]（真实协议握手 + HTTP 请求）。
     *
     * 依赖配置里已声明的 `proxy-providers.radar`（`type: file`）：实现把节点写进那个文件、
     * 热加载后调 provider 的 healthcheck 一次测完整批。provider 未声明时返回空表，不抛异常。
     */
    suspend fun test(nodes: List<RadarNode>, serviceUrl: String): List<RadarTestResult>

    /** 纯函数：把节点渲染成目标格式的文本 */
    fun render(nodes: List<RadarNode>, target: RadarExportTarget): String
}
