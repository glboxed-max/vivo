package com.sukisu.nova.backend

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.Natives

@Composable
fun KernelPatchScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("SukiSU Nova v3 · KernelPatch 后端 · 调试版本") }
    var ready by remember { mutableStateOf(KernelPatchBackend.ready) }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var uid by remember { mutableStateOf("") }
    var appQuery by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
    var showApps by remember { mutableStateOf(false) }
    var module by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var modules by remember { mutableStateOf("") }
    var confirmFlash by remember { mutableStateOf(false) }
    fun runOperation(operation: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { operation() }
                ready = KernelPatchBackend.ready
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: error.javaClass.simpleName
            } finally { busy = false }
        }
    }
    val chooseBoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selected = uri
        output = null
    }
    val saveBoot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val current = output
        if (uri != null && current != null) runOperation {
            context.contentResolver.openOutputStream(uri, "w")!!.use { destination -> current.inputStream().use { it.copyTo(destination) } }
            "已导出修补镜像，SHA-256 ${KernelPatchBackend.hash(current)}"
        }
    }
    val chooseKpm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runOperation {
            KernelPatchBackend.load(context, uri, args)
            modules = KernelPatchBackend.modules()
            "KPM 已加载"
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SukiSU Nova", style = MaterialTheme.typography.headlineLarge)
        Text("KernelPatch · 调试版本", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("SuperKey") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("修补时设置密钥。开机后输入同一密钥管理 Root 与 KPM。密钥只保留在当前进程内存。")
        Button(enabled = !busy && key.isNotEmpty(), onClick = { runOperation {
            KernelPatchBackend.authenticate(key)
            "已连接 KernelPatch ${KernelPatchBackend.version()}"
        } }) { Text("验证并连接内核") }
        HorizontalDivider()
        Text("修补 boot", style = MaterialTheme.typography.titleLarge)
        Text("原厂内核二进制修补，包含 Root 与 KPM 后端。请保存原厂镜像。")
        OutlinedButton(enabled = !busy, onClick = { chooseBoot.launch(arrayOf("*/*")) }) { Text("选择 boot.img") }
        selected?.let { Text("已选择 ${it.lastPathSegment}") }
        Button(enabled = !busy && selected != null && key.length >= 16, onClick = { runOperation {
            val directory = File(context.filesDir, "patches/${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
            val input = File(directory, "original-boot.img")
            context.contentResolver.openInputStream(selected!!)!!.use { stream -> input.outputStream().use { stream.copyTo(it) } }
            output = KernelPatchBackend.patch(context, input, key, directory)
            "修补成功。请导出镜像。原镜像备份位于 ${directory.name}/original-boot.img"
        } }) { Text("修补 boot（Root + KPM）") }
        OutlinedButton(enabled = !busy && output != null, onClick = { saveBoot.launch("Nova-KernelPatch-boot.img") }) { Text("导出修补镜像") }
        OutlinedButton(enabled = !busy && ready && key.length >= 16, onClick = { confirmFlash = true }) { Text("提取并修补当前槽位 boot 后刷入") }
        HorizontalDivider()
        Text("Root 授权", style = MaterialTheme.typography.titleLarge)
        Text("按应用 UID 管理当前内核授权。此版本重启后需连接管理器并重新授权。")
        OutlinedButton(enabled = !busy, onClick = {
            runOperation {
                apps = context.packageManager.getInstalledApplications(0).map { app ->
                    Triple(context.packageManager.getApplicationLabel(app).toString(), app.packageName, app.uid)
                }.sortedBy { it.first.lowercase() }
                showApps = true
                "已读取当前用户应用列表"
            }
        }) { Text("选择应用") }
        OutlinedTextField(uid, { uid = it }, label = { Text("应用 UID") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && ready, onClick = { runOperation { KernelPatchBackend.grant(uid.toInt(), true); "已授权 UID $uid" } }) { Text("授权") }
            OutlinedButton(enabled = !busy && ready, onClick = { runOperation { KernelPatchBackend.grant(uid.toInt(), false); "已撤销 UID $uid" } }) { Text("撤销") }
        }
        OutlinedButton(enabled = !busy && ready, onClick = { runOperation { "已授权 UID ${Natives.suUids().joinToString()}" } }) { Text("读取内核授权列表") }
        HorizontalDivider()
        Text("KPM 管理", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(module, { module = it }, label = { Text("完整模块名称") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(args, { args = it }, label = { Text("模块参数（可选）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && ready, onClick = { chooseKpm.launch(arrayOf("*/*")) }) { Text("选择并加载 KPM") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && ready, onClick = { runOperation { modules = KernelPatchBackend.modules(); "已读取 KPM 列表" } }) { Text("刷新列表") }
            OutlinedButton(enabled = !busy && ready && module.isNotBlank(), onClick = { runOperation { KernelPatchBackend.unload(module); modules = KernelPatchBackend.modules(); "KPM 已卸载" } }) { Text("卸载") }
        }
        OutlinedButton(enabled = !busy && ready && module.isNotBlank(), onClick = { runOperation { KernelPatchBackend.control(module, args) } }) { Text("发送控制参数") }
        Text(modules.ifEmpty { "暂无已读取的模块" })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
    if (showApps) AlertDialog(
        onDismissRequest = { showApps = false },
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(appQuery, { appQuery = it }, label = { Text("名称或包名") })
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps.filter { it.first.contains(appQuery, true) || it.second.contains(appQuery, true) }) { app ->
                        TextButton(onClick = {
                            uid = app.third.toString()
                            message = "已选择 ${app.first}（${app.second}），UID ${app.third}"
                            showApps = false
                        }) { Text("${app.first}\n${app.second} · UID ${app.third}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showApps = false }) { Text("关闭") } },
    )
    if (confirmFlash) AlertDialog(onDismissRequest = { confirmFlash = false }, title = { Text("确认写入当前槽位 boot") }, text = { Text("将备份当前 boot，修补其内核并写回当前槽位。失败可能导致无法开机。需要已验证的 KernelPatch Root，请预先准备可用的原厂镜像与恢复方式。") }, confirmButton = {
        TextButton(onClick = {
            confirmFlash = false
            runOperation {
                KernelPatchBackend.rootShell().use { shell ->
                    val partition = KernelPatchBackend.currentBoot(shell)
                    val directory = File(context.filesDir, "patches/${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
                    val input = File(directory, "original-boot.img")
                    check(shell.newJob().add("dd if=$partition of=${input.absolutePath} bs=4096").exec().isSuccess) { "备份 boot 失败" }
                    val originalHash = KernelPatchBackend.hash(input)
                    val patched = KernelPatchBackend.patch(context, input, key, directory)
                    check(patched.length() == input.length()) { "镜像长度改变，停止写入" }
                    val currentHash = shell.newJob().add("sha256sum $partition").exec()
                    check(currentHash.isSuccess && currentHash.out.firstOrNull()?.startsWith(originalHash) == true) { "当前 boot 已变化，停止写入" }
                    check(shell.newJob().add("dd if=${patched.absolutePath} of=$partition bs=4096 conv=fsync").exec().isSuccess) { "写入失败，备份保存在 ${input.absolutePath}" }
                    val verify = shell.newJob().add("sha256sum $partition").exec()
                    check(verify.isSuccess && verify.out.firstOrNull()?.startsWith(KernelPatchBackend.hash(patched)) == true) { "写入校验失败，备份保存在 ${input.absolutePath}" }
                    output = patched
                    "已写入 $partition 并校验。原镜像备份 ${input.absolutePath}。请手动重启。"
                }
            }
        }) { Text("确认修补并刷入") }
    }, dismissButton = { TextButton(onClick = { confirmFlash = false }) { Text("取消") } })
}
