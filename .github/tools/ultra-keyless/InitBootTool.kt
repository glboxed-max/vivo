package com.sukisu.ultra.backend

import android.content.Context
import java.io.DataInputStream
import java.io.File

/**
 * Independent init_boot maintenance: inspect the ramdisk, optionally remove an
 * older KernelSU wrapper and flash the result to the current slot. Root and KPM
 * come from the patched boot image; this flow never installs a second root provider.
 */
object InitBootTool {
    data class Inspection(
        val hasRamdisk: Boolean,
        val hasInit: Boolean,
        val hasInitReal: Boolean,
        val hasKernelsu: Boolean,
        val initIsWrapper: Boolean,
        val log: String,
    )

    private fun tool(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, "libmagiskboot.so")

    private fun run(directory: File, vararg arguments: String): Pair<Int, String> {
        val log = File(directory, "magiskboot.log")
        val process = ProcessBuilder(arguments.toList())
            .directory(directory).redirectErrorStream(true).redirectOutput(log).start()
        process.outputStream.close()
        val rc = process.waitFor()
        return rc to log.takeIf { it.isFile }?.readText().orEmpty()
    }

    private fun copyToWork(context: Context, source: File, directory: File): File {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建临时目录" }
        val image = File(directory, "init_boot.img")
        source.inputStream().use { input -> image.outputStream().use { input.copyTo(it) } }
        DataInputStream(image.inputStream()).use { stream ->
            val magic = ByteArray(8)
            stream.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "ANDROID!") { "请选择 Android init_boot 镜像" }
            val kernelSize = Integer.reverseBytes(stream.readInt())
            require(kernelSize == 0) { "这是含内核的 boot.img，不是 init_boot" }
        }
        return image
    }

    @Synchronized
    fun inspect(context: Context, source: File, directory: File): Inspection {
        val image = copyToWork(context, source, directory)
        val unpack = run(directory, tool(context).absolutePath, "unpack", image.name)
        val cpio = File(directory, "ramdisk.cpio")
        if (unpack.first != 0 || !cpio.isFile) {
            return Inspection(false, false, false, false, false, unpack.second)
        }
        fun exists(name: String) =
            run(directory, tool(context).absolutePath, "cpio", cpio.name, "exists $name").first == 0
        val hasInit = exists("init")
        val hasInitReal = exists("init.real")
        val hasKernelsu = exists("kernelsu.ko")
        var wrapper = false
        if (hasInit) {
            run(directory, tool(context).absolutePath, "cpio", cpio.name, "extract init init.extracted")
            val init = File(directory, "init.extracted")
            if (init.isFile) {
                val bytes = init.readBytes()
                wrapper = bytes.toString(Charsets.ISO_8859_1).contains("kernelsu", ignoreCase = true)
            }
        }
        return Inspection(true, hasInit, hasInitReal, hasKernelsu, wrapper, unpack.second)
    }

    @Synchronized
    fun removeKernelSu(context: Context, source: File, directory: File): File {
        val inspection = inspect(context, source, directory)
        check(inspection.hasRamdisk) { "init_boot 没有可编辑的 ramdisk" }
        check(inspection.hasInitReal) { "未找到 init.real，无法安全移除旧 KernelSU" }
        check(inspection.hasKernelsu || inspection.initIsWrapper) { "未检测到旧 KernelSU" }
        val cpio = File(directory, "ramdisk.cpio")
        val commands = mutableListOf(tool(context).absolutePath, "cpio", cpio.name)
        if (inspection.hasInit) commands += "rm init"
        commands += "mv init.real init"
        if (inspection.hasKernelsu) commands += "rm kernelsu.ko"
        val edit = run(directory, *commands.toTypedArray())
        check(edit.first == 0) { "修改 ramdisk 失败 (${edit.first})\n${edit.second}" }
        val repack = run(directory, tool(context).absolutePath, "repack", "init_boot.img", "init_boot-stripped.img")
        check(repack.first == 0) { "重打包 init_boot 失败 (${repack.first})\n${repack.second}" }
        val output = File(directory, "init_boot-stripped.img")
        check(output.isFile && output.length() > 4096) { "输出 init_boot 无效" }
        DataInputStream(output.inputStream()).use { stream ->
            val magic = ByteArray(8)
            stream.readFully(magic)
            check(String(magic, Charsets.US_ASCII) == "ANDROID!") { "输出镜像头无效" }
        }
        return output
    }
}
