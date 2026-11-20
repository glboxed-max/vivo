package com.sukisu.ultra.backend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sukisu.ultra.Natives.Profile
import com.topjohnwu.superuser.Shell
import java.util.concurrent.FutureTask
import me.bmax.apatch.BackendSession
import me.bmax.apatch.Natives as AP
import org.json.JSONObject

object PairingSession {
    private lateinit var vault: PairingVault
    private var cachedShell: Shell? = null
    @Volatile var error: String? = null
        private set
    val ready: Boolean get() = BackendSession.protocolMarker.isNotEmpty()

    @Synchronized
    fun initialize(context: Context) {
        BackendSession.clear()
        error = null
        try {
            BackendSession.connectManager()
        } catch (failure: Exception) {
            BackendSession.clear()
            error = failure.message ?: "连接内核失败"
        }
        try {
            if (!::vault.isInitialized) vault = PairingVault(context.applicationContext)
            vault.read()
            if (ready) restoreGrants()
        } catch (failure: Exception) {
            error = failure.message ?: "读取授权记录失败"
        }
    }

    fun version(): Long = if (ready) AP.kernelPatchVersion() else 0

    fun versionText(): String {
        val number = version()
        return if (number > 0) "KernelPatch v${number shr 16}.${(number shr 8) and 255}.${number and 255}" else "未识别到兼容内核"
    }

    fun profile(packageName: String?, uid: Int): Profile {
        if (!ready) return Profile(packageName.orEmpty(), uid)
        val allowed = AP.suUids().contains(uid)
        val native = if (allowed) AP.suProfile(uid) else AP.Profile(uid)
        return Profile(name = packageName.orEmpty(), currentUid = uid, allowSu = allowed,
            uid = native.toUid, context = native.scontext,
            rootUseDefault = native.toUid == 0 && native.scontext == "u:r:su:s0")
    }

    @Synchronized
    fun setProfile(profile: Profile?): Boolean {
        if (!ready || profile == null || profile.currentUid < 0) return false
        // This backend supports the default root profile and explicit UID/context only.
        if (!profile.rootUseDefault && (profile.groups.isNotEmpty() || profile.capabilities.isNotEmpty() ||
                profile.gid != 0 || profile.namespace != 0 || profile.rules.isNotEmpty())) return false
        return try {
            val state = vault.read()
            val identity = vault.identity(profile.name)
            check(identity.getInt("uid") == profile.currentUid) { "应用 UID 已改变" }
            val old = AP.suUids().contains(profile.currentUid)
            val previous = if (old) AP.suProfile(profile.currentUid) else null
            val targetUid = if (profile.rootUseDefault) 0 else profile.uid
            val context = if (profile.rootUseDefault) "u:r:su:s0" else profile.context
            val rc = if (profile.allowSu) AP.grantSu(profile.currentUid, targetUid, context)
                else AP.revokeSu(profile.currentUid)
            check(rc == 0L) { "更新内核授权失败 ($rc)" }
            try {
                check(AP.suUids().contains(profile.currentUid) == profile.allowSu) { "授权读回校验失败" }
                val grants = state.getJSONObject("grants")
                if (profile.allowSu) {
                    grants.put(profile.currentUid.toString(), identity.put("toUid", targetUid).put("context", context))
                } else {
                    grants.remove(profile.currentUid.toString())
                }
                vault.write(state)
            } catch (failure: Exception) {
                if (old) {
                    AP.grantSu(profile.currentUid, previous!!.toUid, previous.scontext)
                } else AP.revokeSu(profile.currentUid)
                throw failure
            }
            error = null
            true
        } catch (failure: Exception) {
            error = failure.message ?: "更新授权失败"
            false
        }
    }

    @Synchronized
    private fun restoreGrants() {
        val state = vault.read()
        val grants = state.getJSONObject("grants")
        val stale = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (uid in grants.keys()) {
            val record = grants.getJSONObject(uid)
            if (vault.matches(record)) {
                val rc = AP.grantSu(uid.toInt(), record.optInt("toUid", 0), record.optString("context", "u:r:su:s0"))
                if (rc != 0L) failed.add(uid)
            } else {
                val rc = AP.revokeSu(uid.toInt())
                if (rc == 0L) stale.add(uid) else failed.add(uid)
            }
        }
        if (stale.isNotEmpty()) {
            stale.forEach { grants.remove(it) }
            vault.write(state)
        }
        if (failed.isNotEmpty()) error = "部分授权恢复失败 ${failed.joinToString()}"
    }

    @Synchronized
    fun adoptCurrentGrants(context: Context) {
        check(ready) { "请先安装支持此管理器的内核补丁" }
        val state = vault.read()
        val grants = state.getJSONObject("grants")
        for (uid in AP.suUids()) {
            if (grants.has(uid.toString())) continue
            val name = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: continue
            val identity = vault.identity(name)
            check(identity.getInt("uid") == uid) { "授权 UID 已改变" }
            val profile = AP.suProfile(uid)
            grants.put(uid.toString(), identity.put("toUid", profile.toUid).put("context", profile.scontext))
        }
        vault.write(state)
    }

    @Synchronized
    fun rootShell(fresh: Boolean = false): Shell {
        if (!fresh && ready && cachedShell?.isAlive == true) return cachedShell!!
        check(ready) { "当前内核未授予此管理器访问权限" }
        val operation = FutureTask<Shell> {
            check(AP.su()) { "取得 KernelPatch Root 失败" }
            val shell = Shell.Builder.create().build("/system/bin/sh")
            if (!shell.isRoot) {
                shell.close()
                error("Root shell 校验失败")
            }
            shell
        }
        Thread(operation, "UltraRootShell").start()
        return operation.get().also { if (!fresh) cachedShell = it }
    }
}

class PairingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_ADDED)) return
        val pending = goAsync()
        Thread {
            try {
                PairingSession.initialize(context)
                if (SusfsManager.autoLoad(context)) {
                    runCatching { PairingSession.rootShell(fresh = true).use { SusfsManager.load(context, it) } }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
