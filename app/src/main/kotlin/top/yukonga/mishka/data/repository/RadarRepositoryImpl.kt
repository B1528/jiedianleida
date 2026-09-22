package top.yukonga.mishka.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.yukonga.mishka.data.api.MihomoApiClient
import top.yukonga.mishka.data.radar.RadarDedupe
import top.yukonga.mishka.data.radar.RadarExtractor
import top.yukonga.mishka.data.radar.ShareLinkCodec
import top.yukonga.mishka.data.radar.SubscriptionRenderer
import top.yukonga.mishka.domain.model.RadarExportTarget
import top.yukonga.mishka.domain.model.RadarNode
import top.yukonga.mishka.domain.model.RadarScanResult
import top.yukonga.mishka.domain.model.RadarSourceFailure
import top.yukonga.mishka.domain.model.RadarSourceInput
import top.yukonga.mishka.domain.model.RadarTestResult
import top.yukonga.mishka.domain.repository.RadarRepository
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.service.RuntimeOverrideBuilder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 雷达主流程。
 *
 * 抓取走 [SubscriptionProxyResolver]（与订阅下载同一条路）：Mishka 自身流量永远绕过 TUN，
 * 直连 GitHub raw 之类的境外资源极慢，必须让请求经过本机 mixed-port。
 *
 * 单个源失败不中断整轮 —— 一个挂掉的源不该让另外十九个白抓。取消照常向上传播。
 */
