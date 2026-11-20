package com.sukisu.ultra.backend

import com.sukisu.ultra.ui.util.createRootShell
import com.sukisu.ultra.ui.util.getRootShell
import com.topjohnwu.superuser.Shell

/**
 * Root for image work: KernelPatch when its backend is running, otherwise the
 * KernelSU/su binary the phone already has. The flash and init_boot flows must
 * work before the first KernelPatch boot is written.
 */
object RootAccess {
    data class Session(val shell: Shell, val source: String)

    fun acquire(fresh: Boolean = false): Session {
        if (PairingSession.ready) {
            val shell = PairingSession.rootShell(fresh)
            if (shell.isRoot) return Session(shell, "KernelPatch")
        }
        val shell = if (fresh) createRootShell() else getRootShell()
        check(shell.isRoot) { "未取得 Root：请在 KernelSU 里给本应用授权后再试" }
        return Session(shell, "KernelSU")
    }
}
