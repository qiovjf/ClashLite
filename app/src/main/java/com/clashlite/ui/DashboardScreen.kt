package com.clashlite.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashlite.ClashViewModel
import com.clashlite.core.CoreState
import com.clashlite.util.formatSpeed

@Composable
fun DashboardScreen(vm: ClashViewModel) {
    val running by vm.vpnRunning.collectAsStateWithLifecycle()
    val status by vm.vpnStatus.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val coreState by com.clashlite.core.CoreManager.state.collectAsStateWithLifecycle()
    val logs by com.clashlite.core.CoreManager.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    val ipCheck by vm.ipCheck.collectAsStateWithLifecycle()

    // VPN 授权与启动
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.requestVpnStartVia(context)
        }
    }

    val activeProfile = profiles.find { it.id == settings.activeProfileId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        // 连接按钮
        Box(contentAlignment = Alignment.Center) {
            val glow by animateColorAsState(
                targetValue = if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent,
                label = "glow",
            )
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(glow),
            )
            androidx.compose.material3.FilledIconButton(
                onClick = {
                    if (running) {
                        vm.requestVpnStop()
                    } else {
                        val prepare = VpnService.prepare(context)
                        if (prepare == null) {
                            vm.requestVpnStartVia(context)
                        } else {
                            vpnConsentLauncher.launch(prepare)
                        }
                    }
                },
                modifier = Modifier.size(180.dp),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (running) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (running) "断开" else "连接",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = status, fontSize = 13.sp, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 模式切换：规则 / 全局 / 直连（热更）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("rule" to "规则", "global" to "全局", "direct" to "直连").forEach { (value, label) ->
                FilterChip(
                    selected = overrides.mode == value,
                    onClick = { vm.setMode(value) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 速率卡片
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedCard("↑ 上行", formatSpeed(speed.up), Modifier.weight(1f))
            SpeedCard("↓ 下行", formatSpeed(speed.down), Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // IP 检测卡片
        IpCheckCard(ipCheck, running) { vm.checkExitIp() }

        Spacer(Modifier.height(12.dp))

        // 精简日志：只显示应用层状态，最近 3 行；完整日志见 设置 → 日志
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("运行状态", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        "完整日志见 设置 → 日志",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                val appLogs = logs.filter { it.startsWith("[app]") }.takeLast(3)
                if (appLogs.isEmpty()) {
                    Text("—", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                appLogs.forEach { line ->
                    Text(
                        line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (LocalGlassNav.current) Spacer(Modifier.height(84.dp))
            }
        }
    }
}

@Composable
private fun IpCheckCard(state: com.clashlite.ClashViewModel.IpCheck, running: Boolean, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onRefresh) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    !running -> "·"
                    state.loading -> "⟳"
                    state.overseas == null -> "?"
                    else -> flagOf(state.info?.countryCode ?: "")
                },
                fontSize = 26.sp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !running -> "未连接 · 点击检测出口 IP"
                        state.loading -> "正在检测出口…"
                        state.overseas == null -> "检测失败 · 点击重试"
                        state.overseas -> "境外出口"
                        else -> "境内出口"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = when {
                        state.overseas == true -> Color(0xFF4CAF50)
                        state.overseas == false -> Color(0xFFFFA726)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                val ip = state.info?.ip
                if (ip != null) {
                    Text(
                        listOfNotNull(
                            state.info?.country?.takeIf { it.isNotBlank() },
                            ip,
                        ).joinToString(" · "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.loading) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("点击刷新", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 国家代码转旗帜 emoji（区域指示符超出 BMP，需用 toChars 生成代理对） */
private fun flagOf(countryCode: String): String {
    if (countryCode.length != 2) return "\uD83C\uDF10"
    return countryCode.uppercase()
        .map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }
        .joinToString("")
}

@Composable
private fun SpeedCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
