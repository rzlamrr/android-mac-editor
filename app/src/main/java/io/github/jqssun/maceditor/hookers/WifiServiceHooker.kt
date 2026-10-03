package io.github.jqssun.maceditor.hookers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import io.github.jqssun.maceditor.BuildConfig
import io.github.jqssun.maceditor.TAG
import io.github.jqssun.maceditor.utils.MacUtils
import io.github.jqssun.maceditor.utils.SsidRules
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class WifiServiceHooker {
    companion object {
        var module: XposedModule? = null
            private set

        const val ACTION_APPLY_MAC = "${BuildConfig.APPLICATION_ID}.ACTION_APPLY_MAC"
        const val ACTION_MAC_DETECTED = "${BuildConfig.APPLICATION_ID}.ACTION_MAC_DETECTED"
        const val ACTION_APPLY_RESULT = "${BuildConfig.APPLICATION_ID}.ACTION_APPLY_RESULT"
        const val EXTRA_RESULT = "result"
        const val RESULT_APPLIED = "applied"
        const val RESULT_NO_MATCH = "no_match"
        const val RESULT_NOT_READY = "not_ready"
        private const val RECEIVER_CLASS = "${BuildConfig.APPLICATION_ID}.MacBroadcastReceiver"

        // cached WifiNative state
        private var nativeInstance: Any? = null
        private var nativeSetStaMethod: Method? = null
        private var staIface: String? = null
        private var apIface: String? = null
        private var nativeGetMacMethod: Method? = null
        private var receiverRegistered = false

        // SSID of the network ClientModeImpl is preparing, visible to setStaMacAddress on the same thread
        private class Ctx(val ssid: String?) { var applied = false }
        private val staCtx = ThreadLocal<Ctx?>()
        private val ctxFired = AtomicBoolean(false)
        private val hooksInstalled = AtomicBoolean(false)
        private val staFired = AtomicBoolean(false)
        private val apFired = AtomicBoolean(false)

        @SuppressLint("PrivateApi")
        fun hook(param: SystemServerStartingParam, module: XposedModule) {
            this.module = module
            module.hook(
                param.classLoader.loadClass("com.android.server.SystemServiceManager")
                    .getDeclaredMethod("loadClassFromLoader", String::class.java, ClassLoader::class.java)
            ).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (chain.getArg(0) == "com.android.server.wifi.WifiService" &&
                        hooksInstalled.compareAndSet(false, true)
                    ) {
                        _installNativeHooks(chain.getArg(1) as ClassLoader)
                    }
                } catch (t: Throwable) {
                    module.log(Log.ERROR, TAG, "Failed to install Wi-Fi hooks: $t")
                }
                result
            }
        }

        // each hook is installed independently so one wrong method name cannot break the others
        private fun _installNativeHooks(cl: ClassLoader) {
            val nativeClass = cl.loadClass("com.android.server.wifi.WifiNative")
            _hookSafely("WifiNative.setStaMacAddress") {
                val method = nativeClass.getDeclaredMethod("setStaMacAddress", String::class.java, MacAddress::class.java)
                nativeSetStaMethod = method
                module?.hook(method)?.intercept(StaMacHooker())
            }
            _hookSafely("WifiNative.setApMacAddress") {
                val method = nativeClass.getDeclaredMethod("setApMacAddress", String::class.java, MacAddress::class.java)
                module?.hook(method)?.intercept(ApMacHooker())
            }
            _hookSafely("WifiNative.getMacAddress (lookup)") {
                nativeGetMacMethod = nativeClass.getDeclaredMethod("getMacAddress", String::class.java)
            }
            // setStaMacAddress has no SSID; these ClientModeImpl callers do (and call it synchronously)
            val cmiClass = try {
                cl.loadClass("com.android.server.wifi.ClientModeImpl")
            } catch (t: Throwable) {
                module?.log(Log.ERROR, TAG, "ClientModeImpl not found, per-SSID rules disabled: $t")
                return
            }
            for (name in listOf("configureRandomizedMacAddress", "setCurrentMacToFactoryMac")) {
                _hookSafely("ClientModeImpl.$name") {
                    val method = cmiClass.getDeclaredMethod(name, WifiConfiguration::class.java)
                    module?.hook(method)?.intercept(SsidContextHooker(name))
                }
            }
        }

        private fun _hookSafely(name: String, block: () -> Unit) {
            try {
                block()
                module?.log(Log.INFO, TAG, "Hooked $name")
            } catch (t: Throwable) {
                module?.log(Log.ERROR, TAG, "Failed to hook $name: $t")
            }
        }

        @SuppressLint("PrivateApi")
        private fun _getSystemContext(): Context? {
            return try {
                val at = Class.forName("android.app.ActivityThread")
                at.getMethod("currentApplication").invoke(null) as? Context
            } catch (_: Exception) {
                null
            }
        }

        private fun _registerApplyReceiver() {
            if (receiverRegistered) return
            val ctx = _getSystemContext() ?: return
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    _applyMacDirectly()
                }
            }, IntentFilter(ACTION_APPLY_MAC), Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            module?.log(Log.INFO, TAG, "Registered apply-MAC receiver in system_server")
        }

        private fun _applyMacDirectly() {
            val native = nativeInstance
            val method = nativeSetStaMethod
            val iface = staIface
            if (native == null || method == null || iface == null) {
                module?.log(Log.WARN, TAG, "Cannot apply MAC: WifiNative/STA interface not cached yet")
                _sendApplyResult(RESULT_NOT_READY)
                return
            }
            val prefs = module?.getRemotePreferences(BuildConfig.APPLICATION_ID) ?: return
            val customMac = prefs.getString("customMac", "") ?: ""
            val perSsid = _isPerSsid(prefs)
            var ssid: String? = null
            val mac = if (perSsid) {
                ssid = _connectedSsid()
                SsidRules.find(SsidRules.fromJson(prefs.getString("rulesJson", null)), ssid)?.mac
            } else customMac.ifEmpty { null }
            if (mac == null) {
                module?.log(Log.INFO, TAG, "Apply: no MAC to set (SSID=${SsidRules.normalizeSsid(ssid)})")
                _sendApplyResult(RESULT_NO_MATCH)
                return
            }

            try {
                // runs on a broadcast thread, not the wifi handler; calls WifiNative.setStaMacAddress
                // (disconnects + HAL call). The SSID context makes StaMacHooker pick the rule's MAC.
                staCtx.set(Ctx(ssid))
                method.invoke(native, iface, MacAddress.fromString(mac))
                module?.log(Log.INFO, TAG, "Directly applied MAC: $mac on $iface")
                _sendApplyResult(RESULT_APPLIED)
            } catch (t: Throwable) {
                module?.log(Log.ERROR, TAG, "Failed to directly apply MAC: $t")
            } finally {
                staCtx.remove()
            }
        }

        private fun _isPerSsid(prefs: android.content.SharedPreferences): Boolean =
            SsidRules.resolvePerSsidMode(
                if (prefs.contains("perSsidMode")) prefs.getBoolean("perSsidMode", true) else null,
                prefs.getString("customMac", "") ?: "",
                prefs.contains("rulesJson")
            )

        @Suppress("DEPRECATION")
        private fun _connectedSsid(): String? = try {
            _getSystemContext()?.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        } catch (t: Throwable) {
            module?.log(Log.WARN, TAG, "Could not read connected SSID: $t")
            null
        }

        private fun _sendApplyResult(result: String) {
            try {
                _getSystemContext()?.sendBroadcast(
                    Intent(ACTION_APPLY_RESULT).setPackage(BuildConfig.APPLICATION_ID).putExtra(EXTRA_RESULT, result)
                )
            } catch (t: Throwable) {
                module?.log(Log.WARN, TAG, "Could not send apply result: $t")
            }
        }

        private fun _broadcastMacs(system: MacAddress, active: MacAddress) {
            try {
                val ctx = _getSystemContext() ?: return
                val intent = Intent(ACTION_MAC_DETECTED).apply {
                    putExtra("mac", system.toString())
                    putExtra("activeMac", active.toString())
                    setClassName(BuildConfig.APPLICATION_ID, RECEIVER_CLASS)
                }
                ctx.sendBroadcast(intent)
            } catch (e: Exception) {
                module?.log(Log.WARN, TAG, "Could not broadcast MAC: $e")
            }
        }

        // Wraps the ClientModeImpl calls that know the target network, so setStaMacAddress can match its SSID
        class SsidContextHooker(private val name: String) : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val ctx = try {
                    val prefs = module?.getRemotePreferences(BuildConfig.APPLICATION_ID)
                    if (prefs != null && prefs.getBoolean("hookActive", true) && _isPerSsid(prefs)) {
                        val ssid = (chain.getArg(0) as? WifiConfiguration)?.SSID
                        if (ctxFired.compareAndSet(false, true)) {
                            module?.log(Log.INFO, TAG, "ClientModeImpl.$name hook fired for SSID=${SsidRules.normalizeSsid(ssid)}")
                        }
                        Ctx(ssid).also { staCtx.set(it) }
                    } else null
                } catch (t: Throwable) {
                    module?.log(Log.ERROR, TAG, "SSID context hook error: $t")
                    null
                }
                try {
                    val result = chain.proceed()
                    staCtx.remove()
                    if (ctx != null && !ctx.applied) _applyIfSkipped(ctx)
                    return result
                } finally {
                    staCtx.remove()
                }
            }
        }

        // configureRandomizedMacAddress() skips setStaMacAddress when the framework's MAC already equals
        // the current one; if a rule matches and was not applied, set it ourselves (same thread).
        private fun _applyIfSkipped(ctx: Ctx) {
            try {
                val prefs = module?.getRemotePreferences(BuildConfig.APPLICATION_ID) ?: return
                val rule = SsidRules.find(SsidRules.fromJson(prefs.getString("rulesJson", null)), ctx.ssid) ?: return
                val native = nativeInstance ?: return
                val iface = staIface ?: return
                val current = nativeGetMacMethod?.invoke(native, iface) as? String
                if (rule.mac.equals(current, ignoreCase = true)) return
                staCtx.set(ctx)
                try {
                    nativeSetStaMethod?.invoke(native, iface, MacAddress.fromString(rule.mac))
                    module?.log(Log.INFO, TAG, "Applied rule MAC ${rule.mac} on $iface (framework skipped set)")
                } finally {
                    staCtx.remove()
                }
            } catch (t: Throwable) {
                module?.log(Log.ERROR, TAG, "Fallback apply failed: $t")
            }
        }

        class StaMacHooker : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                var replacement: MacAddress? = null
                try {
                    val prefs = module?.getRemotePreferences(BuildConfig.APPLICATION_ID)
                    if (prefs?.getBoolean("hookActive", true) ?: true) {
                        // cache WifiNative instance and STA iface
                        nativeInstance = chain.thisObject
                        staIface = chain.getArg(0) as? String
                        if (staFired.compareAndSet(false, true)) {
                            module?.log(Log.INFO, TAG, "setStaMacAddress hook fired on $staIface")
                        }
                        _registerApplyReceiver()

                        val ctx = staCtx.get()
                        val customMac = prefs?.getString("customMac", "") ?: ""
                        val mac = when {
                            prefs == null -> null
                            // per-SSID: only networks with an enabled rule are overridden
                            _isPerSsid(prefs) -> SsidRules.find(
                                SsidRules.fromJson(prefs.getString("rulesJson", null)), ctx?.ssid
                            )?.mac?.also { ctx?.applied = true }
                            customMac.isNotEmpty() -> customMac
                            else -> null
                        }
                        if (mac != null) {
                            replacement = MacAddress.fromString(mac)
                            module?.log(Log.INFO, TAG, "Replacing MAC with $mac on $staIface")
                        }

                        // report the system-assigned MAC and the one actually set
                        (chain.getArg(1) as? MacAddress)?.let { _broadcastMacs(it, replacement ?: it) }
                    }
                } catch (t: Throwable) {
                    module?.log(Log.ERROR, TAG, "STA hook error: $t")
                }
                return _proceedWith(chain, replacement)
            }
        }

        // Soft AP: untouched unless "Override hotspot MAC" is on. Never uses the Wi-Fi client MAC.
        class ApMacHooker : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                var replacement: MacAddress? = null
                try {
                    val prefs = module?.getRemotePreferences(BuildConfig.APPLICATION_ID)
                    if (prefs != null && prefs.getBoolean("hookActive", true) &&
                        prefs.getBoolean("apOverride", false)
                    ) {
                        apIface = chain.getArg(0) as? String
                        if (apFired.compareAndSet(false, true)) {
                            module?.log(Log.INFO, TAG, "setApMacAddress hook fired on $apIface")
                        }
                        val apMac = prefs.getString("apMac", "") ?: ""
                        val staMacs = SsidRules.wifiMacs(
                            _isPerSsid(prefs),
                            prefs.getString("customMac", "") ?: "",
                            SsidRules.fromJson(prefs.getString("rulesJson", null))
                        )
                        when {
                            MacUtils.validate(apMac) != MacUtils.ValidationResult.VALID ->
                                module?.log(Log.WARN, TAG, "Hotspot MAC invalid or unset; leaving AP MAC unchanged")
                            MacUtils.collides(apMac, staMacs) ->
                                module?.log(Log.WARN, TAG, "Hotspot MAC equals a Wi-Fi MAC; leaving AP MAC unchanged")
                            else -> {
                                replacement = MacAddress.fromString(apMac)
                                module?.log(Log.INFO, TAG, "Replacing AP MAC with $apMac on $apIface")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    module?.log(Log.ERROR, TAG, "AP hook error: $t")
                }
                return _proceedWith(chain, replacement)
            }
        }

        private fun _proceedWith(chain: XposedInterface.Chain, mac: MacAddress?): Any? {
            if (mac == null) return chain.proceed()
            val args = chain.args.toTypedArray()
            args[1] = mac
            return chain.proceed(args)
        }
    }
}
