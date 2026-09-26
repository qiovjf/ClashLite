package com.clashlite.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashlite.ClashViewModel
import com.clashlite.core.CoreState
import com.clashlite.data.PerAppMode

@Composable
fun SettingsScreen(vm: ClashViewModel, onOpenCoreConfig: () -> Unit = {}) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAppPicker by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // 基础配置入口
        Card(
            Modifier.fillMaxWidth().clickable(onClick = onOpenCoreConfig),
            colors = CardDefaults.cardColors(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("基础配置", fontWeight = FontWeight.SemiBold)
                    Text(
                        "端口 · 局域网 · 认证 · 日志等级 · IPv6 · TCP 并发 · Geo 低内存",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 分应用代理
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("分应用代理", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.perAppMode == PerAppMode.OFF,
                        onClick = { vm.setPerAppMode(PerAppMode.OFF) },
                        label = { Text("关闭") },
                    )
                    FilterChip(
                        selected = settings.perAppMode == PerAppMode.EXCLUDE,
                        onClick = { vm.setPerAppMode(PerAppMode.EXCLUDE) },
                        label = { Text("排除应用") },
                    )
                    FilterChip(
                        selected = settings.perAppMode == PerAppMode.INCLUDE,
                        onClick = { vm.setPerAppMode(PerAppMode.INCLUDE) },
                        label = { Text("仅代理") },
                    )
                }
                Text(
                    "已选择 ${settings.perAppPackages.size} 个应用",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.perAppMode != PerAppMode.OFF) {
                    TextButton(onClick = { showAppPicker = true }) { Text("选择应用…") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 开机自启
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("启动", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                TextSwitch(
                    label = "开机自动连接 VPN",
                    checked = settings.autoBoot,
                    onChange = { vm.setAutoBoot(it) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 日志查看器：分级过滤 + 导出
        LogViewerCard()
        Spacer(Modifier.height(16.dp))
        Text("ClashLite 1.3.0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (LocalGlassNav.current) Spacer(Modifier.height(84.dp))
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = settings.perAppPackages,
            onConfirm = { vm.setPerAppPackages(it); showAppPicker = false },
            onDismiss = { showAppPicker = false },
        )
    }
}

/** 日志分级 */
private enum class LogFilter(val label: String) {
    ALL("全部"), APP("应用"), CORE("内核"), ERROR("错误");

    fun matches(line: String): Boolean = when (this) {
        ALL -> true
        APP -> line.startsWith("[app]")
        CORE -> !line.startsWith("[app]") && !line.startsWith("[tun2socks]")
        ERROR -> line.contains("level=error") || line.contains("level=fatal") ||
            line.contains("FATAL") || line.startsWith("[app] 启动失败") ||
            line.startsWith("[app] 内核") || line.contains("\"level\":\"fatal\"") ||
            line.contains("\"level\":\"error\"")
    }
}

@Composable
private fun LogViewerCard() {
    val logs by com.clashlite.core.CoreManager.logs.collectAsStateWithLifecycle()
    val coreState by com.clashlite.core.CoreManager.state.collectAsStateWithLifecycle()
    var filter by androidx.compose.runtime.remember { mutableStateOf(LogFilter.ALL) }
    val context = LocalContext.current

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write((logs + com.clashlite.core.CoreManager.logs.value).distinct().joinToString("\n").toByteArray())
                }
                android.widget.Toast.makeText(context, "日志已导出", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "导出失败: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("日志", fontWeight = FontWeight.SemiBold)
                val version = (coreState as? CoreState.Running)?.version
                Text(
                    version?.let { "mihomo $it" } ?: "内核未运行",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            // 分级过滤
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label, fontSize = 12.sp) },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    exportLauncher.launch("clashlite-logs-${System.currentTimeMillis()}.log")
                }) { Text("导出", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))

            val filtered = logs.filter { filter.matches(it) }
            LazyColumn(modifier = Modifier.height(240.dp)) {
                items(filtered) { line ->
                    Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun TextSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AppPickerDialog(
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }
    val apps = remember {
        pm.getInstalledPackages(0).mapNotNull { pkg ->
            val info = pkg.applicationInfo ?: return@mapNotNull null
            Triple(pkg.packageName, pm.getApplicationLabel(info).toString(), (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }.sortedBy { it.second }
    }
    var selection by remember { mutableStateOf(selected) }
    var showSystem by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("共 ${selection.size} 个", fontSize = 12.sp)
                    TextButton(onClick = { showSystem = !showSystem }) {
                        Text(if (showSystem) "隐藏系统应用" else "显示系统应用", fontSize = 12.sp)
                    }
                }
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(apps.filter { showSystem || !it.third }) { (pkg, label, _) ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
                            Checkbox(
                                checked = pkg in selection,
                                onCheckedChange = { checked ->
                                    selection = if (checked) selection + pkg else selection - pkg
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selection) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
