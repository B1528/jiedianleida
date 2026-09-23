package top.yukonga.mishka.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.RadarExportTarget
import top.yukonga.mishka.domain.model.RadarFetchResult
import top.yukonga.mishka.domain.model.RadarNode
import top.yukonga.mishka.domain.model.RadarSourceInput
import top.yukonga.mishka.domain.repository.RadarProbe
import top.yukonga.mishka.domain.repository.RadarRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys

/**
 * 雷达：抓取 GitHub 等来源发布的节点，解析成通用节点、去重，再用内核逐个拨测，
 * 最后按「能连上哪个服务」分组导出为 v2rayN / Clash 订阅。
 *
 * 源列表落在 [PlatformStorage]（JSON 字符串），只要用户不删就一直在。
 *
 * **扫描分两段，中间必须停一次**：抓取要一个能翻墙的出口（源多半在 GitHub raw），
 * 内核拨测要一个干净出口（被别的 VPN 劫持时测出来的不是节点本身的可达性）。这两段的
 * 网络需求互相矛盾，而 Android 同时只允许一个 VPN 生效，所以只能让用户手动切换。
 * 断点落在「抓取完、解析前」——解析与去重都是纯文本处理，不碰网络，放在切换之后做
 * 既不影响结果，也省得用户开着 VPN 干等。
 */
enum class RadarPhase { Idle, Scanning, Paused, Done }

/** 流水线的四个阶段，顺序即展示顺序。 */
enum class RadarStage { Fetch, Parse, Dedupe, Test }

@Serializable
@Immutable
data class RadarSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
)

/**
 * 一个检测目标能连上的节点数。
 *
 * [passed] 的语义是「这个节点**真的能访问**该目标」，而不是「TCP 通了」——只看通不通
 * 的话，导入之后仍要逐个试能不能连上 Google，那这个数就没有意义。
 */
@Immutable
data class RadarTarget(
    val key: String,
    val name: String,
    val passed: Int,
)

@Immutable
data class RadarUiState(
    val phase: RadarPhase = RadarPhase.Idle,
    /** 已完成的阶段数；0 表示还没开始，等于 [RadarStage.entries] 数量表示全部完成。 */
    val stageDone: Int = 0,
    val sources: ImmutableList<RadarSource> = persistentListOf(),
    val fetched: Int = 0,
    val deduped: Int = 0,
    val usable: Int = 0,
    val targets: ImmutableList<RadarTarget> = persistentListOf(),
    /** 当前单选中的目标 key；null 表示没有选中。 */
    val selectedTarget: String? = null,
    /** 抓取失败的源数量。非 0 时扫描仍然算完成，只是结果里少了那几个源 */
    val failedSources: Int = 0,
    /** 暂停态提示用：这一轮实际下回来了几个源 */
    val pausedSources: Int = 0,
    /** 分享链解析失败条数。非 0 说明有链被静默丢掉，用户应当能看见 */
    val parseFailed: Int = 0,
    /**
     * 当前导出目录的显示名；null 表示还没选过。真正的 tree uri 留在 ViewModel 里不进状态 ——
     * 屏幕只需要「显示什么」，不需要拿它去拼路径。
     */
    val exportDirLabel: String? = null,
    /**
     * 拨测整个没跑起来（内核拉不起来 / provider 写不进去），所有目标都是 0 而不是真的都连不上。
     * 必须与「测了但全挂」区分开，否则用户会以为这批节点全废了。
     */
    val testUnavailable: Boolean = false,
    /**
     * 拨测链路的诊断原文。雷达内核是独立进程（独立端口 + 独立 secret），它的日志不进主
     * 日志页，测速失败时这里是唯一能说明「卡在哪一环」的东西。空串表示还没跑过或读不到。
     */
    /** 拨测进度：已完成目标数 / 目标总数。8 个目标跑完之前界面靠它显示「在动」 */
    val testDone: Int = 0,
    val testTotal: Int = 0,
    val diagText: String = "",
) {
    /** 只有扫描完成后才允许点选目标行。 */
    val canPickTarget: Boolean get() = phase == RadarPhase.Done

    val hasEnabledSource: Boolean get() = sources.any { it.enabled }
}

