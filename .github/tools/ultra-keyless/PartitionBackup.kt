package com.sukisu.ultra.backend

import com.topjohnwu.superuser.Shell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies the current boot and init_boot partitions to
 * /sdcard/Download/西努鲁/<timestamp>/ before anything is written.
 * The shell aborts on the first failure, so a missing backup also stops the flash.
 */
object PartitionBackup {
    fun slot(shell: Shell): String {
        val result = shell.newJob().add("getprop ro.boot.slot_suffix").exec()
        check(result.isSuccess) { "读取槽位失败" }
        val slot = result.out.joinToString("").trim()
        require(slot in listOf("", "_a", "_b")) { "槽位不受支持" }
        return slot
    }

    @Synchronized
    fun backup(shell: Shell, slot: String, reason: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val suffix = java.util.UUID.randomUUID().toString().take(4)
        val directory = "/sdcard/Download/西努鲁/$stamp-$suffix"
        val model = "PD2339"
        val script = """
            set -e
            backup='$directory'
            mkdir -p "${'$'}backup"
            printf 'reason=%s\nslot=%s\ndevice=%s\ndate=%s\n' '$reason' '$slot' '$model' '$stamp' > "${'$'}backup/info.txt"
            for name in boot init_boot; do
              part="/dev/block/by-name/${'$'}{name}${'$'}{slot}"
              [ -b "${'$'}part" ] || { echo "missing ${'$'}part" >&2; exit 1; }
              dd if="${'$'}part" of="${'$'}backup/${'$'}{name}.img" bs=1048576
              set -- $(sha256sum "${'$'}part")
              before=${'$'}1
              set -- $(sha256sum "${'$'}backup/${'$'}{name}.img")
              after=${'$'}1
              [ "${'$'}before" = "${'$'}after" ] || { echo "verify ${'$'}{name} failed" >&2; exit 2; }
              size=${'$'}(wc -c < "${'$'}backup/${'$'}{name}.img")
              printf '%s %s %s\n' "${'$'}name" "${'$'}before" "${'$'}size" >> "${'$'}backup/info.txt"
            done
            sync
            echo "$directory"
        """.trimIndent()
        val result = shell.newJob().add(script).exec()
        check(result.isSuccess) { "备份原厂 boot/init_boot 失败 (${result.code})，已停止写入" }
        val path = result.out.lastOrNull { it.trim().startsWith("/sdcard/") }?.trim() ?: directory
        check(result.out.none { it.contains("verify") && it.contains("failed") }) { "备份读回校验失败，已停止写入" }
        return path
    }

    @Synchronized
    fun flash(shell: Shell, slot: String, partitionName: String, image: java.io.File): String {
        val backup = backup(shell, slot, "写入 $partitionName 前备份")
        val partition = "/dev/block/by-name/$partitionName$slot"
        val expected = BootPatcher.hash(image)
        val length = image.length()
        val sizeResult = shell.newJob().add("blockdev --getsize64 $partition 2>/dev/null").exec()
        val partitionSize = sizeResult.out.firstOrNull()?.trim()?.toLongOrNull()
        check(partitionSize == null || length <= partitionSize) { "镜像大于 $partitionName 分区，停止写入" }
        check(shell.newJob().add("dd if=${image.absolutePath} of=$partition bs=4096 conv=fsync").exec().isSuccess) {
            "写入失败，原厂备份位于手机 $backup"
        }
        val verify = shell.newJob().add("head -c $length $partition | sha256sum").exec()
        check(verify.isSuccess && verify.out.firstOrNull()?.startsWith(expected) == true) {
            "写入读回校验失败，原厂备份位于手机 $backup"
        }
        return backup
    }
}
