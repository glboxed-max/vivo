package com.sukisu.ultra.backend

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * SUSFS guard LKM support. The module is a GKI android14-6.1 build; the loader
 * asks finit_module to ignore vermagic/modversions so it can be tried on the
 * 6.1 vendor kernel. Rules live in /proc/susfs_* and are re-applied on load.
 */
object SusfsManager {
    const val KO_NAME = "susfs_guard_lkm-android14-6.1.ko"
    private const val LOADER = "libsusfs_load.so"
    private const val PREFS = "susfs"

    data class Status(val loaded: Boolean, val nodes: List<String>)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoLoad(context: Context) = prefs(context).getBoolean("autoLoad", false)

    fun setAutoLoad(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("autoLoad", enabled).apply()
    }

    fun logEnabled(context: Context) = prefs(context).getBoolean("log", false)

    fun avcSpoof(context: Context) = prefs(context).getBoolean("avc", false)

    fun paths(context: Context): Set<String> = prefs(context).getStringSet("paths", emptySet()) ?: emptySet()

    fun modules(context: Context): Set<String> = prefs(context).getStringSet("modules", emptySet()) ?: emptySet()

    fun updatePaths(context: Context, values: Set<String>) {
        prefs(context).edit().putStringSet("paths", values).apply()
    }

    fun updateModules(context: Context, values: Set<String>) {
        prefs(context).edit().putStringSet("modules", values).apply()
    }

    fun status(shell: Shell): Status {
        val result = shell.newJob()
            .add("[ -e /proc/susfs_path ] && echo loaded; ls /proc 2>/dev/null | grep '^susfs_' | tr '\\n' ' '")
            .exec()
        val text = result.out.joinToString("\n")
        val nodes = text.split(Regex("\\s+")).filter { it.startsWith("susfs_") }
        return Status(text.contains("loaded") || nodes.contains("susfs_path"), nodes)
    }

    fun load(context: Context, shell: Shell): String {
        val directory = File(context.filesDir, "susfs").apply { check(isDirectory || mkdirs()) }
        val ko = File(directory, KO_NAME)
        if (!ko.isFile || ko.length() < 1024 * 1024) {
            context.assets.open("susfs/$KO_NAME").use { input -> ko.outputStream().use { input.copyTo(it) } }
        }
        val loader = File(context.applicationInfo.nativeLibraryDir, LOADER)
        val result = shell.newJob().add("'${loader.absolutePath}' '${ko.absolutePath}'").exec()
        check(result.isSuccess) { "SUSFS 加载失败：${result.out.joinToString("\n").ifBlank { "rc=${result.code}" }}" }
        applyRules(context, shell)
        val status = status(shell)
        check(status.loaded) { "SUSFS 模块报告加载完成，但没有出现 /proc/susfs_* 节点；可能被内核拒绝，请查看 dmesg" }
        return "SUSFS 已加载（${status.nodes.size} 个控制节点）"
    }

    fun unload(shell: Shell): String {
        val result = shell.newJob().add("rmmod susfs_guard_lkm 2>&1 || true").exec()
        return if (status(shell).loaded) "卸载失败：${result.out.joinToString("\n")}" else "SUSFS 已卸载"
    }

    fun applyRules(context: Context, shell: Shell) {
        write(shell, "/proc/susfs_enable_log", if (logEnabled(context)) "1" else "0")
        write(shell, "/proc/susfs_avc_spoof", if (avcSpoof(context)) "1" else "0")
        write(shell, "/proc/susfs_path", "clear")
        paths(context).forEach { write(shell, "/proc/susfs_path", "add $it") }
        write(shell, "/proc/susfs_hide_modules", "clear")
        modules(context).forEach { write(shell, "/proc/susfs_hide_modules", "add $it") }
    }

    fun setLog(context: Context, shell: Shell, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean("log", enabled).apply()
        return write(shell, "/proc/susfs_enable_log", if (enabled) "1" else "0")
    }

    fun setAvcSpoof(context: Context, shell: Shell, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean("avc", enabled).apply()
        return write(shell, "/proc/susfs_avc_spoof", if (enabled) "1" else "0")
    }

    fun addPath(context: Context, shell: Shell, path: String): Boolean {
        val value = path.trim()
        if (value.isEmpty()) return false
        val updated = paths(context) + value
        updatePaths(context, updated)
        return write(shell, "/proc/susfs_path", "add $value")
    }

    fun clearPaths(context: Context, shell: Shell) {
        updatePaths(context, emptySet())
        write(shell, "/proc/susfs_path", "clear")
    }

    fun addModuleName(context: Context, shell: Shell, name: String): Boolean {
        val value = name.trim()
        if (value.isEmpty()) return false
        updateModules(context, modules(context) + value)
        return write(shell, "/proc/susfs_hide_modules", "add $value")
    }

    fun clearModuleNames(context: Context, shell: Shell) {
        updateModules(context, emptySet())
        write(shell, "/proc/susfs_hide_modules", "clear")
    }

    private fun write(shell: Shell, node: String, value: String): Boolean {
        val quoted = value.replace("'", "'\\''")
        return shell.newJob().add("echo '$quoted' > $node").exec().isSuccess
    }
}
