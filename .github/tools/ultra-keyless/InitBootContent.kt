package com.sukisu.ultra.backend

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InitBootCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<File?>(null) }
    var inspection by remember { mutableStateOf<InitBootTool.Inspection?>(null) }
    var workDir by remember { mutableStateOf<File?>(null) }
    var stripped by remember { mutableStateOf<File?>(null) }
    var confirmFlash by remember { mutableStateOf(false) }

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

    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        operation {
            val directory = File(context.filesDir, "initboot/${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
            val image = File(directory, "source-init_boot.img")
            context.contentResolver.openInputStream(uri)!!.use { input -> image.outputStream().use { input.copyTo(it) } }
            workDir = directory
            selected = image
            stripped = null
            inspection = InitBootTool.inspect(context, image, directory)
            val info = inspection!!
            "init_boot: ramdisk=${info.hasRamdisk}, init=${info.hasInit}, init.real=${info.hasInitReal}, " +
                "kernelsu=${info.hasKernelsu}, wrapper=${info.initIsWrapper}"
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val image = stripped
        if (uri != null && image != null) operation {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                image.inputStream().use { it.copyTo(output) }
            }
            "已导出移除 KernelSU 的 init_boot，SHA-256 ${BootPatcher.hash(image)}"
        }
    }

    fun ensureStripped(): File {
        stripped?.let { return it }
        val image = selected ?: error("请先选择 init_boot.img")
        val directory = workDir ?: error("临时目录不可用")
        val output = InitBootTool.removeKernelSu(context, image, directory)
        stripped = output
        return output
    }

    LiquidGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("init_boot 独立处理", style = MaterialTheme.typography.titleLarge)
            Text("单独分析、移除旧 KernelSU 并刷入 init_boot。Root 与 KPM 仍由已修补的 boot 提供。")
            OutlinedButton(enabled = !busy, onClick = { choose.launch(arrayOf("*/*")) }) { Text("选择 init_boot.img") }
            inspection?.let { info ->
                Text("ramdisk: ${if (info.hasRamdisk) "有" else "无"}；init: ${if (info.hasInit) "有" else "无"}；" +
                    "init.real: ${if (info.hasInitReal) "有" else "无"}；kernelsu.ko: ${if (info.hasKernelsu) "有" else "无"}")
            }
            val canStrip = inspection?.let { it.hasKernelsu || it.initIsWrapper } == true
            OutlinedButton(enabled = !busy && canStrip, onClick = {
                operation {
                    val output = ensureStripped()
                    "已移除旧 KernelSU，可导出或刷入。SHA-256 ${BootPatcher.hash(output)}"
                }
            }) { Text("移除旧 KernelSU") }
            OutlinedButton(enabled = !busy && stripped != null, onClick = { save.launch("init_boot-no-ksu.img") }) {
                Text("导出已处理镜像")
            }
            Button(enabled = !busy && selected != null, onClick = { confirmFlash = true }) {
                Text("刷入当前槽位 init_boot（先备份）")
            }
            Text("刷入前会把当前 boot 和 init_boot 备份到手机 Download/西努鲁/时间目录/。")
        }
    }
    if (confirmFlash) AlertDialog(
        onDismissRequest = { confirmFlash = false },
        title = { Text("确认写入 init_boot") },
        text = { Text(if (inspection?.let { it.hasKernelsu || it.initIsWrapper } == true)
            "将先移除旧 KernelSU，再备份并写入当前槽位 init_boot。写入失败可能无法开机，请保留备份。"
        else "将备份并写入当前槽位 init_boot。写入失败可能无法开机，请保留备份。") },
        confirmButton = {
            TextButton(onClick = {
                confirmFlash = false
                operation {
                    val image = if (inspection?.let { it.hasKernelsu || it.initIsWrapper } == true) ensureStripped()
                        else selected ?: error("请先选择 init_boot.img")
                    val backup = PairingSession.rootShell(fresh = true).use { shell ->
                        val slot = PartitionBackup.slot(shell)
                        PartitionBackup.flash(shell, slot, "init_boot", image)
                    }
                    "init_boot 写入和读回校验完成。原厂备份位于手机 $backup"
                }
            }) { Text("确认刷入") }
        },
        dismissButton = { TextButton(onClick = { confirmFlash = false }) { Text("取消") } },
    )
}
