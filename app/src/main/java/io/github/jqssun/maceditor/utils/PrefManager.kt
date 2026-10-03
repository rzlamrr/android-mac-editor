package io.github.jqssun.maceditor.utils

import android.content.SharedPreferences
import io.github.jqssun.maceditor.BuildConfig
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

enum class OverrideMode { OFF, GLOBAL, PER_SSID }

class PrefManager {
    companion object {
        private var prefs: SharedPreferences? = null
        private var loaded = false

        fun loadPrefs(onReady: (() -> Unit)? = null) {
            if (loaded) {
                if (XposedChecker.isEnabled()) onReady?.invoke()
                return
            }
            loaded = true
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    XposedChecker.flagAsEnabled()
                    prefs = service.getRemotePreferences(BuildConfig.APPLICATION_ID)
                    onReady?.invoke()
                }

                override fun onServiceDied(service: XposedService) {}
            })
        }

        fun isHookOn(): Boolean {
            return prefs?.getBoolean("hookActive", true) ?: true
        }

        fun setHookState(on: Boolean) {
            prefs?.edit()?.putBoolean("hookActive", on)?.apply()
        }

        fun getCustomMac(): String {
            return prefs?.getString("customMac", "") ?: ""
        }

        fun setCustomMac(mac: String) {
            prefs?.edit()?.putString("customMac", mac)?.apply()
        }

        fun isPerSsidMode(): Boolean {
            val p = prefs ?: return true
            return SsidRules.resolvePerSsidMode(
                if (p.contains("perSsidMode")) p.getBoolean("perSsidMode", true) else null,
                getCustomMac(),
                p.contains("rulesJson")
            )
        }

        fun setPerSsidMode(on: Boolean) {
            prefs?.edit()?.putBoolean("perSsidMode", on)?.apply()
        }

        fun getRules(): List<SsidRules.Rule> = SsidRules.fromJson(prefs?.getString("rulesJson", null))

        fun setRules(rules: List<SsidRules.Rule>) {
            prefs?.edit()?.putString("rulesJson", SsidRules.toJson(rules))?.apply()
        }

        /** MACs the Wi-Fi client can be given in the current mode. */
        fun wifiMacs(): List<String> = SsidRules.wifiMacs(isPerSsidMode(), getCustomMac(), getRules())

        fun getMode(): OverrideMode = when {
            !isHookOn() -> OverrideMode.OFF
            isPerSsidMode() -> OverrideMode.PER_SSID
            else -> OverrideMode.GLOBAL
        }

        /** OFF only clears hookActive, so the last Global/Per-SSID choice survives. */
        fun setMode(mode: OverrideMode) {
            setHookState(mode != OverrideMode.OFF)
            if (mode != OverrideMode.OFF) setPerSsidMode(mode == OverrideMode.PER_SSID)
        }

        fun isApOverride(): Boolean {
            return prefs?.getBoolean("apOverride", false) ?: false
        }

        fun setApOverride(on: Boolean) {
            prefs?.edit()?.putBoolean("apOverride", on)?.apply()
        }

        fun getApMac(): String {
            return prefs?.getString("apMac", "") ?: ""
        }

        fun setApMac(mac: String) {
            prefs?.edit()?.putString("apMac", mac)?.apply()
        }

        fun isForceShowMacRandomization(): Boolean {
            return prefs?.getBoolean("forceShowMacRandomization", true) ?: true
        }

        fun setForceShowMacRandomization(on: Boolean) {
            prefs?.edit()?.putBoolean("forceShowMacRandomization", on)?.apply()
        }
    }
}
