package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.yukonga.mishka.domain.model.RadarExportTarget
import top.yukonga.mishka.domain.model.RadarNode
import top.yukonga.mishka.domain.model.RadarSourceInput
import top.yukonga.mishka.domain.repository.RadarRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys

/**
 * 雷达：抓取 GitHub 等来源发布的节点，解析成通用节点、去重，再用内核逐个拨测，
 * 最后按「能连上哪个服务」分组导出为 v2rayN / Clash 订阅。
 *
 * 源列表落在 [PlatformStorage]（JSON 字符串），只要用户不删就一直在。
 *
 * 测速必须经过内核：L1 的 TCP 连通性对 vless/trojan 这类协议给不出结论（端口开着但
 * 握手被拒是常态），只有 mihomo 真拨一次才算数。所以 [RadarRepository.test] 会把节点
 * 灌进内核的 file provider 再逐个 healthcheck，**这一步要求代理正在运行**；没跑时内核
 * 没起来，任何数字都是编的，于是置 [RadarUiState.testUnavailable] 让页面说明原因。
 */
enum class RadarPhase { Idle, Scanning, Done }

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
 * 一个检测目标（GitHub / YouTube / …）能连上的节点数。
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
    /**
     * 拨测整个没跑起来（代理未运行 / provider 写不进去），所有目标都是 0 而不是真的都连不上。
     * 必须与「测了但全挂」区分开，否则用户会以为这批节点全废了。
     */
    val testUnavailable: Boolean = false,
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
 * 一个拨测目标。名字全是专有名词，四种语言下写法一致，所以不做资源化。
 *
 * 探针优先挑各家的 204 / 小体积端点：`generate_204` 只回状态行不回 body，拨测耗时里
 * 几乎全是握手与 RTT，不会被下载时间污染。
 */
private data class RadarService(val key: String, val name: String, val url: String)

private val RADAR_SERVICES = listOf(
    RadarService("google", "Google", "http://www.google.com/generate_204"),
    RadarService("youtube", "YouTube", "https://www.youtube.com/generate_204"),
    RadarService("github", "GitHub", "https://github.com/robots.txt"),
    RadarService("telegram", "Telegram", "https://telegram.org/"),
)

class RadarViewModel(
    private val repository: RadarRepository,
    private val storage: PlatformStorage,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    /** 当前扫描任务。重扫或离开时 cancel 掉，避免两轮扫描同时往 UI 写 */
    private var scanJob: Job? = null

    /** 最近一次扫描出来的节点本体，导出时用。不进 UiState —— 上千条塞进去会让每次状态变更都全量 diff */
    private var scannedNodes: List<RadarNode> = emptyList()

    /**
     * 每个目标通过的节点下标。也不进 UiState（同上，几千个 Int 每帧 diff 不值），
     * 但导出必须靠它把「选中 Google」翻译成具体的节点子集。
     */
    private var passSets: Map<String, Set<Int>> = emptyMap()

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
        _uiState.value = _uiState.value.copy(sources = loaded)
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

    // === 扫描 ===

    /** 点「开始扫描」走这里：真抓取 → 抽取 → 解析 → 去重 → 内核拨测。 */
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
                    testUnavailable = false,
                )
            }
            scannedNodes = emptyList()
            passSets = emptyMap()

            val inputs = _uiState.value.sources
                .filter { it.enabled }
                .map { RadarSourceInput(it.id, it.name, it.url) }

            val result = try {
                repository.scan(
                    sources = inputs,
                    // 抓完源正文即 Fetch 完成；抽取与去重是同一次遍历，只在结束后报一次
                    onFetched = { n -> update { it.copy(stageDone = maxOf(it.stageDone, 1), fetched = n) } },
                    onDeduped = { n ->
                        update {
                            it.copy(
                                stageDone = maxOf(it.stageDone, RadarStage.Test.ordinal),
                                deduped = n,
                            )
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 失败退回未扫描，让按钮重新可点；不把异常冒到主线程打崩进程
                update { it.copy(phase = RadarPhase.Idle, stageDone = 0) }
                return@launch
            }

            scannedNodes = result.nodes

            if (result.nodes.isEmpty()) {
                update {
                    it.copy(
                        phase = RadarPhase.Done,
                        stageDone = RadarStage.entries.size,
                        fetched = result.fetched,
                        deduped = result.deduped,
                        failedSources = result.failures.size,
                    )
                }
                return@launch
            }

            // === 测速：每个目标一次全量拨测 ===
            //
            // 一次扫描要拨 目标数 × 节点数 次真实握手（4 × 上千 ≈ 数千次），所以这里串行跑目标、
            // 每个目标内部由仓库并发。目标之间不并发是刻意的：它们共用同一个 file provider，
            // 并发写 provider 文件会互相覆盖，healthcheck 拨到的是另一批节点。
            val sets = LinkedHashMap<String, Set<Int>>(RADAR_SERVICES.size)
            var kernelAnswered = false
            for (service in RADAR_SERVICES) {
                val results = try {
                    repository.test(result.nodes, service.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 单个目标拨测整体失败不拖垮其余目标
                    emptyList()
                }
                if (results.isNotEmpty()) kernelAnswered = true
                sets[service.key] = results
                    .asSequence()
                    .filter { it.delayMs > 0 }
                    .map { it.index }
                    .toSet()
            }
            passSets = sets

            // 「可用」= 至少能连上一个目标。要求全通会把大部分能用的节点算成不可用，
            // 而这批订阅里 vless 居多，各家的可达性本来就参差。
            val usable = result.nodes.indices.count { i -> sets.values.any { i in it } }

            update {
                it.copy(
                    phase = RadarPhase.Done,
                    stageDone = RadarStage.entries.size,
                    fetched = result.fetched,
                    deduped = result.deduped,
                    usable = usable,
                    failedSources = result.failures.size,
                    testUnavailable = !kernelAnswered,
                    targets = RADAR_SERVICES
                        .map { RadarTarget(it.key, it.name, sets[it.key]?.size ?: 0) }
                        .toPersistentList(),
                )
            }
        }
    }

    /** 重置：清空结果回到未扫描。 */
    fun reset() {
        scanJob?.cancel()
        scannedNodes = emptyList()
        passSets = emptyMap()
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
                testUnavailable = false,
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

    /**
     * 渲染选中目标对应的节点。没有选中、该目标一个都没通、或还没扫出节点时返回 null。
     *
     * 只导出通过的节点是这个功能的意义所在：把 4000 条原始节点丢给用户等于没筛。
     */
    fun exportSelected(format: RadarExportTarget): String? {
        val target = takeSelectedTarget() ?: return null
        val indexes = passSets[target.key] ?: return null
        val picked = scannedNodes.filterIndexed { i, _ -> i in indexes }
        if (picked.isEmpty()) return null
        return repository.render(picked, format)
    }
}
