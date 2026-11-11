package com.v2ray.ang.banshan

import com.v2ray.ang.handler.MmkvManager

/**
 * Snapshot of everything the protected BanShan subscription owns, plus its device-local login
 * flags. Raw MMKV values are kept so restoring preserves server GUIDs and the current selection.
 */
internal data class ManagedLoginState(
    val subscriptionId: String,
    val subscriptionJson: String,
    val serverListJson: String?,
    val profiles: Map<String, String>,
    val rawConfigs: Map<String, String>,
    val affiliations: Map<String, String>,
    val selectedServer: String?,
    val cacheSubscriptionId: String?,
    val loggedIn: Boolean,
    val username: String?,
)

/**
 * Keeps BanShan login data out of backups and restores it afterwards.
 *
 * Backups are MMKV snapshots, so the managed subscription and the login flags are removed from
 * MMKV for the duration of the export and written back in `finally`. On restore the local snapshot
 * is captured first, anything `bs1:` coming from the archive is stripped, and the local snapshot is
 * written back so restoring a backup never logs the device out.
 */
internal object BanShanBackup {

    fun captureLocalLogin(): ManagedLoginState? = MmkvManager.captureManagedLoginState()

    fun removeLocalLoginForExport(state: ManagedLoginState?) {
        state ?: return
        MmkvManager.removeManagedLoginData(state)
    }

    fun restoreLocalLogin(state: ManagedLoginState?) {
        state ?: return
        MmkvManager.restoreManagedLoginData(state)
    }

    fun stripLoginDataFromRestoredBackup(): Boolean =
        MmkvManager.stripManagedLoginData()
}
