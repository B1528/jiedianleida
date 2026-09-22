package top.yukonga.mishka.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import top.yukonga.mishka.domain.model.RadarFetchResult
import top.yukonga.mishka.domain.model.RadarNode
import top.yukonga.mishka.domain.model.RadarScanResult
import top.yukonga.mishka.domain.model.RadarSourceBody
import top.yukonga.mishka.domain.model.RadarSourceFailure
import top.yukonga.mishka.domain.model.RadarSourceInput
import top.yukonga.mishka.domain.model.RadarTestResult
import top.yukonga.mishka.domain.repository.RadarRepository
import top.yukonga.mishka.service.ConfigGenerator
import top.yukonga.mishka.service.MihomoRunner
import top.yukonga.mishka.service.RuntimeOverrideBuilder
import java.io.File

/**
 * 雷达主流程。
 *
 * 抓取走 [SubscriptionProxyResolver]（与订阅下载同一条路）：Mishka 自身流量永远绕过 TUN，
 * 直连 GitHub raw 之类的境外资源极慢，必须让请求经过本机 mixed-port。
 *
 * **抓取与解析分开**：抓取要一个能翻墙的出口，拨测要一个干净出口，两段需求互相矛盾，
 * 中间必须留给用户切换网络的机会，所以正文先落成 [RadarFetchResult] 再交给 [parse]。
 *
 * 单个源失败不中断整轮 —— 一个挂掉的源不该让另外十九个白抓。取消照常向上传播。
 */
