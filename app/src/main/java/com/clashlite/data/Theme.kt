package com.clashlite.data

import kotlinx.serialization.Serializable

/** 深浅色模式 */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/** 一份完整的主题配置，可序列化后导出/导入 */
@Serializable
data class ThemeConfig(
    val id: String,
    val name: String,
    /** 主色 ARGB */
    val seedColor: Long = 0xFF1E88E5L,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    /** Android 12+ 跟随壁纸动态取色 */
    val useDynamicColor: Boolean = false,
    /** 深黑 AMOLED 背景 */
    val amoled: Boolean = false,
    /** 液态玻璃风格底部导航栏（半透明+高光描边） */
    val glassNavBar: Boolean = false,
)

/** 内置预设主题 */
object ThemePresets {
    val presets = listOf(
        ThemeConfig("p1", "Clash 蓝", 0xFF1E88E5),
        ThemeConfig("p2", "薄荷绿", 0xFF00A98F),
        ThemeConfig("p3", "樱花粉", 0xFFE91E63),
        ThemeConfig("p4", "日落橙", 0xFFFF7043),
        ThemeConfig("p5", "星空紫", 0xFF7C4DFF),
        ThemeConfig("p6", "青柠", 0xFF7CB342),
        ThemeConfig("p7", "琥珀", 0xFFFFB300),
        ThemeConfig("p8", "石墨灰", 0xFF546E7A),
    )
}
