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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var passwordMode by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }

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
            "已导出与本管理器配对的 AnyKernel3 包"
        }
    }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val archive = pendingBackup
        pendingBackup = null
        if (uri != null && archive != null) operation {
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(archive) }
            "加密配对备份已导出，请保存备份密码"
        }
    }
    val chooseBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri
            passwordMode = "restore"
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("安装与配对") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SukiSU Ultra · 调试版本", style = MaterialTheme.typography.titleLarge)
                    Text(if (ready) "已自动连接 ${PairingSession.versionText()}" else "尚未安装本管理器配对的内核")
                    Text("管理凭证自动生成并由 Android Keystore 加密保存，正常修补和授权无需输入密钥。")
                }
            }
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("修补 boot", style = MaterialTheme.typography.titleLarge)
                    Text("选择原厂 boot，修补内核二进制，同时安装 Root 与 KPM 后端。")
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
                            patched = BootPatcher.patch(context, original, directory)
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
                    Text("已有本管理器配对的 KernelPatch Root 时，可备份并修补当前槽位 boot 后写回。")
                    Button(enabled = !busy && ready, onClick = { confirmFlash = true }) { Text("修补并刷入当前槽位") }
                }
            }
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("配对备份", style = MaterialTheme.typography.titleLarge)
                    Text("卸载或清除数据会丢失本机配对。请导出加密备份，恢复时才需要备份密码。")
                    OutlinedButton(enabled = !busy, onClick = { passwordMode = "backup" }) { Text("备份配对") }
                    OutlinedButton(enabled = !busy, onClick = { chooseBackup.launch(arrayOf("*/*")) }) { Text("恢复配对") }
                }
            }
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
                    patched = BootPatcher.flashCurrent(context)
                    "写入和读回校验完成。备份位于 ${patched!!.parent}/original-boot.img，请手动重启。"
                }
            }) { Text("确认刷入") }
        },
        dismissButton = { TextButton(onClick = { confirmFlash = false }) { Text("取消") } },
    )
    if (passwordMode != null) AlertDialog(
        onDismissRequest = { passwordMode = null; password = "" },
        title = { Text(if (passwordMode == "backup") "加密配对备份" else "恢复配对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (passwordMode == "backup") "设置至少 12 字符的备份密码。此密码只用于备份恢复。"
                    else "输入备份密码。确认后会替换本机保存的配对数据并恢复有效的应用授权。")
                OutlinedTextField(password, { password = it }, label = { Text("备份密码") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = password.length >= 12, onClick = {
                val mode = passwordMode
                val secret = password.toCharArray()
                password = ""
                passwordMode = null
                if (mode == "backup") {
                    scope.launch {
                        busy = true
                        try {
                            pendingBackup = withContext(Dispatchers.IO) { PairingSession.backup(secret) }
                            saveBackup.launch("Ultra-Pairing.ukpair")
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            message = failure.message ?: "备份失败"
                        } finally {
                            secret.fill('\u0000')
                            busy = false
                        }
                    }
                } else {
                    val uri = importUri!!
                    operation {
                        try {
                            val archive = context.contentResolver.openInputStream(uri)!!.use {
                                PairingArchive.read(it)
                            }
                            BootPatcher.restorePairing(archive, secret)
                            "配对已恢复。${if (PairingSession.ready) "已连接内核" else "请使用本配对重新修补 boot"}"
                        } finally { secret.fill('\u0000') }
                    }
                }
            }) { Text(if (passwordMode == "backup") "加密并导出" else "确认恢复") }
        },
        dismissButton = { TextButton(onClick = { passwordMode = null; password = "" }) { Text("取消") } },
    )
}
