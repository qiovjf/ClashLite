package com.clashlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashlite.ClashViewModel
import com.clashlite.core.MihomoApi
import com.clashlite.util.formatBytes
import com.clashlite.util.formatSpeed

private enum class ConnFilter(val label: String) {
    ALL("全部"), TCP("TCP"), UDP("UDP");
}

/** 连接监控页（v2.0）：实时连接列表 */
@Composable
fun ConnectionsScreen(vm: ClashViewModel) {
    val state by vm.connections.collectAsStateWithLifecycle()
    val running by vm.vpnRunning.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(ConnFilter.ALL) }

    LaunchedEffect(running) { /* 轮询由 VM 管理，此处无需操作 */ }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("连接", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.closeAllConnections() }) { Text("全部断开") }
        }

        // 累计流量
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(10.dp)) {
                    Text("累计上行", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(state.snapshot.uploadTotal), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(10.dp)) {
                    Text("累计下行", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(state.snapshot.downloadTotal), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(10.dp)) {
                    Text("活动连接", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${state.snapshot.items.size}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 类型过滤
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label, fontSize = 12.sp) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (!running) {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors()) {
                Text("VPN 未连接。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        val visible = state.snapshot.items.filter {
            when (filter) {
                ConnFilter.ALL -> true
                ConnFilter.TCP -> it.network == "tcp"
                ConnFilter.UDP -> it.network == "udp"
            }
        }.sortedByDescending { it.download + it.upload }

        if (visible.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors()) {
                Text("暂无活动连接", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.id }) { conn ->
                ConnectionRow(conn) { vm.closeConnection(conn.id) }
            }
            item { Spacer(Modifier.height(if (LocalGlassNav.current) 100.dp else 16.dp)) }
        }
    }
}

@Composable
private fun ConnectionRow(conn: MihomoApi.ConnectionItem, onClose: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClose),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 网络类型徽标
                Text(
                    conn.network.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (conn.network == "udp") delayOrange else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(
                            (if (conn.network == "udp") delayOrange else MaterialTheme.colorScheme.primary).copy(alpha = 0.12f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    conn.host.ifBlank { conn.destination },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "断开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(onClick = onClose),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    conn.destination.takeIf { it.isNotBlank() && it != ":" },
                    conn.process.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conn.chains.ifBlank { conn.rule.ifBlank { "—" } },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "↑${formatSpeed(conn.upload)} ↓${formatSpeed(conn.download)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