class RadarRepositoryImpl(
    private val proxyResolver: SubscriptionProxyResolver,
    private val providerFile: File,
) : RadarRepository {

    private data class SourceOutcome(
        val nodes: List<RadarNode>,
        val failure: RadarSourceFailure?,
    )

    override suspend fun scan(
        sources: List<RadarSourceInput>,
        onFetched: (Int) -> Unit,
        onDeduped: (Int) -> Unit,
    ): RadarScanResult = withContext(Dispatchers.IO) {
        val active = sources.filter { it.url.isNotBlank() }
        if (active.isEmpty()) return@withContext RadarScanResult()

        val client = buildClient(proxyResolver.resolve(requireUserToggle = true))
        // 累计的是「已抽出的节点数」而不是「已完成的源数」——进度条上的 fetched 是前者
        val nodeCount = AtomicInteger(0)

        val outcomes: List<SourceOutcome> = try {
            val gate = Semaphore(FETCH_CONCURRENCY)
            coroutineScope {
                active.map { src ->
                    async {
                        val outcome = gate.withPermit { fetchOne(client, src) }
                        onFetched(nodeCount.addAndGet(outcome.nodes.size))
                        outcome
                    }
                }.awaitAll()
            }
        } finally {
            client.close()
        }

        val all = ArrayList<RadarNode>()
        val failures = ArrayList<RadarSourceFailure>()
        for (o in outcomes) {
            all.addAll(o.nodes)
            o.failure?.let(failures::add)
        }

        val deduped = RadarDedupe.dedupe(all)
        val summary = RadarDedupe.summarize(deduped)
        onDeduped(summary.unique)

        // 往返自检只抽前 500 条：它是解析器的体检，不是全量校验，抽样的信号足够
        val roundTrip = ShareLinkCodec.roundTrip(deduped.unique.take(500))

        RadarScanResult(
            fetched = all.size,
            l0Dropped = summary.l0Dropped,
            deduped = summary.unique,
            multiSource = summary.multiSource,
            serverCount = summary.serverCount,
            nodes = deduped.unique,
            failures = failures,
            roundTripFailed = roundTrip.failed.size,
        )
    }

    private suspend fun fetchOne(client: HttpClient, src: RadarSourceInput): SourceOutcome {
        val text = try {
            client.get(src.url).bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return SourceOutcome(emptyList(), RadarSourceFailure(src.id, src.name, e.message ?: "fetch failed"))
        }

        val ext = RadarExtractor.extract(text)
        val nodes = ArrayList<RadarNode>()

        // 结构化节点（Clash / JSON）与兜底扫出来的分享链一起进统一模型，重复交给去重处理
        for (obj in ext.nodes) {
            ShareLinkCodec.fromClash(obj)?.let(nodes::add)
        }
        nodes.addAll(ShareLinkCodec.parseAll(ext.links).nodes)

        // origin 是 sourceCount 的唯一来源，漏了这一步跨源重复就永远是 0
        return SourceOutcome(nodes.map { it.copy(origin = src.id) }, null)
    }

    override fun render(nodes: List<RadarNode>, target: RadarExportTarget): String = when (target) {
        RadarExportTarget.V2rayN -> SubscriptionRenderer.v2rayN(nodes)
        RadarExportTarget.Clash -> SubscriptionRenderer.clash(nodes)
        RadarExportTarget.SingBox -> SubscriptionRenderer.singbox(nodes)
    }

    /**
     * 实测节点可用性：把 [nodes] 写进配置里已声明的 `proxy-providers.radar`，热加载后逐节点拨测。
     *
     * 走 file provider 而不是 `PUT /configs`：embed mode 禁掉了配置热重载，重启内核又会断掉
     * 用户当前的连接，provider 是唯一不打扰用户的注入通道。
     *
     * 批量端点 `/{provider}/healthcheck` 不收 url 参数（用的永远是 provider 声明里那个 url），
     * 要按服务分别测就只能走单节点端点 `/{provider}/{name}/healthcheck?url=`，所以这里是 N 次调用。
     *
     * 代理没跑、provider 未声明、文件写不进去 —— 一律返回空表让 UI 自己提示；不抛异常，
     * 也不编造「0 个可用」这种看着像有结论的数字。
     */
    override suspend fun test(nodes: List<RadarNode>, serviceUrl: String): List<RadarTestResult> =
        withContext(Dispatchers.IO) {
            if (nodes.isEmpty()) return@withContext emptyList()

            val status = ProxyServiceBridge.state.value
            if (status.state != ProxyState.Running) return@withContext emptyList()

            val api = MihomoApiClient(
                baseUrl = "http://${status.externalController}",
                secret = status.secret,
            )

            try {
                // 名字必须与写进文件时一致，否则 healthcheck 按名字找不到节点，全部 404
                val names = SubscriptionRenderer.uniqueNames(nodes)

                providerFile.parentFile?.mkdirs()
                providerFile.writeText(SubscriptionRenderer.clashProvider(nodes), Charsets.UTF_8)
                api.updateProvider(RuntimeOverrideBuilder.RADAR_PROVIDER_NAME)

                val gate = Semaphore(TEST_CONCURRENCY)
                coroutineScope {
                    nodes.mapIndexed { i, n ->
                        async {
                            gate.withPermit {
                                val delay = try {
                                    api.getProviderProxyDelay(
                                        provider = RuntimeOverrideBuilder.RADAR_PROVIDER_NAME,
                                        name = names[i],
                                        testUrl = serviceUrl,
                                        timeout = TEST_TIMEOUT_MS,
                                    ).delay
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    // 单节点拨不通不算整体失败，标记后继续下一个
                                    NO_DELAY
                                }
                                RadarTestResult(i, n.server, n.port, n.name, delay)
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            } finally {
                api.close()
            }
        }

    private fun buildClient(proxyUrl: String?): HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        followRedirects = true
        if (proxyUrl != null) {
            engine { proxy = ProxyBuilder.http(Url(proxyUrl)) }
        }
    }

    companion object {
        /** 20 个源一次全发会同时攥住几十 MB 正文，限 4 路足够快又压得住内存 */
        private const val FETCH_CONCURRENCY = 4
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L

        /** 拨测是真实协议握手，比抓取贵得多；32 路在真机上够快又不会把内核压垮 */
        private const val TEST_CONCURRENCY = 32
        private const val TEST_TIMEOUT_MS = 5000

        /** 拨不通时的哨兵值，与 [RadarTestResult.delayMs] 的约定一致 */
        private const val NO_DELAY = -1
    }
}
