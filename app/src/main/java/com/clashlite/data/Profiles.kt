package com.clashlite.data

import kotlinx.serialization.Serializable

/** 订阅/配置文件 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** 订阅地址；手动导入的本地文件为空 */
    val url: String = "",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** 节点数量，解析后更新 */
    val nodeCount: Int = 0,
)

/** 分应用代理模式 */
enum class PerAppMode { OFF, EXCLUDE, INCLUDE }

/** 代理页显示偏好 */
@Serializable
data class ProxiesDisplay(
    val style: String = "tabs",      // tabs=分组标签页+网格 / list=分组列表
    val sort: String = "default",    // default / delay / name
    val columns: Int = 3,            // 宽松2 标准3 紧凑4
    val density: String = "standard" // standard / compact / min
)

/** 全局运行设置 */
data class AppSettings(
    val activeProfileId: String? = null,
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val perAppPackages: Set<String> = emptySet(),
    val autoBoot: Boolean = false,
)
