package com.sukisu.ultra.backend

import android.content.Context
import java.io.File
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object BootPatcher {
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

    private fun validate(file: File) {
        DataInputStream(file.inputStream()).use { input ->
            val magic = ByteArray(8)
            input.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "ANDROID!") { "请选择 Android boot 镜像" }
            val kernelSize = Integer.reverseBytes(input.readInt())
            require(kernelSize > 0) { "镜像没有内核，请选择 boot.img" }
        }
    }

    @Synchronized
    fun patch(context: Context, original: File, directory: File, vivoBlock: Boolean = true): File {
        validate(original)
        val before = hash(original)
        val core = File(directory, "kpimg")
        context.assets.open("pairing/kpimg").use { stream -> core.outputStream().use { stream.copyTo(it) } }
        val output = File(directory, "patched-boot.img")
        val log = File(directory, "patch.log")
        val command = mutableListOf(
            File(context.applicationInfo.nativeLibraryDir, "libkptools.so").absolutePath,
            "-p", "-i", original.absolutePath, "-k", core.absolutePath, "-o", output.absolutePath,
        )
        if (vivoBlock) {
            val kpm = File(directory, "vr_module_blocker.kpm")
            context.assets.open("pairing/vr_module_blocker.kpm").use { stream ->
                kpm.outputStream().use { stream.copyTo(it) }
            }
            // Do not pass -N here: it aliases config->path and makes kptools read the name as a file.
            command += listOf("-M", kpm.absolutePath, "-T", "kpm", "-V", "pre-kernel-init")
        }
        val process = ProcessBuilder(command)
            .directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        process.outputStream.close()
        val rc = process.waitFor()
        if (rc != 0 || !output.isFile) {
            val tail = runCatching { log.readLines().takeLast(12).joinToString("\n") }.getOrDefault("")
            val stamp = DownloadStore.stamp()
            val saved = DownloadStore.save(context, log, "patch-failed-$stamp.log", stamp)
            error("修补失败 ($rc)。" + (saved?.let { "日志已保存到手机 $it" } ?: "日志：${log.absolutePath}") + "\n$tail")
        }
        check(hash(original) == before) { "原镜像校验失败" }
        validate(output)
        check(output.length() in 4096L..(original.length() + 1_048_576L)) { "修补后镜像长度异常，停止刷入" }
        val meta = JSONObject().put("sourceSha256", before).put("patchedSha256", hash(output))
            .put("vivoBlock", vivoBlock)
        File(directory, "metadata.json").writeText(meta.toString())
        return output
    }

    @Synchronized
    fun exportInstaller(context: Context, patched: File, destination: java.io.OutputStream) {
        val metadata = JSONObject(File(patched.parentFile, "metadata.json").readText())
        check(hash(patched) == metadata.getString("patchedSha256")) { "修补镜像摘要已变化" }
        ZipOutputStream(destination.buffered()).use { output ->
            ZipInputStream(context.assets.open("pairing/AnyKernel3-template.zip")).use { template ->
                while (true) {
                    val entry = template.nextEntry ?: break
                    require(entry.name !in setOf("paired-boot.img", "source-sha256.txt", "patched-sha256.txt"))
                    output.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) template.copyTo(output)
                    output.closeEntry()
                }
            }
            for ((name, value) in listOf(
                "source-sha256.txt" to metadata.getString("sourceSha256"),
                "patched-sha256.txt" to metadata.getString("patchedSha256"),
            )) {
                output.putNextEntry(ZipEntry(name))
                output.write((value + "\n").toByteArray())
                output.closeEntry()
            }
            output.putNextEntry(ZipEntry("paired-boot.img"))
            patched.inputStream().use { it.copyTo(output) }
            output.closeEntry()
        }
    }

    data class FlashResult(val image: File, val backup: String)

    @Synchronized
    fun flashCurrent(context: Context, vivoBlock: Boolean = true): FlashResult = RootAccess.acquire(fresh = true).shell.use { shell ->
        val slot = PartitionBackup.slot(shell)
        val partition = "/dev/block/by-name/boot$slot"
        val directory = File(context.filesDir, "patches/${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val original = File(directory, "original-boot.img")
        val backup = PartitionBackup.backup(shell, slot, "写入 boot 前备份")
        check(shell.newJob().add("dd if=$partition of=${original.absolutePath} bs=4096").exec().isSuccess) { "备份当前 boot 失败" }
        val originalHash = hash(original)
        val patched = patch(context, original, directory, vivoBlock)
        val unchanged = shell.newJob().add("sha256sum $partition").exec()
        check(unchanged.isSuccess && unchanged.out.firstOrNull()?.startsWith(originalHash) == true) { "当前 boot 已变化，停止刷入" }
        val sizeResult = shell.newJob().add("blockdev --getsize64 $partition 2>/dev/null").exec()
        val partitionSize = sizeResult.out.firstOrNull()?.trim()?.toLongOrNull()
        check(partitionSize == null || patched.length() <= partitionSize) { "修补镜像大于 boot 分区，停止刷入" }
        check(shell.newJob().add("dd if=${patched.absolutePath} of=$partition bs=4096 conv=fsync").exec().isSuccess) {
            "写入失败，原厂备份位于手机 $backup"
        }
        val verify = shell.newJob().add("head -c ${patched.length()} $partition | sha256sum").exec()
        check(verify.isSuccess && verify.out.firstOrNull()?.startsWith(hash(patched)) == true) {
            "写入读回校验失败，原厂备份位于手机 $backup"
        }
        FlashResult(patched, backup)
    }
}
