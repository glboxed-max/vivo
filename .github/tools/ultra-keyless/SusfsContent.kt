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
fun SusfsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var nodes by remember { mutableStateOf(listOf<String>()) }
    var autoLoad by remember { mutableStateOf(SusfsManager.autoLoad(context)) }
    var logEnabled by remember { mutableStateOf(SusfsManager.logEnabled(context)) }
    var avcSpoof by remember { mutableStateOf(SusfsManager.avcSpoof(context)) }
    var pathInput by remember { mutableStateOf("") }
    var moduleInput by remember { mutableStateOf("") }

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

    fun withShell(action: (RootAccess.Session) -> String): String {
        val session = RootAccess.acquire(fresh = true)
        session.shell.use { return action(session) }
    }

    LiquidGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SUSFS（LKM）", style = MaterialTheme.typography.titleLarge)
            Text("加载 android14-6.1 的 SUSFS guard LKM，并写入 /proc/susfs_* 规则。使用带忽略校验的 finit_module，能否被厂商内核接受需实机确认。")
            Text(if (loaded) "状态：已加载（${nodes.size} 个节点）" else "状态：未加载")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    operation {
                        withShell { session ->
                            val status = SusfsManager.status(session.shell)
                            loaded = status.loaded
                            nodes = status.nodes
                            if (status.loaded) "SUSFS 已加载：${status.nodes.joinToString()}" else "SUSFS 未加载"
                        }
                    }
                }) { Text("刷新状态") }
                Button(enabled = !busy, onClick = {
                    operation {
                        withShell { session -> SusfsManager.load(context, session.shell) }.also {
                            val status = withShell { session -> SusfsManager.status(session.shell) }
                            loaded = status.loaded
                            nodes = status.nodes
                        }
                    }
                }) { Text("加载") }
                OutlinedButton(enabled = !busy, onClick = {
                    operation {
                        withShell { session -> SusfsManager.unload(session.shell) }.also {
                            val status = withShell { session -> SusfsManager.status(session.shell) }
                            loaded = status.loaded
                            nodes = status.nodes
                        }
                    }
                }) { Text("卸载") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = autoLoad, enabled = !busy, onCheckedChange = {
                    autoLoad = it
                    SusfsManager.setAutoLoad(context, it)
                })
                Spacer(Modifier.width(8.dp))
                Text("开机自动加载（BootCompleted 阶段，kstat 可能延后）")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = logEnabled, enabled = !busy && loaded, onCheckedChange = {
                    logEnabled = it
                    operation { withShell { session -> if (SusfsManager.setLog(context, session.shell, it)) "日志已${if (it) "开启" else "关闭"}" else "写入失败" } }
                })
                Spacer(Modifier.width(8.dp))
                Text("SUSFS 日志")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = avcSpoof, enabled = !busy && loaded, onCheckedChange = {
                    avcSpoof = it
                    operation { withShell { session -> if (SusfsManager.setAvcSpoof(context, session.shell, it)) "AVC 伪装已${if (it) "开启" else "关闭"}" else "写入失败" } }
                })
                Spacer(Modifier.width(8.dp))
                Text("AVC 日志伪装")
            }
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = { Text("要隐藏的路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && loaded && pathInput.isNotBlank(), onClick = {
                    operation {
                        val result = withShell { session ->
                            if (SusfsManager.addPath(context, session.shell, pathInput)) "已添加 $pathInput" else "添加失败"
                        }
                        if (result.startsWith("已添加")) pathInput = ""
                        result
                    }
                }) { Text("添加路径") }
                OutlinedButton(enabled = !busy && loaded, onClick = {
                    operation { withShell { session -> SusfsManager.clearPaths(context, session.shell); "已清空路径规则" } }
                }) { Text("清空路径") }
            }
            OutlinedTextField(
                value = moduleInput,
                onValueChange = { moduleInput = it },
                label = { Text("要从 /proc/modules 隐藏的模块名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && loaded && moduleInput.isNotBlank(), onClick = {
                    operation {
                        val result = withShell { session ->
                            if (SusfsManager.addModuleName(context, session.shell, moduleInput)) "已添加 $moduleInput" else "添加失败"
                        }
                        if (result.startsWith("已添加")) moduleInput = ""
                        result
                    }
                }) { Text("隐藏模块") }
                OutlinedButton(enabled = !busy && loaded, onClick = {
                    operation { withShell { session -> SusfsManager.clearModuleNames(context, session.shell); "已清空模块隐藏" } }
                }) { Text("清空模块") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message)
        }
    }
}
