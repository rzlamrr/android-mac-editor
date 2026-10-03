package io.github.jqssun.maceditor.utils

import org.json.JSONArray
import org.json.JSONObject

/** Per-SSID MAC rules. Pure Kotlin (plus org.json) so it can be unit-tested on the JVM. */
object SsidRules {

    data class Rule(val ssid: String, val mac: String, val enabled: Boolean = true)

    /**
     * Strips one pair of surrounding quotes, as in WifiConfiguration.SSID / WifiInfo.getSSID().
     * An unquoted value is returned as is: Android uses that form (hex) for non-UTF-8 SSIDs,
     * which therefore never match a text rule.
     */
    fun normalizeSsid(raw: String?): String {
        if (raw == null) return ""
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) return raw.substring(1, raw.length - 1)
        return raw
    }

    /** First enabled rule whose SSID equals [rawSsid] exactly (case-sensitive); null for hidden/empty SSIDs. */
    fun find(rules: List<Rule>, rawSsid: String?): Rule? {
        val ssid = normalizeSsid(rawSsid)
        if (ssid.isEmpty()) return null
        return rules.firstOrNull { it.enabled && it.ssid == ssid }
    }

    /** Stored choice wins; otherwise keep legacy users with a global MAC (and no rules yet) in global mode. */
    fun resolvePerSsidMode(stored: Boolean?, customMac: String, hasRules: Boolean): Boolean =
        stored ?: !(customMac.isNotEmpty() && !hasRules)

    /** MACs the Wi-Fi client may be given, used to keep the hotspot MAC distinct. */
    fun wifiMacs(perSsid: Boolean, customMac: String, rules: List<Rule>): List<String> =
        if (perSsid) rules.map { it.mac } else listOf(customMac)

    fun toJson(rules: List<Rule>): String = JSONArray().apply {
        rules.forEach { put(JSONObject().put("ssid", it.ssid).put("mac", it.mac).put("enabled", it.enabled)) }
    }.toString()

    /** Tolerant parser: malformed input yields an empty list, bad entries are skipped. */
    fun fromJson(json: String?): List<Rule> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val ssid = o.optString("ssid")
                val mac = o.optString("mac")
                if (ssid.isEmpty() || mac.isEmpty()) null else Rule(ssid, mac, o.optBoolean("enabled", true))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
