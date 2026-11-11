package com.v2ray.ang.banshan

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Reasons a BanShan login can fail, mapped to user-facing strings by the login screen. */
enum class BanShanLoginStage {
    SIGNING_IN,
    LOADING_NODES,
}

enum class BanShanLoginFailure {
    EMPTY_USERNAME,
    EMPTY_PASSWORD,
    NETWORK,
    BAD_RESPONSE,
    SERVER_REJECTED,
    NO_NODES,
}

sealed interface BanShanLoginResult {
    data class Success(val username: String) : BanShanLoginResult
    data class Failure(
        val reason: BanShanLoginFailure,
        val serverMessage: String? = null,
    ) : BanShanLoginResult
}

/**
 * Owns the BanShan account: login against `/get_sub`, the hidden protected subscription, and the
 * device-local login state.
 *
 * The subscription URL is stored AES-obfuscated (`bs1:`) and the subscription row is treated as
 * read-only everywhere else in the app; [AngConfigManager.updateConfigViaSub] decrypts it for the
 * normal refresh paths.
 */
object BanShanSession {
    const val LOGIN_URL = "https://proxy.yinshaoze.asia/get_sub"

    private const val SUCCESS_CODE = 200
    private const val LOGIN_TIMEOUT_SECONDS = 20L
    private const val AUTO_UPDATE_INTERVAL_MINUTES = 360L
    private const val STALE_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val FALLBACK_GROUP_NAME = "半山代理"

    @Volatile
    private var loginChanged = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Name of the hidden BanShan subscription group. */
    fun groupName(): String = runCatching {
        AngApplication.application.getString(R.string.banshan_group_name)
    }.getOrDefault(FALLBACK_GROUP_NAME)

    /** True when the device-local flag is set and the protected subscription still exists. */
    fun isLoggedIn(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_BANSHAN_LOGGED_IN) &&
            managedSubscription() != null

    fun username(): String =
        MmkvManager.decodeSettingsString(AppConfig.PREF_BANSHAN_USERNAME).orEmpty()

    fun managedSubscription(): SubscriptionCache? =
        MmkvManager.decodeSubscriptions().firstOrNull {
            BanShanCrypto.isEncrypted(it.subscription.url)
        }

    fun managedSubscriptionId(): String? = managedSubscription()?.guid

    fun isManagedSubscription(subscriptionId: String?): Boolean =
        MmkvManager.isManagedSubscription(subscriptionId)

    fun isManagedProfile(profile: ProfileItem?): Boolean =
        profile != null && isManagedSubscription(profile.subscriptionId)

    fun isManagedProfileGuid(guid: String?): Boolean =
        guid != null && MmkvManager.isManagedProfile(guid)

    /** Plain subscription URL of the protected subscription, or null when it cannot be decrypted. */
    fun subscriptionUrl(): String? {
        val stored = managedSubscription()?.subscription?.url ?: return null
        return BanShanCrypto.decrypt(stored)
    }

    /** Marks the main screen so it reloads groups after a successful (re)login. */
    fun markLoginChanged() {
        loginChanged = true
    }

    fun consumeLoginChanged(): Boolean {
        val changed = loginChanged
        loginChanged = false
        return changed
    }

    /**
     * Logs in, validates the returned subscription URL, replaces the managed subscription's nodes,
     * and only then persists the login state.
     */
    suspend fun signIn(
        username: String,
        password: String,
        onStage: (BanShanLoginStage) -> Unit = {},
    ): BanShanLoginResult =
        withContext(Dispatchers.IO) {
            val user = username.trim()
            if (user.isEmpty()) {
                return@withContext BanShanLoginResult.Failure(BanShanLoginFailure.EMPTY_USERNAME)
            }
            if (password.isEmpty()) {
                return@withContext BanShanLoginResult.Failure(BanShanLoginFailure.EMPTY_PASSWORD)
            }

            onStage(BanShanLoginStage.SIGNING_IN)
            val response = try {
                postLogin(user, password)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "BanShan login request failed", e)
                return@withContext BanShanLoginResult.Failure(BanShanLoginFailure.NETWORK)
            }

            val payload = BanShanLoginParser.parse(response.second)
                ?: return@withContext BanShanLoginResult.Failure(BanShanLoginFailure.BAD_RESPONSE)

