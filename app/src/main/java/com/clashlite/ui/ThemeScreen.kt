package com.clashlite.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashlite.ClashViewModel
import com.clashlite.data.DarkMode
import com.clashlite.data.ThemeConfig
import com.clashlite.data.ThemePresets

/**
 * 主题定制页：
 * - 预设主题 / 自定义主色（HSV 滑条）
 * - 深浅色模式、动态取色（Android 12+）、AMOLED 纯黑
 * - 导出/导入主题 JSON
 */
@Composable
fun ThemeScreen(vm: ClashViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    var showImport by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("主题", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // 预设
        Text("预设主题", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemePresets.presets.take(4).forEach { preset ->
                PresetSwatch(preset, theme, onSelect = { vm.saveTheme(preset) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemePresets.presets.drop(4).forEach { preset ->
                PresetSwatch(preset, theme, onSelect = { vm.saveTheme(preset) })
            }
        }

        Spacer(Modifier.height(16.dp))

        // 自定义主色
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("自定义主色", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                var currentSeed by remember(theme.seedColor) { mutableStateOf(theme.seedColor) }
                HsvSliders(
                    color = currentSeed,
                    onChange = { currentSeed = it },
                    onCommit = { vm.saveTheme(theme.copy(seedColor = it, useDynamicColor = false, name = "自定义")) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 外观选项
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("外观", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                Text("深浅色模式")
                Row {
                    DarkMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { vm.saveTheme(theme.copy(darkMode = mode)) },
                        ) {
                            RadioButton(
                                selected = theme.darkMode == mode,
                                onClick = { vm.saveTheme(theme.copy(darkMode = mode)) },
                            )
                            Text(
                                when (mode) {
                                    DarkMode.SYSTEM -> "跟随系统"
                                    DarkMode.LIGHT -> "浅色"
                                    DarkMode.DARK -> "深色"
                                },
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                SettingSwitch(
                    label = "动态取色（Android 12+ 跟随壁纸）",
                    checked = theme.useDynamicColor,
                    enabled = android.os.Build.VERSION.SDK_INT >= 31,
                    onChange = { vm.saveTheme(theme.copy(useDynamicColor = it)) },
                )
                SettingSwitch(
                    label = "AMOLED 纯黑背景（深色模式）",
                    checked = theme.amoled,
                    onChange = { vm.saveTheme(theme.copy(amoled = it)) },
                )
                SettingSwitch(
                    label = "液态玻璃导航栏",
                    checked = theme.glassNavBar,
                    onChange = { vm.saveTheme(theme.copy(glassNavBar = it)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 导入导出
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { exportText = vm.exportTheme(theme) }) { Text("导出主题") }
            TextButton(onClick = { showImport = true }) { Text("导入主题") }
        }
        if (LocalGlassNav.current) Spacer(Modifier.height(84.dp))
    }

    if (showImport) {
        var raw by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入主题") },
            text = {
                Column {
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it; error = false },
                        label = { Text("粘贴主题 JSON") },
                        isError = error,
                    )
                    if (error) Text("JSON 无效", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!vm.importTheme(raw)) error = true else showImport = false
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } },
        )
    }

    exportText?.let { json ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text("主题 JSON（长按复制）") },
            text = { Text(json, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(json))
                    exportText = null
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { exportText = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun PresetSwatch(preset: ThemeConfig, current: ThemeConfig, onSelect: () -> Unit) {
    val selected = preset.seedColor == current.seedColor
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(preset.seedColor.toInt()))
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                )
                .clickable(onClick = onSelect),
        )
        Spacer(Modifier.height(4.dp))
        Text(preset.name, fontSize = 11.sp)
    }
}

@Composable
private fun HsvSliders(color: Long, onChange: (Long) -> Unit, onCommit: (Long) -> Unit) {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toInt(), hsv)
    var hue by remember(color) { mutableStateOf(hsv[0]) }
    var sat by remember(color) { mutableStateOf(hsv[1]) }
    var bri by remember(color) { mutableStateOf(hsv[2]) }

    fun toColor(h: Float, s: Float, v: Float): Long =
        AndroidColor.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL

    val preview = Color(toColor(hue, sat, bri).toInt())

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(preview)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Text("拖动滑条调整颜色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("色相", fontSize = 12.sp)
        Slider(
            value = hue,
            onValueChange = { hue = it; onChange(toColor(hue, sat, bri)) },
            onValueChangeFinished = { onCommit(toColor(hue, sat, bri)) },
            valueRange = 0f..360f,
        )
        Text("饱和度", fontSize = 12.sp)
        Slider(
            value = sat,
            onValueChange = { sat = it; onChange(toColor(hue, sat, bri)) },
            onValueChangeFinished = { onCommit(toColor(hue, sat, bri)) },
            valueRange = 0.2f..1f,
        )
        Text("亮度", fontSize = 12.sp)
        Slider(
            value = bri,
            onValueChange = { bri = it; onChange(toColor(hue, sat, bri)) },
            onValueChangeFinished = { onCommit(toColor(hue, sat, bri)) },
            valueRange = 0.2f..1f,
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
