package com.clashlite.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashlite.ClashViewModel
import com.clashlite.core.ProxyGroup
import com.clashlite.data.ProxiesDisplay

internal val delayGreen = Color(0xFF4CAF50)
internal val delayOrange = Color(0xFFFFA726)
private val delayRed = Color(0xFFEF5350)

private fun delayColor(ms: Int?): Color? = when {
    ms == null -> null
    ms < 0 -> delayRed
    ms < 200 -> delayGreen
    ms < 500 -> delayOrange
    else -> delayRed
}

private fun sortNodes(
    nodes: List<String>,
    sort: String,
    delays: Map<String, Int>,
): List<String> = when (sort) {
    "delay" -> nodes.sortedBy { delays[it]?.takeIf { d -> d > 0 } ?: Int.MAX_VALUE }
    "name" -> nodes.sortedBy { it.lowercase() }
    else -> nodes
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(vm: ClashViewModel) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val delays by vm.delays.collectAsStateWithLifecycle()
    val nodeTypes by vm.nodeTypes.collectAsStateWithLifecycle()
    val running by vm.vpnRunning.collectAsStateWithLifecycle()
    val display by vm.proxiesDisplay.collectAsStateWithLifecycle()

    var selectedGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val glassNav = LocalGlassNav.current

    LaunchedEffect(running) { if (running) vm.refreshGroups() }

    // 默认选中第一个非 GLOBAL 的分组（订阅主分组）
    val group = groups.firstOrNull { it.name == selectedGroupName }
        ?: groups.firstOrNull { it.name != "GLOBAL" }
        ?: groups.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        // ── 顶栏：标题 + 搜索 / 测速 / 设置 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("代理", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, "搜索") }
            IconButton(onClick = { group?.let { vm.testGroupDelayViaApi(it.name) } }) {
                Icon(Icons.Outlined.NetworkCheck, "测速")
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.MoreVert, "显示设置") }
        }

        when {
            !running -> HintCard("VPN 未连接。连接后可查看代理组并选择节点。")
            groups.isEmpty() -> HintCard("暂无代理组数据，点击右上角测速或稍后再试。")
            display.style == "tabs" -> {
                // ── 分组标签页 ──
                GroupTabs(groups, group?.name) { selectedGroupName = it }
                group?.let { g ->
                    val nodes = sortNodes(
                        g.nodes.filter { it.contains(searchQuery, ignoreCase = true) },
                        display.sort, delays,
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(display.columns),
                        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, if (glassNav) 110.dp else 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(nodes, key = { it }) { node ->
                            NodeCard(
                                node = node,
                                type = nodeTypes[node] ?: "",
                                delay = delays[node],
                                selected = node == group.now,
                                density = display.density,
                                compact = display.columns >= 4,
                                onSelect = { vm.selectNode(g.name, node) },
                                onTest = { vm.testGroupDelayViaApi(node) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
            else -> {
                // ── 分组列表 ──
                LazyVerticalGrid(
                    columns = GridCells.Fixed(display.columns),
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, if (glassNav) 110.dp else 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    groups.forEach { g ->
                        item(key = "h-${g.name}", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                Text(g.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    g.now,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val nodes = sortNodes(
                            g.nodes.filter { it.contains(searchQuery, ignoreCase = true) },
                            display.sort, delays,
                        )
                        items(nodes, key = { "${g.name}-$it" }) { node ->
                            NodeCard(
                                node = node,
                                type = nodeTypes[node] ?: "",
                                delay = delays[node],
                                selected = node == g.now,
                                density = display.density,
                                compact = display.columns >= 4,
                                onSelect = { vm.selectNode(g.name, node) },
                                onTest = { vm.testGroupDelayViaApi(node) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 搜索对话框 ──
    if (showSearch) {
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("搜索节点") },
            text = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("关键词") },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(onClick = { showSearch = false }) { Text("应用") } },
            dismissButton = {
                TextButton(onClick = { searchQuery = ""; showSearch = false }) { Text("清空") }
            },
        )
    }

    // ── 显示设置底部弹窗 ──
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("风格", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = display.style == "tabs",
                        onClick = { vm.setProxiesDisplay(display.copy(style = "tabs")) },
                        label = { Text("标签页") },
                    )
                    FilterChip(
                        selected = display.style == "list",
                        onClick = { vm.setProxiesDisplay(display.copy(style = "list")) },
                        label = { Text("列表") },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("排序", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = display.sort == "default",
                        onClick = { vm.setProxiesDisplay(display.copy(sort = "default")) },
                        label = { Text("默认") },
                    )
                    FilterChip(
                        selected = display.sort == "delay",
                        onClick = { vm.setProxiesDisplay(display.copy(sort = "delay")) },
                        label = { Text("延迟") },
                    )
                    FilterChip(
                        selected = display.sort == "name",
                        onClick = { vm.setProxiesDisplay(display.copy(sort = "name")) },
                        label = { Text("名称") },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("布局", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = display.columns == 2,
                        onClick = { vm.setProxiesDisplay(display.copy(columns = 2)) },
                        label = { Text("宽松") },
                    )
                    FilterChip(
                        selected = display.columns == 3,
                        onClick = { vm.setProxiesDisplay(display.copy(columns = 3)) },
                        label = { Text("标准") },
                    )
                    FilterChip(
                        selected = display.columns == 4,
                        onClick = { vm.setProxiesDisplay(display.copy(columns = 4)) },
                        label = { Text("紧凑") },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("尺寸", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = display.density == "standard",
                        onClick = { vm.setProxiesDisplay(display.copy(density = "standard")) },
                        label = { Text("标准") },
                    )
                    FilterChip(
                        selected = display.density == "compact",
                        onClick = { vm.setProxiesDisplay(display.copy(density = "compact")) },
                        label = { Text("紧凑") },
                    )
                    FilterChip(
                        selected = display.density == "min",
                        onClick = { vm.setProxiesDisplay(display.copy(density = "min")) },
                        label = { Text("最小") },
                    )
                }
            }
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors()) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 分组标签页（横向滚动 + 下划线指示） */
@Composable
private fun GroupTabs(groups: List<ProxyGroup>, selected: String?, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        groups.forEach { g ->
            val isSelected = g.name == selected
            Column(
                modifier = Modifier.clickable { onSelect(g.name) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    g.name,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(48.dp)
                        .height(3.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

/** 节点卡片 */
@Composable
private fun NodeCard(
    node: String,
    type: String,
    delay: Int?,
    selected: Boolean,
    density: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onTest: () -> Unit,
) {
    val (pad, nameSize, metaSize) = when (density) {
        "compact" -> Triple(10.dp, 12.sp, 10.sp)
        "min" -> Triple(8.dp, 11.sp, 9.sp)
        else -> Triple(14.dp, 13.sp, 11.sp)
    }
    // 选中状态颜色平滑过渡
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(220),
        label = "cardColor",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(if (density == "standard") 92.dp else if (density == "compact") 76.dp else 64.dp)
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(pad)) {
            Text(
                node,
                fontSize = nameSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    type,
                    fontSize = metaSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                val d = delay
                val text = when {
                    d == null -> ""
                    d < 0 -> "超时"
                    else -> "$d ms"
                }
                if (text.isNotEmpty()) {
                    val delayTextColor by animateColorAsState(
                        targetValue = delayColor(d) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(220),
                        label = "delayColor",
                    )
                    Text(
                        text,
                        fontSize = metaSize,
                        fontWeight = FontWeight.Medium,
                        color = delayTextColor,
                        modifier = Modifier.clickable(onClick = onTest),
                    )
                }
            }
        }
    }
}