            if (payload.code != SUCCESS_CODE) {
                LogUtil.i(AppConfig.TAG, "BanShan login rejected: HTTP ${response.first} code=${payload.code}")
                return@withContext BanShanLoginResult.Failure(
                    BanShanLoginFailure.SERVER_REJECTED,
                    payload.message
                )
            }

            val subscriptionUrl = payload.subscriptionUrl
                ?.takeIf { url -> BanShanLoginParser.isValidSubscriptionUrl(url) }
                ?: return@withContext BanShanLoginResult.Failure(BanShanLoginFailure.BAD_RESPONSE)

            onStage(BanShanLoginStage.LOADING_NODES)
            if (!syncManagedSubscription(subscriptionUrl)) {
                return@withContext BanShanLoginResult.Failure(BanShanLoginFailure.NO_NODES)
            }

            MmkvManager.encodeSettings(AppConfig.PREF_BANSHAN_USERNAME, user)
            MmkvManager.encodeSettings(AppConfig.PREF_BANSHAN_LOGGED_IN, true)
            markLoginChanged()
            BanShanLoginResult.Success(user)
        }

    /**
     * Re-downloads the protected subscription from the stored URL.
     *
     * @return true when at least one node was stored.
     */
    suspend fun refreshManagedSubscription(): Boolean = withContext(Dispatchers.IO) {
        val subscription = managedSubscription() ?: return@withContext false
        if (BanShanCrypto.decrypt(subscription.subscription.url) == null) {
            return@withContext false
        }
        val result = AngConfigManager.updateConfigViaSub(subscription)
        if (result.configCount > 0) {
            ensureSelectedServer(subscription.guid)
            true
        } else {
            false
        }
    }

    /**
     * Refreshes the protected subscription when its cached nodes are older than six hours.
     *
     * @return true when a refresh stored new nodes.
     */
    suspend fun refreshIfStale(): Boolean = withContext(Dispatchers.IO) {
        val subscription = managedSubscription() ?: return@withContext false
        val lastUpdated = subscription.subscription.lastUpdated
        if (lastUpdated > 0L &&
            System.currentTimeMillis() - lastUpdated < STALE_REFRESH_INTERVAL_MS
        ) {
            return@withContext false
        }
        refreshManagedSubscription()
    }

    /**
     * Creates or replaces the protected subscription.
     *
     * The old subscription row and its nodes stay untouched unless the new URL yields nodes;
     * [AngConfigManager.updateConfigViaSub] commits the new profiles before dropping the old ones.
     */
    private fun syncManagedSubscription(subscriptionUrl: String): Boolean {
        val encrypted = BanShanCrypto.encrypt(subscriptionUrl) ?: return false
        val existing = managedSubscription()
        val guid = existing?.guid ?: Utils.getUuid()
        if (guid.isEmpty()) return false

        val item = (existing?.subscription ?: SubscriptionItem()).copy(
            remarks = groupName(),
            url = encrypted,
            enabled = true,
            autoUpdate = true,
            updateInterval = AUTO_UPDATE_INTERVAL_MINUTES,
        )

        val result = AngConfigManager.updateConfigViaSub(SubscriptionCache(guid, item))
        if (result.configCount <= 0 || result.successCount <= 0) return false

        MmkvManager.encodeSubscription(guid, item)
        SubscriptionUpdater.syncOne(subId = guid)
        ensureSelectedServer(guid)
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, guid)
        return true
    }

    /** Keeps the current selection when it is still part of the protected subscription. */
    private fun ensureSelectedServer(subscriptionId: String) {
        val servers = MmkvManager.decodeServerList(subscriptionId)
        if (servers.isEmpty()) return
        val selected = MmkvManager.getSelectServer()
        if (selected.isNullOrEmpty() || selected !in servers) {
            MmkvManager.setSelectServer(servers.first())
        }
    }

    private fun postLogin(username: String, password: String): Pair<Int, String> {
        // Credentials travel in the form body only: query strings end up in proxy and server logs.
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder()
            .url(LOGIN_URL)
            .post(form)
            .header("Accept", "*/*")
            .header("User-Agent", "BanShanProxy/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }
}
