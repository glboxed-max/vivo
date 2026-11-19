package com.sukisu.nova.backend

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import java.io.File
import java.security.MessageDigest
import me.bmax.apatch.BackendSession
import me.bmax.apatch.Natives

object KernelPatchBackend {
    val ready: Boolean get() = BackendSession.superKey.isNotEmpty()

    fun authenticate(key: String) {
        check(BackendSession.authenticate(key)) { "SuperKey 验证失败，或内核尚未安装 KernelPatch" }
    }

    fun version(): String {
        check(ready) { "请先验证 SuperKey" }
        val value = Natives.kernelPatchVersion()
        check(value > 0) { "读取 KernelPatch 版本失败 ($value)" }
        return "${value shr 16}.${(value shr 8) and 255}.${value and 255}"
    }

    fun grant(uid: Int, allowed: Boolean) {
        check(ready) { "请先验证 SuperKey" }
        require(uid >= 0) { "UID 无效" }
        val rc = if (allowed) Natives.grantSu(uid, 0, "u:r:su:s0") else Natives.revokeSu(uid)
        check(rc == 0L) { "更新授权失败 ($rc)" }
        check(Natives.suUids().contains(uid) == allowed) { "内核授权读回结果不一致" }
    }

    fun modules(): String {
        check(ready) { "请先验证 SuperKey" }
        val count = Natives.kernelPatchModuleNum()
        check(count >= 0) { "读取 KPM 数量失败 ($count)" }
        return Natives.kernelPatchModuleList()
    }

    fun load(context: Context, uri: Uri, args: String) {
        check(ready) { "请先验证 SuperKey" }
        val local = File.createTempFile("module-", ".kpm", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> local.outputStream().use { input.copyTo(it) } }
            val rc = Natives.loadKernelPatchModule(local.absolutePath, args)
            check(rc == 0L) { "KPM 加载失败 ($rc)" }
        } finally { local.delete() }
    }

    fun unload(name: String) {
        check(ready) { "请先验证 SuperKey" }
        require(name.isNotBlank()) { "请输入完整模块名称" }
        val rc = Natives.unloadKernelPatchModule(name)
        check(rc == 0L) { "KPM 卸载失败 ($rc)" }
    }

    fun control(name: String, args: String): String {
        check(ready) { "请先验证 SuperKey" }
        val result = Natives.kernelPatchModuleControl(name, args)
        check(result.rc >= 0) { "KPM 控制失败 (${result.rc})" }
        return result.outMsg.orEmpty()
    }

    fun rootShell(): Shell {
        check(ready && Natives.su()) { "无法取得 KernelPatch Root" }
        val shell = Shell.Builder.create().build("/system/bin/sh")
        check(shell.isRoot) { "Root shell 验证失败" }
        return shell
    }

    fun currentBoot(shell: Shell): String {
        val result = shell.newJob().add("getprop ro.boot.slot_suffix").exec()
        check(result.isSuccess) { "读取槽位失败" }
        val slot = result.out.joinToString("").trim()
        require(slot in listOf("", "_a", "_b")) { "槽位无效" }
        return "/dev/block/by-name/boot$slot"
    }

    fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun patch(context: Context, input: File, key: String, directory: File): File {
        require(key.length >= 16 && key.none { it == '\n' || it == '\r' || it == '\u0000' }) { "SuperKey 至少 16 个字符，不能包含换行或空字符" }
        input.inputStream().use { require(String(it.readNBytes(8), Charsets.US_ASCII) == "ANDROID!") { "请选择 boot.img" } }
        val core = File(directory, "kpimg")
        context.assets.open("apatch/kpimg").use { stream -> core.outputStream().use { stream.copyTo(it) } }
        val output = File(directory, "patched-boot.img")
        val tool = File(context.applicationInfo.nativeLibraryDir, "libkptools.so")
        val log = File(directory, "patch.log")
        val before = hash(input)
        val process = ProcessBuilder(tool.absolutePath, "-p", "-i", input.absolutePath,
            "-k", core.absolutePath, "--root-skey-fd", "0", "-o", output.absolutePath)
            .directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        process.outputStream.bufferedWriter().use { it.write(key); it.newLine() }
        val rc = process.waitFor()
        check(rc == 0 && output.isFile && output.length() > 4096) { "修补失败 ($rc)，日志保存在 ${log.name}" }
        check(hash(input) == before) { "输入镜像摘要发生变化" }
        output.inputStream().use { check(String(it.readNBytes(8), Charsets.US_ASCII) == "ANDROID!") { "输出镜像头无效" } }
        return output
    }
}