/**
 * 节点源编辑页的状态：草稿 + 已保存快照。
 *
 * 页面上的增删改只动 [draft]，点「保存」才写回 [saved]；[isDirty] 由两者比较得出，
 * 所以「保存」按钮的可用状态不需要额外的标志位，也就不可能与内容脱节。
 */
@Immutable
data class RadarSourcesUiState(
    val draft: ImmutableList<RadarSource> = persistentListOf(),
    val saved: ImmutableList<RadarSource> = persistentListOf(),
) {
    val isDirty: Boolean get() = draft != saved
}

/**
 * 一个拨测目标。名字全是专有名词，四种语言下写法一致，所以不做资源化
 * （唯一例外是「全部通过」那一行，它走 `R.string.radar_target_all`）。
 *
 * 探针优先挑各家的 204 / 小体积端点：`generate_204` 只回状态行不回 body，拨测耗时里
 * 几乎全是握手与 RTT，不会被下载时间污染。
 */
private data class RadarService(
    val key: String,
    val name: String,
    val url: String,
    val timeoutMs: Int = 3000,
)

private val RADAR_SERVICES = listOf(
    RadarService("google", "Google", "http://www.google.com/generate_204", 2000),
    RadarService("youtube", "YouTube", "https://www.youtube.com/generate_204", 2500),
    RadarService("github", "GitHub", "https://github.com/robots.txt", 2500),
    RadarService("chatgpt", "ChatGPT", "https://chatgpt.com/robots.txt", 4000),
    RadarService("x", "X", "https://x.com/robots.txt", 3000),
    RadarService("cloudflare", "Cloudflare", "https://www.cloudflare.com/cdn-cgi/trace", 2000),
    RadarService("grok", "Grok", "https://grok.com/robots.txt", 4000),
    RadarService("telegram", "Telegram", "https://telegram.org/", 3000),
)

/**
 * 「全部通过」那一行的 key。**不进 [RADAR_SERVICES]** —— 它不是要拨测的服务，
 * 而是其余服务通过集合的交集，没有自己的探针地址。
 */
private const val RADAR_TARGET_ALL = "all"

