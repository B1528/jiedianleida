package top.yukonga.mishka.ui.screen.radar

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.viewmodel.RadarPhase
import top.yukonga.mishka.viewmodel.RadarStage
import top.yukonga.mishka.viewmodel.RadarTarget
import top.yukonga.mishka.viewmodel.RadarUiState
import top.yukonga.mishka.viewmodel.RadarViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

private val CardRadius = 16.dp
private val ItemGap = 12.dp

/**
 * 雷达页面。作为 Pager Tab 由 MainPage 的 Scaffold 持有底栏，所以：
 * · 顶部接 [bottomPadding] 之外的 [innerPadding]，底部用 [bottomPadding] 把固定操作条顶到导航栏之上
 * · 固定操作条的文案由 phase + 选中目标共同决定，具体规则见 [radarActionLabel]
 */
@Composable
fun RadarScreen(
    viewModel: RadarViewModel,
    bottomPadding: Dp = 0.dp,
    onAddSource: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()

    // 暂停提示只弹一次：关掉之后靠底部按钮「继续执行」，否则每次重组都会重新弹
    var pauseAcknowledged by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.phase) {
        if (uiState.phase != RadarPhase.Paused) pauseAcknowledged = false
    }

    // 导出目录：第一次点导出时弹 SAF 目录选择器，选完记住；之后直接落那里，随时可改。
    // 用 rememberLauncherForActivityResult 就地注册，不走 FilePicker 那套从 Activity 逐层透传。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf(false) }

    val runExport: () -> Unit = {
        scope.launch {
            // 失败（授权失效 / 磁盘满）时保留选中，用户可以直接再点一次
            if (viewModel.exportSelected()) viewModel.clearSelection()
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            // 用户取消：只清掉待办，不动选中态
            pendingExport = false
        } else {
            // 持久授权必须与选择器在同一个回调里拿，否则重启后这个 tree uri 就失效了
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setExportDir(uri.toString())
            if (pendingExport) {
                pendingExport = false
                runExport()
            }
        }
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = "",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = onAddSource) {
                            Icon(
                                imageVector = MiuixIcons.Add,
                                contentDescription = stringResource(R.string.radar_add_source),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                WideContentBox { sidePadding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            start = sidePadding,
                            end = sidePadding,
                            bottom = 16.dp,
                        ),
                    ) {
                        item(key = "radar_header") { RadarHeaderCard(uiState) }
                        item(key = "radar_stats") { RadarStatsRow(uiState) }
                        item(key = "radar_targets") {
                            RadarTargetCard(
                                uiState = uiState,
                                onPick = viewModel::pickTarget,
                            )
                        }
                    }
                }
            }
            // 导出目录行只在扫描完成后露出，未扫描时不占位置
            if (uiState.phase == RadarPhase.Done) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ItemGap)
                        .padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = uiState.exportDirLabel
                            ?.let { stringResource(R.string.radar_export_dir_set, it) }
                            ?: stringResource(R.string.radar_export_dir_none),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.radar_export_change),
                        onClick = { treeLauncher.launch(null) },
                    )
                }
            }
            RadarActionButton(
                uiState = uiState,
                onScan = viewModel::startScan,
                onContinue = viewModel::continueScan,
                onReset = viewModel::reset,
                onExport = {
                    if (viewModel.hasExportDir()) {
                        runExport()
                    } else {
                        // 还没选过目录：先弹选择器，选完在回调里接着导出
                        pendingExport = true
                        treeLauncher.launch(null)
                    }
                },
            )
            Spacer(Modifier.height(bottomPadding))
        }
    }

    // 抓取完、解析前的停靠点：拨测要被测节点自己出网，而 Android 同时只允许一个 VPN 生效，
    // 用户得手动关掉别的 VPN。只弹一次，关掉后靠底部按钮「继续执行」。
    if (uiState.phase == RadarPhase.Paused && !pauseAcknowledged) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.radar_pause_title),
            summary = stringResource(R.string.radar_pause_message, uiState.pausedSources),
            onDismissRequest = { pauseAcknowledged = true },
        ) {
            Column(Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.radar_pause_confirm),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { pauseAcknowledged = true },
                )
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.reset() },
                )
            }
        }
    }
}

