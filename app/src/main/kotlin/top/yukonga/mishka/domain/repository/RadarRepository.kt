package top.yukonga.mishka.domain.repository

import top.yukonga.mishka.domain.model.RadarExportTarget
import top.yukonga.mishka.domain.model.RadarFetchResult
import top.yukonga.mishka.domain.model.RadarNode
import top.yukonga.mishka.domain.model.RadarScanResult
import top.yukonga.mishka.domain.model.RadarSourceInput
import top.yukonga.mishka.domain.model.RadarTestResult

/**
 * 雷达：抓源 → 抽取 → 解析 → 去重 → 实测 → 按目标格式渲染。
 *
 * 抓取与解析**拆成两步**是刻意的：抓取要一个能翻墙的出口（源多半在 GitHub raw），
 * 拨测要一个干净出口（被别的 VPN 劫持时测出来的不是节点本身的可达性）。这两段的网络
 * 需求互相矛盾，中间必须留给用户切换网络的机会，所以正文要先落成 [RadarFetchResult]。
 *
 * **实现不得向调用方抛异常**：单个源抓取失败要落进 [RadarFetchResult.failures] 继续跑完
 * 其余源，一个挂掉的源不该让整轮扫描白费。取消（CancellationException）照常向上传播。
 */
/** 一个拨测目标：探针地址 + 该站点的单次拨测超时（毫秒）。 */
data class RadarProbe(val url: String, val timeoutMs: Int)

interface RadarRepository {

    /** 只下载：把每个源的正文取回来，不做任何解析。 */
    suspend fun fetch(sources: List<RadarSourceInput>): RadarFetchResult

    /** 纯本地：抽取 + 解析 + L0 + 去重。不碰网络，可以在任何网络状态下调用。 */
    fun parse(fetched: RadarFetchResult): RadarScanResult

    /**
     * 用 mihomo 实测这批节点能否真的访问 [serviceUrls] 中的每一个（真实协议握手 + HTTP 请求）。
     *
     * **内核只拉一次测完所有目标**：冷启动要 fork+exec + 加载 56MB .so + 解析整份 provider，
     * 每个目标重启一次是纯浪费；provider 校验又是整份原子的，自愈剔除也没必要重跑 N 遍。
     * 返回列表与 [serviceUrls] 一一对应；[onProgress] 回调 (已完成目标数, 目标总数)。
     *
     * 实现自己负责把内核拉起来再关掉：日常代理没跑时不能直接返回空表，否则「测速」就
     * 退化成了「必须先开代理」——而代理能不能开起来恰恰取决于这批节点通不通，是个死锁。
     * 拉起的是**纯内核**（`tun.enable=false`），不占 VPN 位，能和别的 VPN 共存。
     */
    suspend fun test(
        nodes: List<RadarNode>,
        services: List<RadarProbe>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<List<RadarTestResult>>

    /** 纯函数：把节点渲染成目标格式的文本 */
    fun render(nodes: List<RadarNode>, target: RadarExportTarget): String

    /**
     * 把渲染好的订阅文本写进 SAF 目录下的一个新文件。
     *
     * 只收 tree uri 的字符串形式 —— 接口层不引 android.net.Uri。返回是否写入成功：
     * 授权失效、磁盘满、provider 拒绝都返回 false，不抛异常（与 [fetch] 同一约定）。
     */
    suspend fun writeExport(treeUri: String, fileName: String, content: String): Boolean

    /**
     * 读回诊断日志原文，供界面直接展示。
     *
     * 雷达测速跑的是**独立内核**（独立端口 + 独立 secret），它的日志不进主日志页，
     * 失败原因只能靠这条链路自己捞回来。实现不得抛异常，读不到就返回空串。
     */
    suspend fun readDiag(): String
}
