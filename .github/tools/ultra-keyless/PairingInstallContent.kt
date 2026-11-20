package com.sukisu.ultra.backend

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingInstallContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(PairingSession.error.orEmpty()) }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var patched by remember { mutableStateOf<File?>(null) }
    var ready by remember { mutableStateOf(PairingSession.ready) }
    var confirmFlash by remember { mutableStateOf(false) }
    var vivoBlock by remember { mutableStateOf(true) }

    fun operation(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { action() }
                ready = PairingSession.ready
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    val chooseBoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        selected = it
        patched = null
    }
    val saveBoot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val image = patched
        if (uri != null && image != null) operation {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                image.inputStream().use { it.copyTo(output) }
            }
            "已导出 boot，SHA-256 ${BootPatcher.hash(image)}"
        }
    }
    val saveInstaller = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val image = patched
        if (uri != null && image != null) operation {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                BootPatcher.exportInstaller(context, image, output)
            }
            "已导出配套 AnyKernel3 包"
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("安装与修补") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SukiSU Ultra · 调试版本", style = MaterialTheme.typography.titleLarge)
                    Text(if (ready) "已自动连接 ${PairingSession.versionText()}" else "未识别到支持此管理器的内核补丁")
                    Text("无需 SuperKey 或本机配对。内核自动识别此管理器，提供 Root 授权与 KPM 管理。")
                }
            }
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("修补 boot", style = MaterialTheme.typography.titleLarge)
                    Text("选择原厂 boot，修补内核二进制，同时安装 Root 与 KPM 后端。")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = vivoBlock, onCheckedChange = { vivoBlock = it }, enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        Text("vivo 反 SU（启动时阻止 vr.ko 加载）")
                    }
                    OutlinedButton(enabled = !busy, onClick = { chooseBoot.launch(arrayOf("*/*")) }) { Text("选择 boot.img") }
                    if (selected != null) Text("已选择 ${selected!!.lastPathSegment}")
                    Button(enabled = !busy && selected != null, onClick = {
                        val source = selected!!
                        patched = null
                        operation {
                            PairingSession.initialize(context)
                            val directory = File(context.filesDir, "patches/${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
                            val original = File(directory, "original-boot.img")
                            context.contentResolver.openInputStream(source)!!.use { input ->
                                original.outputStream().use { input.copyTo(it) }
                            }
                            patched = BootPatcher.patch(context, original, directory, vivoBlock)
                            "修补成功，可导出镜像或配套刷入包。请保留原厂 boot。"
                        }
                    }) { Text("修补 boot（Root + KPM）") }
                    OutlinedButton(enabled = !busy && patched != null, onClick = { saveBoot.launch("Ultra-Paired-boot.img") }) {
                        Text("导出修补镜像")
                    }
                    OutlinedButton(enabled = !busy && patched != null, onClick = { saveInstaller.launch("Ultra-Paired-AnyKernel3.zip") }) {
                        Text("导出配套 AnyKernel3 刷入包")
                    }
                    Text("配套包包含已修补镜像及来源摘要，安装时核对当前 boot，无需输入密钥。")
                }
            }
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("直接刷入", style = MaterialTheme.typography.titleLarge)
                    Text("已识别到此管理器的 Root 时，可备份并修补当前槽位 boot 后写回。")
                    Button(enabled = !busy && ready, onClick = { confirmFlash = true }) { Text("修补并刷入当前槽位") }
                }
            }
            InitBootCard()
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message)
        }
    }
    if (confirmFlash) AlertDialog(
        onDismissRequest = { confirmFlash = false },
        title = { Text("确认写入当前槽位 boot") },
        text = { Text("将备份、修补并写入当前槽位 boot。失败可能无法开机，请先保存可用原厂镜像并准备恢复方式。") },
        confirmButton = {
            TextButton(onClick = {
                confirmFlash = false
                patched = null
                operation {
                    val result = BootPatcher.flashCurrent(context, vivoBlock)
                    patched = result.image
                    "写入和读回校验完成。原厂 boot/init_boot 备份位于手机 ${result.backup}，请手动重启。"
                }
            }) { Text("确认刷入") }
        },
        dismissButton = { TextButton(onClick = { confirmFlash = false }) { Text("取消") } },
    )
}
