package io.github.jqssun.maceditor.hookers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.util.Log
import io.github.jqssun.maceditor.BuildConfig
import io.github.jqssun.maceditor.TAG
import io.github.jqssun.maceditor.utils.MacUtils
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
        private const val RECEIVER_CLASS = "${BuildConfig.APPLICATION_ID}.MacBroadcastReceiver"

        // cached WifiNative state
        private var nativeInstance: Any? = null
        private var nativeSetStaMethod: Method? = null
        private var staIface: String? = null
        private var apIface: String? = null
        private var receiverRegistered = false
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
                return
            }
            val prefs = module?.getRemotePreferences(BuildConfig.APPLICATION_ID)
            val mac = prefs?.getString("customMac", "") ?: ""
            if (mac.isEmpty()) return

            try {
                // calls WifiNative.setStaMacAddress which does disconnect() + HAL call
                method.invoke(native, iface, MacAddress.fromString(mac))
                module?.log(Log.INFO, TAG, "Directly applied MAC: $mac on $iface")
            } catch (t: Throwable) {
                module?.log(Log.ERROR, TAG, "Failed to directly apply MAC: $t")
            }
        }

        private fun _broadcastDeviceMac(mac: MacAddress) {
            try {
                val ctx = _getSystemContext() ?: return
                val intent = Intent(ACTION_MAC_DETECTED).apply {
                    putExtra("mac", mac.toString())
                    setClassName(BuildConfig.APPLICATION_ID, RECEIVER_CLASS)
                }
                ctx.sendBroadcast(intent)
            } catch (e: Exception) {
                module?.log(Log.WARN, TAG, "Could not broadcast MAC: $e")
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

                        // broadcast the system-assigned MAC to the app
                        (chain.getArg(1) as? MacAddress)?.let { _broadcastDeviceMac(it) }

                        val customMac = prefs?.getString("customMac", "") ?: ""
                        if (customMac.isNotEmpty()) {
                            replacement = MacAddress.fromString(customMac)
                            module?.log(Log.INFO, TAG, "Replacing MAC with $customMac on $staIface")
                        }
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
                        val staMacs = listOf(prefs.getString("customMac", "") ?: "")
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