class RadarRepositoryImpl(
    private val context: Context,
    private val proxyResolver: SubscriptionProxyResolver,
    private val providerFile: File,
) : RadarRepository {

    private data class FetchOutcome(
        val body: RadarSourceBody?,
        val failure: RadarSourceFailure?,
    )

    /** 自己拉起来的内核：endpoint + secret 建 API 客户端，runner 负责测完收尸 */
    private class KernelHandle(
        val runner: MihomoRunner,
        val endpoint: String,
        val secret: String,
    )

    // === 抓取 ===

    override suspend fun fetch(sources: List<RadarSourceInput>): RadarFetchResult =
        withContext(Dispatchers.IO) {
            val active = sources.filter { it.url.isNotBlank() }
            if (active.isEmpty()) return@withContext RadarFetchResult()

            val client = buildClient(proxyResolver.resolve(requireUserToggle = true))
            val outcomes: List<FetchOutcome> = try {
                val gate = Semaphore(FETCH_CONCURRENCY)
                coroutineScope {
                    active.map { src ->
                        async { gate.withPermit { fetchOne(client, src) } }
                    }.awaitAll()
                }
            } finally {
                client.close()
            }

            RadarFetchResult(
                bodies = outcomes.mapNotNull { it.body },
                failures = outcomes.mapNotNull { it.failure },
            )
        }

    private suspend fun fetchOne(client: HttpClient, src: RadarSourceInput): FetchOutcome {
        val text = try {
            client.get(src.url).bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return FetchOutcome(null, RadarSourceFailure(src.id, src.name, e.message ?: "fetch failed"))
        }
        return FetchOutcome(RadarSourceBody(src.id, text), null)
    }

    // === 解析 + 去重（纯本地，不碰网络） ===

    override fun parse(fetched: RadarFetchResult): RadarScanResult {
        val all = ArrayList<RadarNode>()
        var parseFailed = 0
        for (body in fetched.bodies) {
            val ext = RadarExtractor.extract(body.body)
            val nodes = ArrayList<RadarNode>()

            // 结构化节点（Clash / JSON）与兜底扫出来的分享链一起进统一模型，重复交给去重处理
            for (obj in ext.nodes) {
                ShareLinkCodec.fromClash(obj)?.let(nodes::add)
            }
            // failed 不能丢：解析不出来的链只有这里能统计到，丢了用户就完全看不见
            val parsed = ShareLinkCodec.parseAll(ext.links)
            parseFailed += parsed.failed.size
            nodes.addAll(parsed.nodes)

            // origin 是 sourceCount 的唯一来源，漏了这一步跨源重复就永远是 0
            all.addAll(nodes.map { it.copy(origin = body.sourceId) })
        }

        val deduped = RadarDedupe.dedupe(all)
        val summary = RadarDedupe.summarize(deduped)

        // 往返自检只抽前 500 条：它是解析器的体检，不是全量校验，抽样的信号足够
        val roundTrip = ShareLinkCodec.roundTrip(deduped.unique.take(500))

        return RadarScanResult(
            fetched = all.size,
            l0Dropped = summary.l0Dropped,
            deduped = summary.unique,
            multiSource = summary.multiSource,
            serverCount = summary.serverCount,
            nodes = deduped.unique,
            failures = fetched.failures,
            roundTripFailed = roundTrip.failed.size,
            parseFailed = parseFailed,
        )
    }

    override fun render(nodes: List<RadarNode>, target: RadarExportTarget): String = when (target) {
        RadarExportTarget.V2rayN -> SubscriptionRenderer.v2rayN(nodes)
        RadarExportTarget.Clash -> SubscriptionRenderer.clash(nodes)
        RadarExportTarget.SingBox -> SubscriptionRenderer.singbox(nodes)
    }

    /**
     * 写进 SAF 目录。用 DocumentsContract 而不是 DocumentFile：后者要额外引
     * androidx.documentfile，而这里只需要「在 tree 下新建一个文档」这两步。
     */
    override suspend fun writeExport(treeUri: String, fileName: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val tree = Uri.parse(treeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
                val target = DocumentsContract.createDocument(
                    context.contentResolver,
                    parent,
                    EXPORT_MIME,
                    fileName,
                ) ?: return@withContext false
                // "wt" 截断写：同名文档被 provider 复用时，不清空会把旧内容留在尾部
                context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: return@withContext false
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
        }

    // === 拨测 ===

    override suspend fun test(nodes: List<RadarNode>, serviceUrl: String): List<RadarTestResult> =
        withContext(Dispatchers.IO) {
            if (nodes.isEmpty()) return@withContext emptyList()

            // 一律起独立内核，哪怕日常代理正在跑。借它的内核会把上千个候选节点写进
            // 用户实时代理列表（provider 文件是持久化的，跑完还留着），而日常配置现在
            // 也不再声明 radar provider，PUT 只会 404。
            // **不能直接返回空表** —— 那等于要求用户先开代理才能测，而代理能不能起来
            // 恰恰取决于这批节点通不通，是个死锁。
            val kernel = startKernel() ?: return@withContext emptyList()
            try {
                testOnKernel(nodes, serviceUrl, kernel.endpoint, kernel.secret)
            } finally {
                kernel.runner.stop()
            }
        }

    /**
     * 把节点写进 provider 文件后逐个 healthcheck。
     *
     * 批量端点 `/{provider}/healthcheck` 不收 url 参数（用的永远是 provider 声明里那个 url），
     * 要按服务分别测（Google / YouTube / …）就只能走单节点端点，所以这里是 N 次调用。
     */
    private suspend fun testOnKernel(
        nodes: List<RadarNode>,
        serviceUrl: String,
        endpoint: String,
        secret: String,
    ): List<RadarTestResult> {
        val api = MihomoApiClient(baseUrl = "http://$endpoint", secret = secret)
        return try {
            // 名字必须与写进文件时一致，否则 healthcheck 按名字找不到节点，全部 404
            val names = SubscriptionRenderer.uniqueNames(nodes)

            providerFile.parentFile?.mkdirs()
            providerFile.writeText(SubscriptionRenderer.clashProvider(nodes), Charsets.UTF_8)
            api.updateProvider(RuntimeOverrideBuilder.RADAR_PROVIDER_NAME)

            // 写文件成功、PUT 回 204 都不代表内核真接受了这份 provider：YAML 里哪怕只有一个
            // 非法字符，mihomo 也会整份丢弃并静默加载 0 个代理，随后所有 healthcheck 404，
            // 最终表现成「全部节点不可用」。这里回读一次，0 个节点直接作废整轮测试。
            val loaded = api.getProviders()
                .providers[RuntimeOverrideBuilder.RADAR_PROVIDER_NAME]?.proxies?.size ?: 0
            if (loaded == 0) return emptyList()

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

    /**
     * 拉起一个只提供 API 的 mihomo 进程（`tun.enable=false`）。
     *
     * **不走 [MishkaTunService]**：那是个 VpnService，而 Android 同时只允许一个 VPN 生效，
     * 用户挂着别的 VPN 时它根本起不来。测速也不需要 TUN —— mihomo 拨节点走自己的 outbound，
     * 跟有没有 TUN 无关，所以这里直接 fork 一个裸内核。
     */
    private suspend fun startKernel(): KernelHandle? {
        val workDir = ConfigGenerator.getWorkDir(context)
        workDir.mkdirs()

        // provider 文件必须先存在：mihomo 在 Parse 阶段就读它，缺失会让整个配置解析失败，
        // 现象是「内核起不来」，而不是「provider 是空的」。
        // 而且必须无条件重置：上一轮跑剩的文件会在这里被读走，里面只要有一个非法字符，
        // 内核就会把整份 provider 判废、静默加载 0 个代理——后续的 PUT 也救不回来。
        providerFile.parentFile?.mkdirs()
        providerFile.writeText(EMPTY_PROVIDER, Charsets.UTF_8)

        File(workDir, KERNEL_CONFIG_NAME).writeText(KERNEL_CONFIG_BODY, Charsets.UTF_8)

        val overrideFile = File(workDir, KERNEL_OVERRIDE_NAME)
        overrideFile.writeText(kernelOverride(providerFile.absolutePath), Charsets.UTF_8)

        val secret = ConfigGenerator.generateSecret()
        val endpoint = "127.0.0.1:$KERNEL_PORT"
        val runner = MihomoRunner(context)

        val started = runner.start(
            subscriptionId = null,
            useRoot = false,
            overrideJsonPath = overrideFile.absolutePath,
            secret = secret,
            externalController = endpoint,
        )
        return if (started) KernelHandle(runner, endpoint, secret) else null
    }

    /**
     * 雷达专用 override。刻意不复用 [RuntimeOverrideBuilder.buildAndWriteForRun]：那个入口会
     * 带上用户订阅的 TUN / 分应用 / mixed-port 设置，而测速只要「有 API + 有 radar provider」。
     */
    private fun kernelOverride(providerPath: String): String = """
        {
          "proxy-providers": {
            "${RuntimeOverrideBuilder.RADAR_PROVIDER_NAME}": {
              "type": "file",
              "path": "${jsonEscape(providerPath)}",
              "health-check": {
                "enable": true,
                "url": "$KERNEL_HEALTHCHECK_URL",
                "interval": $KERNEL_HEALTHCHECK_INTERVAL
              }
            }
          },
          "tun": { "enable": false },
          "mode": "direct",
          "log-level": "warning"
        }
    """.trimIndent()

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

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

        /** 避开 9090：日常代理可能正占着它 */
        private const val KERNEL_PORT = 9099
        private const val KERNEL_CONFIG_NAME = "config.yaml"
        private const val KERNEL_OVERRIDE_NAME = "override.radar.json"
        private const val KERNEL_HEALTHCHECK_URL = "http://www.gstatic.com/generate_204"
        private const val KERNEL_HEALTHCHECK_INTERVAL = 300

        /** 空 provider 的最小合法内容。文件缺失会让 mihomo 在 Parse 阶段直接失败 */
        private const val EMPTY_PROVIDER = "proxies: []"

        /** SAF 新建文档用的 MIME。订阅文本是纯文本，text/plain 兼容性最好 */
        private const val EXPORT_MIME = "text/plain"

        /** 测速内核的最小配置：不引用任何订阅，只求把 API 拉起来 */
        private val KERNEL_CONFIG_BODY = """
mode: direct
log-level: warning
proxies: []
proxy-groups: []
rules:
  - MATCH,DIRECT
""".trimIndent()
    }
}