/** 顶部大卡：未扫描 / 抓取暂停 / 扫描中（流水线）/ 已完成 四种形态。 */
@Composable
private fun RadarHeaderCard(uiState: RadarUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ItemGap)
            .padding(bottom = ItemGap)
            .squircleBackground(MiuixTheme.colorScheme.surfaceContainer, CardRadius)
            .padding(vertical = 22.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (uiState.phase) {
            RadarPhase.Idle -> {
                StateIcon(ok = false)
                Text(
                    text = stringResource(R.string.radar_state_idle_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.radar_state_idle_hint),
                    fontSize = 12.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }

            RadarPhase.Scanning -> RadarStageList(doneCount = uiState.stageDone)

            RadarPhase.Paused -> {
                StateIcon(ok = false)
                Text(
                    text = stringResource(R.string.radar_pause_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.radar_pause_message, uiState.pausedSources),
                    fontSize = 12.5.sp,
                    color = StatusColors.warning,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }

            RadarPhase.Done -> {
                // 拨测没跑起来时不能给绿勾：这一轮其实只做完了去重，目标行的 0 是「没测」
                // 而不是「都不通」，勾上去等于把没结论当成好结论
                StateIcon(ok = !uiState.testUnavailable)
                Text(
                    text = stringResource(R.string.radar_state_done_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (uiState.testUnavailable) {
                        stringResource(R.string.radar_test_unavailable)
                    } else {
                        stringResource(R.string.radar_state_done_hint)
                    },
                    fontSize = 12.5.sp,
                    color = if (uiState.testUnavailable) {
                        StatusColors.warning
                    } else {
                        MiuixTheme.colorScheme.onSurfaceContainerVariant
                    },
                    modifier = Modifier.padding(top = 7.dp),
                )
                if (uiState.failedSources > 0) {
                    Text(
                        text = stringResource(R.string.radar_sources_failed, uiState.failedSources),
                        fontSize = 12.5.sp,
                        color = StatusColors.warning,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
                if (uiState.parseFailed > 0) {
                    Text(
                        text = stringResource(R.string.radar_parse_failed, uiState.parseFailed),
                        fontSize = 12.5.sp,
                        color = StatusColors.warning,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StateIcon(ok: Boolean) {
    val color = if (ok) StatusColors.healthy else MiuixTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(52.dp)
            .squircleBackground(color.copy(alpha = 0.12f), 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (ok) {
            Icon(
                imageVector = MiuixIcons.Ok,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(26.dp),
            )
        } else {
            // 未扫描：一个简化的雷达刻度，无需额外图片资源
            Canvas(Modifier.size(26.dp)) {
                val r = size.minDimension / 2f
                drawCircle(color = color, radius = r, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color = color, radius = r * 0.42f, style = Stroke(width = 2.dp.toPx()))
                drawLine(
                    color = color,
                    start = center,
                    end = center.copy(x = center.x + r * 0.72f, y = center.y - r * 0.42f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** 流水线四步：已完成打勾，当前步转圈。 */
@Composable
private fun RadarStageList(doneCount: Int) {
    val stages = RadarStage.entries
    Column(Modifier.fillMaxWidth()) {
        stages.forEachIndexed { index, stage ->
            val done = index < doneCount
            val active = index == doneCount
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageMark(done = done, active = active)
                Text(
                    text = stringResource(stage.labelRes),
                    fontSize = 14.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        active -> MiuixTheme.colorScheme.primary
                        done -> MiuixTheme.colorScheme.onSurfaceContainerVariant
                        else -> MiuixTheme.colorScheme.onSurfaceContainer
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                if (done) {
                    Text(
                        text = stringResource(R.string.radar_stage_done),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusColors.healthy,
                    )
                } else if (active) {
                    Text(
                        text = stringResource(R.string.radar_stage_running),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StageMark(done: Boolean, active: Boolean) {
    val healthy = StatusColors.healthy
    Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
        when {
            done -> Box(
                modifier = Modifier
                    .size(19.dp)
                    .squircleBackground(healthy, 9.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }

            active -> StepSpinner(color = MiuixTheme.colorScheme.primary)

            else -> Box(
                modifier = Modifier
                    .size(19.dp)
                    .squircleBackground(MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.15f), 9.5.dp),
            )
        }
    }
}

/** 「有个小圈在转」——用 Canvas 画旋转弧，不依赖 material 的进度组件。 */
@Composable
private fun StepSpinner(color: Color) {
    val transition = rememberInfiniteTransition(label = "radarStep")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800, easing = LinearEasing)),
        label = "radarStepAngle",
    )
    Canvas(Modifier.size(17.dp)) {
        val stroke = 2.dp.toPx()
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** 三个统计块。配色一律从 token 派生，不写死色值。 */
@Composable
private fun RadarStatsRow(uiState: RadarUiState) {
    val idle = uiState.phase == RadarPhase.Idle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ItemGap)
            .padding(bottom = ItemGap),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatCard(
            value = if (idle) null else uiState.fetched,
            label = stringResource(R.string.radar_stat_fetched),
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            value = if (idle) null else uiState.deduped,
            label = stringResource(R.string.radar_stat_deduped),
            color = StatusColors.warning,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            value = if (idle) null else uiState.usable,
            label = stringResource(R.string.radar_stat_usable),
            color = StatusColors.healthy,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(value: Int?, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .squircleBackground(color.copy(alpha = 0.10f), CardRadius)
            .padding(vertical = 15.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value?.toString() ?: "—",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = color.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

/** 各站点通过情况。扫描完成前不可点，选中为单选、再点取消。 */
@Composable
private fun RadarTargetCard(
    uiState: RadarUiState,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ItemGap)
            .padding(bottom = ItemGap)
            .squircleBackground(MiuixTheme.colorScheme.surfaceContainer, CardRadius),
    ) {
        Text(
            text = stringResource(R.string.radar_targets_caption),
            fontSize = 12.5.sp,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
        )
        uiState.targets.forEach { target ->
            val selected = uiState.selectedTarget == target.key
            RadarTargetRow(
                target = target,
                selected = selected,
                value = when (uiState.phase) {
                    RadarPhase.Idle -> null
                    RadarPhase.Scanning, RadarPhase.Paused -> "…"
                    RadarPhase.Done -> target.passed.toString()
                },
                clickable = uiState.canPickTarget,
                onClick = { onPick(target.key) },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RadarTargetRow(
    target: RadarTarget,
    selected: Boolean,
    value: String?,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    // 选中态：整行铺满 --sel 色（这里用 healthy 绿），文字转白
    val container = if (selected) StatusColors.healthy else Color.Transparent
    val content = if (selected) Color.White else MiuixTheme.colorScheme.onSurfaceContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadarServiceLogo(key = target.key, selected = selected)
        Text(
            text = target.name,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        Box(
            modifier = Modifier
                .squircleBackground(
                    if (selected) Color.White.copy(alpha = 0.24f) else container,
                    9.dp,
                )
                .padding(horizontal = 11.dp, vertical = 4.dp),
        ) {
            Text(
                text = value ?: "—",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
    }
}

/**
 * 底部固定操作条。文案由 phase + 选中目标共同决定：
 * 未扫描 → 开始扫描；抓取暂停 → 继续执行；扫描中 → 扫描中…（禁用）；
 * 已完成 → 重置；已完成且选中 → 导出 xxx。
 */
@Composable
private fun RadarActionButton(
    uiState: RadarUiState,
    onScan: () -> Unit,
    onContinue: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
) {
    val selected = uiState.targets.firstOrNull { it.key == uiState.selectedTarget }
    val scanning = uiState.phase == RadarPhase.Scanning
    val paused = uiState.phase == RadarPhase.Paused
    val label = when {
        scanning -> stringResource(R.string.radar_action_scanning)
        paused -> stringResource(R.string.radar_action_continue)
        selected != null -> stringResource(R.string.radar_action_export, selected.name)
        uiState.phase == RadarPhase.Done -> stringResource(R.string.radar_action_reset)
        else -> stringResource(R.string.radar_action_scan)
    }
    TextButton(
        text = label,
        onClick = {
            when {
                scanning -> Unit
                paused -> onContinue()
                selected != null -> onExport()
                uiState.phase == RadarPhase.Done -> onReset()
                else -> onScan()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ItemGap)
            .padding(bottom = 10.dp),
        enabled = !scanning && (uiState.phase != RadarPhase.Idle || uiState.hasEnabledSource),
        colors = ButtonDefaults.textButtonColorsPrimary(),
    )
}

/** 阶段枚举 → 文案资源。 */
private val RadarStage.labelRes: Int
    get() = when (this) {
        RadarStage.Fetch -> R.string.radar_stage_fetch
        RadarStage.Parse -> R.string.radar_stage_parse
        RadarStage.Dedupe -> R.string.radar_stage_dedupe
        RadarStage.Test -> R.string.radar_stage_test
    }
