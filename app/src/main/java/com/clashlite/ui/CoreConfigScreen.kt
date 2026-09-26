package com.clashlite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashlite.ClashViewModel
import com.clashlite.data.CoreOverrides

/** 基础配置页：内核运行参数（参考 FlClash 的基础配置） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreConfigScreen(vm: ClashViewModel, onBack: () -> Unit) {
    val ov by vm.overrides.collectAsStateWithLifecycle()

    var editDialog by remember { mutableStateOf<String?>(null) }

    fun update(transform: (CoreOverrides) -> CoreOverrides) = vm.updateOverrides(transform)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("基础配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionLabel("入站")

            // 端口
            ItemRow(
                icon = Icons.Filled.SettingsEthernet,
                title = "端口",
                subtitle = ov.mixedPort.toString(),
                onClick = { editDialog = "port" },
            )
            // 局域网代理
            ItemRow(
                icon = Icons.Filled.Hub,
                title = "局域网代理",
                subtitle = "允许通过局域网访问代理",
                toggle = ov.allowLan,
                onToggle = { update { it.copy(allowLan = !it.allowLan) } },
            )
            // 外部控制器
            ItemRow(
                icon = Icons.Filled.Visibility,
                title = "外部控制器",
                subtitle = "开启后才能通过 9090 端口控制 Clash 内核（关闭将无法显示速率/切换节点）",
                toggle = ov.externalController,
                onToggle = { update { it.copy(externalController = !it.externalController) } },
            )
            // 认证
            ItemRow(
                icon = Icons.Filled.Key,
                title = "认证",
                subtitle = if (ov.authentication) "${ov.authUser}:${ov.authPass}" else "为本地代理端口启用认证，防止本机其他应用擅自使用",
                toggle = ov.authentication,
                onToggle = { update { it.copy(authentication = !it.authentication) } },
            )

            SectionLabel("其他")

            // 日志等级
            ItemRow(
                icon = Icons.Filled.Info,
                title = "日志等级",
                subtitle = ov.logLevel,
                onClick = { editDialog = "logLevel" },
            )
            // 用户代理
            ItemRow(
                icon = Icons.Filled.Devices,
                title = "用户代理",
                subtitle = ov.userAgent.ifBlank { "默认" },
                onClick = { editDialog = "ua" },
            )
            // 测速链接
            ItemRow(
                icon = Icons.Filled.NetworkCheck,
                title = "测速链接",
                subtitle = ov.delayTestUrl,
                onClick = { editDialog = "delayUrl" },
            )
            // 追加系统 DNS
            ItemRow(
                icon = Icons.Filled.Dns,
                title = "追加系统 DNS",
                subtitle = "强制为配置附加系统 DNS",
                toggle = ov.appendSystemDns,
                onToggle = { update { it.copy(appendSystemDns = !it.appendSystemDns) } },
            )
            // IPv6
            ItemRow(
                icon = Icons.Filled.SwapVert,
                title = "IPv6",
                subtitle = "开启后将可以接收 IPv6 流量",
                toggle = ov.ipv6,
                onToggle = { update { it.copy(ipv6 = !it.ipv6) } },
            )
            // 统一延迟
            ItemRow(
                icon = Icons.Filled.Speed,
                title = "统一延迟",
                subtitle = "去除握手等额外延迟",
                toggle = ov.unifiedDelay,
                onToggle = { update { it.copy(unifiedDelay = !it.unifiedDelay) } },
            )
            // TCP 并发
            ItemRow(
                icon = Icons.Filled.Terminal,
                title = "TCP 并发",
                subtitle = "开启后允许 TCP 并发",
                toggle = ov.tcpConcurrent,
                onToggle = { update { it.copy(tcpConcurrent = !it.tcpConcurrent) } },
            )
            // 查找进程
            ItemRow(
                icon = Icons.Filled.Visibility,
                title = "查找进程",
                subtitle = "开启后会有一定性能损耗",
                toggle = ov.findProcess,
                onToggle = { update { it.copy(findProcess = !it.findProcess) } },
            )
            // Geo 低内存模式
            ItemRow(
                icon = Icons.Filled.Memory,
                title = "Geo 低内存模式",
                subtitle = "开启将使用 Geo 低内存加载器",
                toggle = ov.geoLowMemory,
                onToggle = { update { it.copy(geoLowMemory = !it.geoLowMemory) } },
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "带 ⚠ 的项（端口/局域网/认证/IPv6/追加 DNS/查找进程/Geo）在下次连接时生效；模式与日志等级即时生效。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    // ── 编辑对话框 ──
    editDialog?.let { kind ->
        var text by remember(kind) { mutableStateOf(textOf(ov, kind)) }
        AlertDialog(
            onDismissRequest = { editDialog = null },
            title = { Text(dialogTitle(kind)) },
            text = {
                if (kind == "logLevel") {
                    Column {
                        listOf("debug", "info", "warning", "error", "silent").forEach { level ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { update { it.copy(logLevel = level) }; editDialog = null }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    level,
                                    color = if (level == ov.logLevel) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (level == ov.logLevel) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text(dialogTitle(kind)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (kind) {
                        "port" -> text.toIntOrNull()?.let { p -> if (p in 1024..65535) update { it.copy(mixedPort = p) } }
                        "ua" -> update { it.copy(userAgent = text.trim()) }
                        "delayUrl" -> if (text.trim().startsWith("http")) update { it.copy(delayTestUrl = text.trim()) }
                    }
                    editDialog = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editDialog = null }) { Text("取消") } },
        )
    }
}

private fun textOf(ov: CoreOverrides, kind: String): String = when (kind) {
    "port" -> ov.mixedPort.toString()
    "ua" -> ov.userAgent
    "delayUrl" -> ov.delayTestUrl
    else -> ""
}

private fun dialogTitle(kind: String): String = when (kind) {
    "port" -> "混合代理端口（1024-65535）"
    "ua" -> "用户代理"
    "delayUrl" -> "测速链接"
    else -> ""
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun ItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    toggle: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (toggle != null && onToggle != null) {
                Switch(checked = toggle, onCheckedChange = { onToggle() })
            } else {
                Spacer(Modifier.width(28.dp))
            }
        }
    }
}
