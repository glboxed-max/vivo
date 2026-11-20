package com.sukisu.ultra.backend

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.topjohnwu.superuser.Shell
import org.json.JSONObject

object ModuleRuntime {
    const val daemonPath = "/data/adb/apd"
    var installed by mutableStateOf(false)
        private set

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun expected(context: Context): JSONObject = context.assets.open("modules/runtime-info.json").use {
        JSONObject(String(it.readBytes(), Charsets.UTF_8))
    }

    fun probe(context: Context): Boolean {
        if (!PairingSession.ready) {
            installed = false
            return false
        }
        val info = expected(context)
        val actual = PairingSession.rootShell().newJob()
            .add("sha256sum /data/adb/apd /data/adb/ap/bin/busybox 2>/dev/null").exec()
        installed = actual.isSuccess && actual.out.any { it == info.getString("apdSha256") + "  /data/adb/apd" } &&
            actual.out.any { it == info.getString("busyboxSha256") + "  /data/adb/ap/bin/busybox" }
        return installed
    }

    /** Only called after the owner confirms backend installation in the UI. */
    fun install(context: Context) {
        check(PairingSession.ready) { "请先完成内核配对" }
        PairingSession.adoptCurrentGrants(context)
        val info = expected(context)
        val native = context.applicationInfo.nativeLibraryDir
        val backup = "/data/adb/ap/ultra-backups/${java.util.UUID.randomUUID()}"
        val source = quote("$native/libapd.so")
        val busybox = quote("$native/libbusybox.so")
        val tools = quote("$native/libkptools.so")
        val script = """
            set -e
            mkdir -p /data/adb/ap/bin /data/adb/ap/log $backup
            chmod 700 $backup
            if [ -e /data/adb/apd ]; then cp -p /data/adb/apd $backup/apd; fi
            if [ -e /data/adb/ap/package_config ]; then cp -p /data/adb/ap/package_config $backup/package_config; fi
            cp $source /data/adb/apd.ultra-next
            cp $busybox /data/adb/ap/bin/busybox.ultra-next
            cp $tools /data/adb/ap/bin/kptools.ultra-next
            echo '${info.getString("apdSha256")}  /data/adb/apd.ultra-next' | sha256sum -c
            echo '${info.getString("busyboxSha256")}  /data/adb/ap/bin/busybox.ultra-next' | sha256sum -c
            chmod 755 /data/adb/apd.ultra-next /data/adb/ap/bin/busybox.ultra-next /data/adb/ap/bin/kptools.ultra-next
            mv /data/adb/ap/bin/busybox.ultra-next /data/adb/ap/bin/busybox
            mv /data/adb/ap/bin/kptools.ultra-next /data/adb/ap/bin/kptools
            printf 'pkg,exclude,allow,uid,to_uid,sctx\n' > /data/adb/ap/package_config.ultra-next
            chmod 600 /data/adb/ap/package_config.ultra-next
            mv /data/adb/ap/package_config.ultra-next /data/adb/ap/package_config
            printf '/system/bin/su\n' > /data/adb/ap/su_path
            mv /data/adb/apd.ultra-next /data/adb/apd
            ln -sf /data/adb/apd /data/adb/ap/bin/apd
            ln -sf /data/adb/apd /data/adb/ap/bin/resetprop
            ln -sf /data/adb/apd /data/adb/ap/bin/magiskpolicy
            restorecon /data/adb/apd /data/adb/ap/bin/busybox /data/adb/ap/bin/kptools
            /data/adb/ap/bin/magiskpolicy --magisk --live
            /data/adb/apd --version
            sync
        """.trimIndent()
        PairingSession.rootShell(fresh = true).use { shell ->
            val result = shell.newJob().add(script).exec()
            check(result.isSuccess) { "安装用户态后端失败 (${result.code})，原组件备份位于 $backup" }
        }
        check(probe(context)) { "用户态后端读回校验失败" }
    }
}
