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
fun ModuleSetupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LiquidGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("用户态模块后端", style = MaterialTheme.typography.titleLarge)
            Text(if (ModuleRuntime.installed) "APD 模块后端已就绪" else "安装后可使用模块列表、安装、启用、停用和卸载功能。")
            Text("首次安装后需要重启，让 KernelPatch 的开机事件执行用户态模块脚本。系统挂载类模块需要兼容 APatch 的挂载模块。")
            Button(enabled = !busy && PairingSession.ready, onClick = { confirm = true }) {
                Text(if (ModuleRuntime.installed) "重新安装后端" else "安装用户态模块后端")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("确认安装 APD 模块后端") },
        text = { Text("将备份现有组件和旧授权表，安装 /data/adb/apd 与运行工具，并应用 APatch 模块所需的运行策略。当前有效授权会由 Ultra 保存和恢复。用户态模块将在重启时通过 KernelPatch 开机事件加载。") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { ModuleRuntime.install(context) }
                        message = "安装完成，请重启后使用用户态模块。"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        message = failure.message ?: "后端安装失败"
                    } finally { busy = false }
                }
            }) { Text("备份并安装") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}
