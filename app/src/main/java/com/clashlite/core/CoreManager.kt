package com.clashlite.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** 内核运行状态 */
sealed class CoreState {
    data object Stopped : CoreState()
    data object Starting : CoreState()
    data class Running(val version: String) : CoreState()
    data class Failed(val message: String) : CoreState()
}

/**
 * mihomo 内核进程管理：
 * - 从 nativeLibraryDir exec 预编译的 libmihomo.so（Android 10+ 唯一可执行位置）
 * - watchdog 自动拉起崩溃进程（只要服务仍在运行）
 * - stdout 重定向到日志文件（每次启动截断）+ 内存环形缓冲（供 UI 查看）
 */
class CoreManager(private val context: Context) {

    companion object {
        /** 供 UI/服务共享的状态流 */
        val state = kotlinx.coroutines.flow.MutableStateFlow<CoreState>(CoreState.Stopped)

        /** 最近 200 行内核日志 */
        val logs = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())

        private const val MAX_LOG_LINES = 200

        /** 追加一行日志（进程与 UI 共享）。已知噪音行（netlink 权限错误）只落文件不进 UI */
        fun appendLog(line: String, isNoise: Boolean = false) {
            if (isNoise) return
            val list = logs.value.toMutableList()
            list.add(line)
            while (list.size > MAX_LOG_LINES) list.removeAt(0)
            logs.value = list
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var process: Process? = null
    private var watchdog: Job? = null
    private var logJob: Job? = null
    private val mutex = Mutex()
    private var lastConfig: String? = null
    private var lastControllerEnabled = true

    private fun binary(): File = File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")

    fun configDir(): File = File(context.filesDir, "core").apply { mkdirs() }

    /** 服务销毁时的同步兜底清理（防止残留内核子进程） */
    fun destroyNow() {
        watchdog?.cancel()
        runCatching { process?.destroy() }
        process = null
        if (state.value is CoreState.Running || state.value is CoreState.Starting) {
            state.value = CoreState.Stopped
        }
    }

    suspend fun start(configYaml: String, controllerEnabled: Boolean = true): Boolean = mutex.withLock {
        if (state.value is CoreState.Running || state.value is CoreState.Starting) return true
        lastConfig = configYaml
        lastControllerEnabled = controllerEnabled
        startInternalLocked(configYaml, controllerEnabled)
    }

    /** 调用方必须已持有 mutex */
    private suspend fun startInternalLocked(configYaml: String, controllerEnabled: Boolean = lastControllerEnabled): Boolean {
        state.value = CoreState.Starting
        appendLog("[app] 正在启动 mihomo 内核…")
        try {
            val dir = configDir()
            val configFile = File(dir, "config.yaml")
            configFile.writeText(configYaml)
            // 日志文件每次启动截断，防止无限膨胀
            val runLog = File(dir, "run.log")
            if (runLog.length() > 2_000_000) runLog.writeText("")

            val bin = binary()
            check(bin.exists() && bin.canExecute()) { "内核二进制不可执行: ${bin.absolutePath}" }

            process = ProcessBuilder(bin.absolutePath, "-d", dir.absolutePath, "-f", configFile.absolutePath)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val proc = process!!
            logJob?.cancel()
            logJob = scope.launch { pumpLogs(proc, runLog) }

            // 等待 REST API 就绪（首次启动加载规则集可能较慢，最多等 30 秒）；
            // 外部控制器被关闭时跳过就绪检测，直接视为运行中
            if (!controllerEnabled) {
                delay(1500)
                state.value = CoreState.Running("v?.")
                appendLog("[app] 内核已启动（外部控制器已关闭，无法显示速率/节点）")
                startWatchdog()
                return true
            }
            val api = MihomoApi()
            var ok = false
            var lastErr: Exception? = null
            var attempt = 0
            while (attempt < 60) {
                delay(500)
                attempt++
                val result = runCatching { api.versionRaw() }
                val raw = result.getOrNull()
                val v = raw?.let {
                    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)
                }
                if (v != null) {
                    state.value = CoreState.Running(v)
                    appendLog("[app] mihomo $v 已就绪")
                    ok = true
                    break
                }
                if (!proc.isAlive) {
                    lastErr = IllegalStateException("内核进程退出, code=${proc.exitValue()}")
                    break
                }
                if (attempt % 8 == 0) {
                    appendLog("[app] 等待API($attempt): ${result.exceptionOrNull()?.message ?: "响应异常: ${raw?.take(80)}"}")
                }
            }
            if (!ok) {
                val msg = lastErr?.message ?: "REST API 未在 30 秒内就绪"
                appendLog("[app] 启动失败: $msg")
                state.value = CoreState.Failed(msg)
                stopProcessLocked()
                return false
            }
            startWatchdog()
            return true
        } catch (e: Exception) {
            appendLog("[app] 内核启动异常: ${e.message}")
            state.value = CoreState.Failed(e.message ?: "unknown")
            stopProcessLocked()
            return false
        }
    }

    suspend fun stop() = mutex.withLock {
        appendLog("[app] 正在停止内核…")
        stopProcessLocked()
        state.value = CoreState.Stopped
    }

    private fun stopProcessLocked() {
        watchdog?.cancel(); watchdog = null
        runCatching { process?.destroy() }
        process = null
        logJob?.cancel(); logJob = null
    }

    /** 内核崩溃自动重启（服务存活期间） */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(1000)
                val proc = process ?: break
                if (proc.isAlive) continue
                if (state.value !is CoreState.Running) break
                appendLog("[app] 内核意外退出(code=${runCatching { proc.exitValue() }.getOrDefault(-1)})，自动重启…")
                val cfg = lastConfig ?: break
                stopProcessLocked()
                state.value = CoreState.Starting
                delay(1500)
                val ok = mutex.withLock { startInternalLocked(cfg) }
                if (!ok) break
            }
        }
    }

    private suspend fun pumpLogs(proc: Process, logFile: File?) {
        runCatching {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    // netlink 路由权限错误是 Android 平台限制的已知噪音，只落文件不进 UI
                    val noise = it.contains("netlinkrib: permission denied")
                    appendLog(it, noise)
                    runCatching { logFile?.appendText(it + "\n") }
                }
            }
        }
    }
}
