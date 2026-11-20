package com.sukisu.ultra.backend

import android.content.Context
import java.io.File

/**
 * Detects other root managers and removes their private data after a backup.
 * Shared trees (/data/adb/modules, /data/adb/ap, /data/adb/apd) are never touched
 * because Ultra's own module backend also uses them.
 */
object RootManagerCleaner {
    data class Finding(val label: String, val detail: String, val removable: Boolean)
    data class Report(
        val rootSource: String,
        val findings: List<Finding>,
        val apps: List<Pair<String, String>>,
        val paths: List<String>,
    ) {
        val removableCount: Int get() = findings.count { it.removable }
    }

    private val PACKAGES = linkedMapOf(
        "com.topjohnwu.magisk" to "Magisk",
        "me.weishu.kernelsu" to "KernelSU",
        "com.sukisu.ultra" to "SukiSU Ultra（上游）",
        "com.sukisu.nova" to "SukiSU Nova",
        "me.bmax.apatch" to "APatch",
        "me.inkdye.vivoksu" to "vivo KernelSU 管理器",
        "eu.chainfire.supersu" to "SuperSU",
        "com.kingroot.kinguser" to "KingRoot",
    )

    private val PATHS = linkedMapOf(
        "/data/adb/magisk" to "Magisk 数据",
        "/data/adb/ksu" to "KernelSU 数据",
        "/data/adb/ksud" to "KernelSU 守护进程",
    )

    fun scan(context: Context): Report {
        val session = RootAccess.acquire(fresh = true)
        val shell = session.shell
        val manager = context.packageManager
        val apps = PACKAGES.filterKeys { it != context.packageName }.filter { (pkg, _) ->
            runCatching { manager.getPackageInfo(pkg, 0) }.isSuccess
        }.toList()

        val probe = StringBuilder()
        PATHS.forEach { (path, _) ->
            probe.appendLine("[ -e '$path' ] && echo 'path:$path'")
        }
        probe.appendLine("grep -q '^kernelsu ' /proc/modules && echo 'module:kernelsu'")
        probe.appendLine("ps -A -o NAME 2>/dev/null | grep -E '^(magiskd|ksud)$' | sort -u | sed 's/^/proc:/'")
        val output = shell.newJob().add(probe.toString()).exec()
        val lines = output.out.map { it.trim() }.filter { it.isNotEmpty() }

        val findings = mutableListOf<Finding>()
        apps.forEach { (pkg, label) -> findings += Finding(label, "已安装应用 $pkg", true) }
        lines.filter { it.startsWith("path:") }.forEach { line ->
            val path = line.removePrefix("path:")
            findings += Finding(PATHS[path] ?: path, path, true)
        }
        lines.filter { it.startsWith("proc:") }.forEach { line ->
            findings += Finding("运行中的 Root 守护进程", line.removePrefix("proc:"), false)
        }
        if (lines.any { it == "module:kernelsu" }) {
            findings += Finding("KernelSU 内核模块", "已加载，来自 init_boot，需单独清理 init_boot 后重启", false)
        }
        if (findings.none { it.removable }) {
            findings += Finding("未发现其它 Root 管理器", "当前只有 ${session.source}", false)
        }
        val removablePaths = findings.filter { it.removable && it.detail.startsWith("/data/adb/") }
            .map { it.detail }
        return Report(session.source, findings, apps, removablePaths)
    }

    /**
     * Uninstalls selected manager apps first (still needs the current su), then moves
     * private data to /data/adb/.pd2339-removed-<stamp>/. Reboot afterwards.
     */
    fun remove(context: Context, report: Report, uninstallApps: Boolean): String {
        val session = RootAccess.acquire(fresh = true)
        val shell = session.shell
        val stamp = DownloadStore.stamp()
        val backup = "/data/adb/.pd2339-removed-$stamp"
        val removedApps = mutableListOf<String>()
        val failedApps = mutableListOf<String>()
        if (uninstallApps) {
            report.apps.forEach { (pkg, _) ->
                val result = shell.newJob().add("pm uninstall --user 0 '$pkg'").exec()
                if (result.isSuccess) removedApps += pkg else failedApps += pkg
            }
        }
        val moves = report.paths.joinToString("\n") { path ->
            val name = path.substringAfterLast('/')
            "if [ -e '$path' ]; then mkdir -p '$backup'; mv '$path' '$backup/$name'; fi"
        }
        val script = """
            set -e
            mkdir -p '$backup'
            $moves
            sync
            echo '$backup'
        """.trimIndent()
        val result = shell.newJob().add(script).exec()
        check(result.isSuccess) { "移除失败 (${result.code})，已移动的内容保存在 $backup" }

        val text = buildString {
            appendLine("rootSource=${session.source}")
            appendLine("backup=$backup")
            appendLine("paths=${report.paths.joinToString()}")
            appendLine("uninstalled=${removedApps.joinToString()}")
            if (failedApps.isNotEmpty()) appendLine("uninstallFailed=${failedApps.joinToString()}")
        }
        val temp = File(context.cacheDir, "root-clean-$stamp.txt").apply { writeText(text) }
        DownloadStore.save(context, temp, "root-clean-$stamp.txt", stamp)
        return buildString {
            append("已移除 ${report.paths.size} 项数据")
            if (removedApps.isNotEmpty()) append("，卸载 ${removedApps.size} 个应用")
            if (failedApps.isNotEmpty()) append("，卸载失败 ${failedApps.joinToString()}")
            append("。原数据备份在手机 $backup，重启后生效。")
        }
    }
}
