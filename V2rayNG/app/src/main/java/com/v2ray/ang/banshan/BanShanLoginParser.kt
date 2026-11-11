package com.v2ray.ang.banshan

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * Parsed body of the BanShan `/get_sub` login endpoint.
 */
data class BanShanLoginPayload(
    val code: Int?,
    val message: String?,
    val subscriptionUrl: String?,
)

/**
 * Parses and validates the BanShan login response.
 *
 * Kept free of Android dependencies so the protocol rules are unit-testable.
 */
object BanShanLoginParser {
    private val subscriptionKeys = listOf(
        "sub_url",
        "subUrl",
        "subscription_url",
        "subscriptionUrl",
        "sub",
        "url",
        "link",
    )

    private val dataKeys = subscriptionKeys + "data"

    /**
     * Parses a JSON login body.
     *
     * @return null when the body is not a JSON object.
     */
    fun parse(body: String?): BanShanLoginPayload? {
        if (body.isNullOrBlank()) return null
        val root = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            return null
        }
        if (!root.isJsonObject) return null
        val json = root.asJsonObject

        val code = json.primitive("code")?.let { it.toIntOrNull() }
        val message = json.primitive("msg") ?: json.primitive("message")

        val data = json.get("data")
        val subscriptionUrl = extractSubscriptionUrl(data)
            ?: subscriptionKeys.firstNotNullOfOrNull { key ->
                json.primitive(key)?.takeIf { url -> isValidSubscriptionUrl(url) }
            }

        return BanShanLoginPayload(code, message, subscriptionUrl)
    }

    /**
     * Extracts the subscription URL from a `data` field that may be a plain URL string or an
     * object/array carrying one of the legacy key names.
     */
    fun extractSubscriptionUrl(data: JsonElement?): String? {
        if (data == null || data.isJsonNull) return null
        if (data.isJsonPrimitive) {
            return data.asJsonPrimitive.asString.trim().takeIf { url -> isValidSubscriptionUrl(url) }
        }
        if (data.isJsonObject) {
            val json = data.asJsonObject
            return dataKeys.firstNotNullOfOrNull { key ->
                json.primitive(key)?.takeIf { url -> isValidSubscriptionUrl(url) }
            }
        }
        if (data.isJsonArray) {
            return data.asJsonArray.firstNotNullOfOrNull { extractSubscriptionUrl(it) }
        }
        return null
    }

    /**
     * Returns true only for a complete http(s) subscription URL.
     */
    fun isValidSubscriptionUrl(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return false
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        if (text.any { it.isWhitespace() }) return false
        return try {
            // java.net.URI keeps the scheme case, but host parsing is more predictable with a
            // lower-case scheme.
            val normalized = if (text.startsWith("http://", ignoreCase = true)) {
                "http://" + text.substring(7)
            } else {
                "https://" + text.substring(8)
            }
            val uri = URI(normalized)
            !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun JsonObject.primitive(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
