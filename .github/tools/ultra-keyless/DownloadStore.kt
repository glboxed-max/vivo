package com.sukisu.ultra.backend

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.topjohnwu.superuser.Shell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes exported images to /sdcard/Download/西努鲁/<timestamp>/.
 * MediaStore needs no permission on Android 10+; older releases use the public dir.
 * Partition backups additionally require a working root shell.
 */
object DownloadStore {
    private const val FOLDER = "西努鲁"

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
        "-" + java.util.UUID.randomUUID().toString().take(4)

    fun save(context: Context, source: File, name: String, stamp: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(context, source, name, stamp)
        else saveLegacy(source, name, stamp)
    }.getOrNull()

    private fun saveMediaStore(context: Context, source: File, name: String, stamp: String): String {
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$stamp"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在 Download 下创建文件")
        try {
            resolver.openOutputStream(uri, "w")!!.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
        return "/sdcard/Download/$FOLDER/$stamp/$name"
    }

    private fun saveLegacy(source: File, name: String, stamp: String): String {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(public, "$FOLDER/$stamp")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建 Download 目录" }
        val target = File(directory, name)
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.absolutePath
    }

    /** Backs up the live boot and init_boot when a root shell is available. */
    fun backupLivePartitions(): String? = runCatching {
        val shell = if (PairingSession.ready) PairingSession.rootShell(fresh = true) else Shell.getShell()
        if (!shell.isRoot) return null
        val slot = PartitionBackup.slot(shell)
        PartitionBackup.backup(shell, slot, "修补 boot 前自动备份")
    }.getOrNull()
}
