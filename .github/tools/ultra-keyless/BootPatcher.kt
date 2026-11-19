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
    fun patch(context: Context, original: File, directory: File): File {
        validate(original)
        val key = PairingSession.patchCredential()
        val before = hash(original)
        val core = File(directory, "kpimg")
        context.assets.open("pairing/kpimg").use { stream -> core.outputStream().use { stream.copyTo(it) } }
        val output = File(directory, "patched-boot.img")
        val log = File(directory, "patch.log")
        val process = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libkptools.so").absolutePath,
            "-p", "-i", original.absolutePath, "-k", core.absolutePath,
            "--root-skey-fd", "0", "-o", output.absolutePath)
            .directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        process.outputStream.bufferedWriter().use { it.write(key); it.newLine() }
        val rc = process.waitFor()
        check(rc == 0 && output.isFile) { "修补失败 ($rc)，请导出日志检查" }
        check(hash(original) == before) { "原镜像校验失败" }
        validate(output)
        check(output.length() == original.length()) { "修补后镜像长度变化，停止刷入" }
        val meta = JSONObject().put("pairingFingerprint", PairingSession.fingerprint()).put("sourceSha256", before).put("patchedSha256", hash(output))
        File(directory, "metadata.json").writeText(meta.toString())
        return output
    }

    @Synchronized
    fun exportInstaller(context: Context, patched: File, destination: java.io.OutputStream) {
        val metadata = JSONObject(File(patched.parentFile, "metadata.json").readText())
        check(metadata.getString("pairingFingerprint") == PairingSession.fingerprint()) { "配对已变化，请重新修补 boot" }
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

    @Synchronized
    fun restorePairing(archive: ByteArray, password: CharArray) = PairingSession.restore(archive, password)

    @Synchronized
    fun flashCurrent(context: Context): File = PairingSession.rootShell(fresh = true).use { shell ->
        val slotResult = shell.newJob().add("getprop ro.boot.slot_suffix").exec()
        check(slotResult.isSuccess) { "读取槽位失败" }
        val slot = slotResult.out.joinToString("").trim()
        require(slot in listOf("", "_a", "_b")) { "槽位不受支持" }
        val partition = "/dev/block/by-name/boot$slot"
        val directory = File(context.filesDir, "patches/${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val original = File(directory, "original-boot.img")
        check(shell.newJob().add("dd if=$partition of=${original.absolutePath} bs=4096").exec().isSuccess) { "备份当前 boot 失败" }
        val originalHash = hash(original)
        val patched = patch(context, original, directory)
        val unchanged = shell.newJob().add("sha256sum $partition").exec()
        check(unchanged.isSuccess && unchanged.out.firstOrNull()?.startsWith(originalHash) == true) { "当前 boot 已变化，停止刷入" }
        check(shell.newJob().add("dd if=${patched.absolutePath} of=$partition bs=4096 conv=fsync").exec().isSuccess) {
            "写入失败，备份位于 ${original.absolutePath}"
        }
        val verify = shell.newJob().add("sha256sum $partition").exec()
        check(verify.isSuccess && verify.out.firstOrNull()?.startsWith(hash(patched)) == true) {
            "写入读回校验失败，备份位于 ${original.absolutePath}"
        }
        patched
    }
}
