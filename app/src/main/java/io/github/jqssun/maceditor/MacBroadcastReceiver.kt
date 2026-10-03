package io.github.jqssun.maceditor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MacBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mac = intent.getStringExtra(EXTRA_MAC) ?: return
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        edit.putString("deviceMac", mac.uppercase())
        intent.getStringExtra(EXTRA_ACTIVE_MAC)?.let { edit.putString("activeMac", it.uppercase()) }
        edit.apply()
    }

    companion object {
        const val ACTION_MAC_DETECTED = "${BuildConfig.APPLICATION_ID}.ACTION_MAC_DETECTED"
        const val EXTRA_MAC = "mac"
        const val EXTRA_ACTIVE_MAC = "activeMac"
        const val PREFS_NAME = "local_prefs"
    }
}