class RadarViewModel(
    private val repository: RadarRepository,
    private val storage: PlatformStorage,
    private val context: Context,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    /** 当前扫描任务。重扫或离开时 cancel 掉，避免两轮扫描同时往 UI 写 */
    private var scanJob: Job? = null

    /**
     * 抓取阶段的产物。正文可能有几 MB，不进 UiState —— 每次都全量 diff 不值。
     * 暂停期间就靠它把两段接起来；进程被杀就没了，重扫一次即可。
     */
    private var fetchResult: RadarFetchResult? = null

    /** 最近一次扫描出来的节点本体，导出时用。同样不进 UiState */
    private var scannedNodes: List<RadarNode> = emptyList()

    /**
     * 每个目标通过的节点下标（含 [RADAR_TARGET_ALL]）。导出必须靠它把「选中 Google」
     * 翻译成具体的节点子集 —— 只导出通过的节点，这个功能才有意义。
     */
    private var passSets: Map<String, Set<Int>> = emptyMap()

    /** 上次选定的导出目录（SAF tree uri）。null 表示还没选过，此时导出前要先弹目录选择器 */
    private var exportDirUri: String? = null

    private fun update(transform: (RadarUiState) -> RadarUiState) {
        _uiState.value = transform(_uiState.value)
    }

    // === 节点源编辑页 ===

    private val _sourcesState = MutableStateFlow(RadarSourcesUiState())
    val sourcesState: StateFlow<RadarSourcesUiState> = _sourcesState.asStateFlow()

    /** 已保存的源列表；与 [RadarUiState.sources] 保持同步，草稿不参与扫描按钮的判定。 */
    private var savedSources: ImmutableList<RadarSource> = persistentListOf()
    private var sourceSeq = 0

    init {
        val loaded = loadSources()
        savedSources = loaded
        // 新 id 从已有最大序号往后接，删掉中间几条也不会撞号
        sourceSeq = loaded.mapNotNull { it.id.removePrefix("s").toIntOrNull() }.maxOrNull()?.plus(1) ?: 0
        _sourcesState.value = RadarSourcesUiState(draft = loaded, saved = loaded)

        // 导出目录跨会话记住；存的是空串就当作没选过
        exportDirUri = storage.getString(StorageKeys.RADAR_EXPORT_TREE, "").ifEmpty { null }
        _uiState.value = _uiState.value.copy(
            sources = loaded,
            exportDirLabel = exportDirUri?.let(::dirLabel),
        )
    }

    private fun loadSources(): ImmutableList<RadarSource> {
        val raw = storage.getString(StorageKeys.RADAR_SOURCES, "")
        if (raw.isEmpty()) return persistentListOf()
        // 存坏了就当没有：宁可丢一次列表，也不能让雷达页因为一条脏数据起不来
        return runCatching {
            json.decodeFromString<List<RadarSource>>(raw).toPersistentList()
        }.getOrDefault(persistentListOf())
    }

    private fun persistSources(list: List<RadarSource>) {
        runCatching { storage.putString(StorageKeys.RADAR_SOURCES, json.encodeToString(list)) }
    }

    private fun updateDraft(transform: (ImmutableList<RadarSource>) -> List<RadarSource>) {
        val current = _sourcesState.value
        _sourcesState.value = current.copy(draft = transform(current.draft).toPersistentList())
    }

    /** 进入节点源页：把已保存的列表拷成草稿，之后的改动都只动草稿。 */
    fun beginSourcesEdit() {
        _sourcesState.value = RadarSourcesUiState(draft = savedSources, saved = savedSources)
    }

    fun addSource(name: String, url: String) =
        updateDraft { it + RadarSource("s${sourceSeq++}", name, url) }

    fun updateSource(id: String, name: String, url: String) = updateDraft { list ->
        list.map { if (it.id == id) it.copy(name = name, url = url) else it }
    }

    fun removeSource(id: String) = updateDraft { list -> list.filterNot { it.id == id } }

    /** 保存：草稿写回已保存、落盘，并同步给主页面（扫描按钮的可用状态看它）。 */
    fun commitSources() {
        val draft = _sourcesState.value.draft
        savedSources = draft
        _sourcesState.value = RadarSourcesUiState(draft = draft, saved = draft)
        _uiState.value = _uiState.value.copy(sources = draft)
        persistSources(draft)
    }

    /** 主页面上的启用开关。它不动草稿，直接改已保存列表并落盘。 */
    fun setSourceEnabled(id: String, enabled: Boolean) {
        val updated = _uiState.value.sources
            .map { if (it.id == id) it.copy(enabled = enabled) else it }
            .toPersistentList()

        savedSources = updated
        _uiState.value = _uiState.value.copy(sources = updated)

        // 编辑页草稿没被改动过才跟着同步，否则会把用户正在编辑的内容冲掉
        val state = _sourcesState.value
        _sourcesState.value = if (state.draft == state.saved) {
            RadarSourcesUiState(draft = updated, saved = updated)
        } else {
            state.copy(saved = updated)
        }

        persistSources(updated)
    }

    /**
     * 点选目标行。**单选**：选中新的会替换旧的；再点已选中的那一行则取消选中。
     *
     * 「取消选中」是「回到重置」的两条路之一（另一条是导出后自动取消），所以这里必须
     * 保留 toggle 语义，不能写成「选中后不可取消」。
     */
    fun pickTarget(key: String) = update { state ->
        if (!state.canPickTarget) state
        else state.copy(selectedTarget = if (state.selectedTarget == key) null else key)
    }

    fun clearSelection() = update { it.copy(selectedTarget = null) }

    // === 扫描：第一段（抓取） ===

    /** 点「开始扫描」走这里：只抓取，抓完停在 [RadarPhase.Paused] 等用户关掉别的 VPN。 */
    fun startScan() {
        if (_uiState.value.phase == RadarPhase.Scanning) return

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            update {
                it.copy(
                    phase = RadarPhase.Scanning,
                    stageDone = 0,
                    fetched = 0,
                    deduped = 0,
                    usable = 0,
                    targets = persistentListOf(),
                    selectedTarget = null,
                    failedSources = 0,
                    pausedSources = 0,
                    parseFailed = 0,
                    testUnavailable = false,
                    testDone = 0,
                    testTotal = 0,
                    diagText = "",
                )
            }
            scannedNodes = emptyList()
            passSets = emptyMap()
            fetchResult = null

            val inputs = _uiState.value.sources
                .filter { it.enabled }
                .map { RadarSourceInput(it.id, it.name, it.url) }

            val result = try {
                repository.fetch(inputs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 失败退回未扫描，让按钮重新可点；不把异常冒到主线程打崩进程
                update { it.copy(phase = RadarPhase.Idle, stageDone = 0) }
                return@launch
            }

            fetchResult = result
            update {
                it.copy(
                    phase = RadarPhase.Paused,
                    stageDone = 1,
                    pausedSources = result.fetchedSources,
                    failedSources = result.failures.size,
                )
            }
        }
    }

    // === 扫描：第二段（解析 → 去重 → 拨测） ===

    /**
     * 点「继续执行」走这里。到这一步用户应当已经关掉其他 VPN —— 内核拨测要被测节点
     * 自己出网，多一层 VPN 会让结果变成「经过那个出口之后能不能连上」。
     */
    fun continueScan() {
        val pending = fetchResult ?: return
        if (_uiState.value.phase != RadarPhase.Paused) return

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            update { it.copy(phase = RadarPhase.Scanning, stageDone = 1) }

            val result = try {
                // parse 是纯 CPU 函数（正则 + 集合运算，上千节点几百 ms 起），
                // 直接在 viewModelScope(Main) 里调会把界面冻住——必须切到 Default。
                withContext(Dispatchers.Default) { repository.parse(pending) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                update { it.copy(phase = RadarPhase.Idle, stageDone = 0) }
                return@launch
            }

            scannedNodes = result.nodes
            update {
                it.copy(
                    stageDone = RadarStage.Test.ordinal,
                    fetched = result.fetched,
                    deduped = result.deduped,
                    failedSources = result.failures.size,
                    parseFailed = result.parseFailed,
                )
            }

            if (result.nodes.isEmpty()) {
                update { it.copy(phase = RadarPhase.Done, stageDone = RadarStage.entries.size) }
                return@launch
            }

            // === 测速：每个目标一次全量拨测 ===
            //
            // 一次扫描要拨 目标数 × 节点数 次真实握手（8 × 上千 ≈ 上万次），所以这里串行跑目标、
            // 每个目标内部由仓库并发。目标之间不并发是刻意的：它们共用同一个 file provider，
            // 并发写 provider 文件会互相覆盖，healthcheck 拨到的是另一批节点。
            val sets = LinkedHashMap<String, Set<Int>>(RADAR_SERVICES.size)
            var kernelAnswered = false
            // 内核只起一次，8 个目标共用它（见 RadarRepository.test），顺带把进度报给界面
            val allResults = try {
                repository.test(result.nodes, RADAR_SERVICES.map { RadarProbe(it.url, it.timeoutMs) }) { done, total ->
                    update { it.copy(testDone = done, testTotal = total) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 整轮拨测失败不拖垮后面的统计
                emptyList()
            }
            RADAR_SERVICES.forEachIndexed { idx, service ->
                val results = allResults.getOrElse(idx) { emptyList() }
                if (results.isNotEmpty()) kernelAnswered = true
                sets[service.key] = results
                    .asSequence()
                    .filter { it.delayMs > 0 }
                    .map { it.index }
                    .toSet()
            }

            // 诊断原文在内核关掉之后取；readDiag 约定不抛异常，读不到就是空串
            val diagText = runCatching { repository.readDiag() }.getOrDefault("")

            // 「全部通过」= 每个服务都拨通的节点。逐个服务求交，别写成
            // `sets.values.all { i in it }` 之外的花样 —— 一个服务都没答上来时交集必须为空，
            // 否则会把「没测」显示成「全通」
            val allPass: Set<Int> = result.nodes.indices
                .filterTo(HashSet()) { i -> sets.values.all { i in it } }
            passSets = sets + (RADAR_TARGET_ALL to allPass)

            // 「可用」= 至少能连上一个目标。要求全通会把大部分能用的节点算成不可用，
            // 而这批订阅里 vless 居多，各家的可达性本来就参差。
            val usable = result.nodes.indices.count { i -> sets.values.any { i in it } }

            val targets = buildList {
                add(RadarTarget(RADAR_TARGET_ALL, context.getString(R.string.radar_target_all), allPass.size))
                RADAR_SERVICES.forEach { add(RadarTarget(it.key, it.name, sets[it.key]?.size ?: 0)) }
            }

            update {
                it.copy(
                    phase = RadarPhase.Done,
                    stageDone = RadarStage.entries.size,
                    usable = usable,
                    testUnavailable = !kernelAnswered,
                    targets = targets.toPersistentList(),
                    diagText = diagText,
                )
            }
        }
    }

    /** 重置：清空结果回到未扫描。暂停态点取消也走这里。 */
    fun reset() {
        scanJob?.cancel()
        scannedNodes = emptyList()
        passSets = emptyMap()
        fetchResult = null
        update { state ->
            state.copy(
                phase = RadarPhase.Idle,
                stageDone = 0,
                fetched = 0,
                deduped = 0,
                usable = 0,
                targets = persistentListOf(),
                selectedTarget = null,
                failedSources = 0,
                pausedSources = 0,
                parseFailed = 0,
                testUnavailable = false,
                diagText = "",
                testDone = 0,
                testTotal = 0,
            )
        }
    }

    /**
     * 导出当前选中的那一组。返回选中的目标，调用方负责真正的导出动作。
     *
     * 导出**不在这里清空选中**——清空是 UI 层在导出完成后显式调 [clearSelection]，
     * 这样导出失败时选中态不会莫名丢失。
     */
    fun takeSelectedTarget(): RadarTarget? = _uiState.value.targets
        .firstOrNull { it.key == _uiState.value.selectedTarget }

// === 导出 ===

    /** 选好目录后记住它：后续导出直接落这里，直到用户再改。 */
    fun setExportDir(treeUri: String) {
        exportDirUri = treeUri
        storage.putString(StorageKeys.RADAR_EXPORT_TREE, treeUri)
        update { it.copy(exportDirLabel = dirLabel(treeUri)) }
    }

    fun hasExportDir(): Boolean = exportDirUri != null

    /**
     * 把选中目标里通过的节点渲染成 v2rayN 订阅，写进已选目录下的一个新文件。
     *
     * 返回 false 的三种情况都不抛异常：没有选中目标、该目标一条都没通、写盘失败。
     * 屏幕只需要知道成没成 —— 授权失效 / 磁盘满对用户没有可操作性。
     *
     * 只导出通过的节点是这个功能的意义所在：把 4000 条原始节点丢给用户等于没筛。
     */
    suspend fun exportSelected(): Boolean {
        val target = takeSelectedTarget() ?: return false
        val dir = exportDirUri ?: return false
        val indexes = passSets[target.key] ?: return false
        val picked = scannedNodes.filterIndexed { i, _ -> i in indexes }
        if (picked.isEmpty()) return false
        val text = repository.render(picked, RadarExportTarget.V2rayN)
        return repository.writeExport(dir, "radar-${target.key}.txt", text)
    }

    /** 从 tree uri 取出便于显示的目录名；解析不出来就原样显示 */
    private fun dirLabel(uri: String): String = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfterLast(':').ifEmpty { uri }
    }.getOrDefault(uri)
}
