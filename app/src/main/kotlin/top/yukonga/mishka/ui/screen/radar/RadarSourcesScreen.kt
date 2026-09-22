package top.yukonga.mishka.ui.screen.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.viewmodel.RadarSource
import top.yukonga.mishka.viewmodel.RadarViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 节点源管理页（从雷达页右上角「＋」进入）。
 *
 * 页面维护一份**草稿**：所有增删改只动草稿，点右上角「保存」才写回 ViewModel 的已保存
 * 快照并返回；直接点返回则丢弃草稿。因此「保存」按钮的可用状态直接读 `isDirty`，
 * 不需要额外的标志位，也就不可能出现「按钮亮着但内容没变」这类脱节。
 */
@Composable
fun RadarSourcesScreen(
    viewModel: RadarViewModel,
    onBack: () -> Unit = {},
) {
    val sourcesState by viewModel.sourcesState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    // 进页面时把已保存的列表拷成草稿；离开时不清，下次进来会重新拷贝
    LaunchedEffect(Unit) { viewModel.beginSourcesEdit() }

    var actionTarget by remember { mutableStateOf<RadarSource?>(null) }
    var editTarget by remember { mutableStateOf<RadarSource?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    val nameState = remember { TextFieldState() }
    val urlState = remember { TextFieldState() }

    fun openEditor(source: RadarSource?) {
        editTarget = source
        nameState.edit { replace(0, length, source?.name.orEmpty()) }
        urlState.edit { replace(0, length, source?.url.orEmpty()) }
        showEditor = true
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.radar_sources),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            text = stringResource(R.string.common_save),
                            onClick = {
                                viewModel.commitSources()
                                onBack()
                            },
                            // 没改动就是灰的；改了才亮起来并可点
                            enabled = sourcesState.isDirty,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
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
                ),
            ) {
                groupedCardItems(
                    keyPrefix = "radar_sources",
                    outerBottomPadding = 12.dp,
                    items = sourcesState.draft.map { source ->
                        CardItem("src:${source.id}") {
                            ArrowPreference(
                                title = source.name,
                                summary = source.url,
                                onClick = { actionTarget = source },
                            )
                        }
                    },
                )

                item(key = "radar_sources_add") {
                    TextButton(
                        text = stringResource(R.string.radar_add_source),
                        onClick = { openEditor(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }

                item(key = "radar_sources_tail") {
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }

    // 点某一行 → 修改 / 删除
    val target = actionTarget
    WindowDialog(
        show = target != null,
        title = target?.name.orEmpty(),
        summary = target?.url.orEmpty(),
        onDismissRequest = { actionTarget = null },
    ) {
        Column(Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.common_edit),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val editing = target
                    actionTarget = null
                    openEditor(editing)
                },
            )
            TextButton(
                text = stringResource(R.string.common_delete),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    target?.let { viewModel.removeSource(it.id) }
                    actionTarget = null
                },
            )
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.fillMaxWidth(),
                onClick = { actionTarget = null },
            )
        }
    }

    // 新增 / 编辑节点源
    if (showEditor) {
        WindowDialog(
            show = true,
            title = stringResource(
                if (editTarget == null) R.string.radar_source_add_title
                else R.string.radar_source_edit_title,
            ),
            onDismissRequest = { showEditor = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                TextField(
                    state = nameState,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.radar_source_name),
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    state = urlState,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.radar_source_url),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.common_cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { showEditor = false },
                    )
                    TextButton(
                        text = stringResource(R.string.common_confirm),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        // 链接不做格式校验：输入是一回事，能不能用是另一回事
                        enabled = nameState.text.isNotBlank() && urlState.text.isNotBlank(),
                        onClick = {
                            val name = nameState.text.toString().trim()
                            val url = urlState.text.toString().trim()
                            val editing = editTarget
                            if (editing == null) {
                                viewModel.addSource(name, url)
                            } else {
                                viewModel.updateSource(editing.id, name, url)
                            }
                            showEditor = false
                        },
                    )
                }
            }
        }
    }
}
