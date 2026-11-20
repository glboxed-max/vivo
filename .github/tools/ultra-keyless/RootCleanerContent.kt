package com.sukisu.ultra.backend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RootCleanerCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<RootManagerCleaner.Report?>(null) }
    var uninstallApps by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }

    fun operation(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { action() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    LiquidGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("其它 Root 管理器", style = MaterialTheme.typography.titleLarge)
            Text("检测 Magisk、KernelSU、APatch 等。移除前会把数据备份到 /data/adb/.pd2339-removed-时间/，不改动 /data/adb/modules、/data/adb/ap 和当前管理器的后端。")
            OutlinedButton(enabled = !busy, onClick = {
                operation {
                    val result = RootManagerCleaner.scan(context)
                    report = result
                    result.findings.joinToString("\n") { "• ${it.label}：${it.detail}" }
                }
            }) { Text("检测") }
            report?.let { result ->
                Text(result.findings.joinToString("\n") { "• ${it.label}：${it.detail}" })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = uninstallApps, onCheckedChange = { uninstallApps = it }, enabled = !busy)
                    Spacer(Modifier.width(8.dp))
                    Text("同时卸载管理器应用")
                }
                Button(
                    enabled = !busy && result.removableCount > 0,
                    onClick = { confirm = true },
                ) { Text("备份并移除") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message)
        }
    }
    if (confirm) {
        val result = report
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认移除其它 Root 管理器") },
            text = {
                Text(buildString {
                    append("当前 Root 来源：${result?.rootSource}\n\n")
                    append(result?.findings?.joinToString("\n") { "• ${it.label}" }.orEmpty())
                    if (result?.rootSource == "KernelSU") {
                        append("\n\n当前正在使用 KernelSU 的 Root。移除后本次 Root 会失效，请确认已准备好 Ultra 修补的 boot，重启后由 KernelPatch 接管。")
                    }
                    append("\n\n原数据会移动到 /data/adb/.pd2339-removed-时间/，重启后生效。")
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    operation {
                        val current = result ?: error("请先检测")
                        RootManagerCleaner.remove(context, current, uninstallApps)
                    }
                }) { Text("确认移除") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}
