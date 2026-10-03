package io.github.jqssun.maceditor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.jqssun.maceditor.databinding.ActivityMainBinding
import io.github.jqssun.maceditor.databinding.DialogRuleBinding
import io.github.jqssun.maceditor.databinding.ItemRuleBinding
import io.github.jqssun.maceditor.hookers.WifiServiceHooker
import io.github.jqssun.maceditor.utils.MacTextWatcher
import io.github.jqssun.maceditor.utils.MacUtils
import io.github.jqssun.maceditor.utils.OverrideMode
import io.github.jqssun.maceditor.utils.PrefManager
import io.github.jqssun.maceditor.utils.SsidRules
import io.github.jqssun.maceditor.utils.XposedChecker

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var updatingUI = false

    private val macReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _refreshDeviceMac()
            _updateStatusCard()
        }
    }

    private val applyResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = when (intent.getStringExtra(WifiServiceHooker.EXTRA_RESULT)) {
                WifiServiceHooker.RESULT_APPLIED -> R.string.apply_applied
                WifiServiceHooker.RESULT_NO_MATCH -> R.string.apply_no_match
                WifiServiceHooker.RESULT_NOT_READY -> R.string.apply_not_ready
                else -> return
            }
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        _setupToggles()
        _setupMacCard()
        _setupRulesCard()
        _setupHotspotCard()
        binding.footerNote.text = getString(R.string.footer_note, getString(R.string.force_mac_randomization_label))

        PrefManager.loadPrefs { runOnUiThread { _refreshAll() } }
        _refreshAll()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            macReceiver,
            IntentFilter(MacBroadcastReceiver.ACTION_MAC_DETECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            applyResultReceiver,
            IntentFilter(WifiServiceHooker.ACTION_APPLY_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        _refreshAll()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(macReceiver)
        unregisterReceiver(applyResultReceiver)
    }

    private fun _refreshAll() {
        updatingUI = true
        _updateStatusCard()
        _refreshDeviceMac()
        _refreshActiveMac()
        binding.forceRandomizationSwitch.isChecked = PrefManager.isForceShowMacRandomization()
        val saved = PrefManager.getCustomMac()
        if (saved.isNotEmpty() && binding.edittextNewMac.text.isNullOrEmpty()) {
            binding.edittextNewMac.setText(saved)
        }
        _updateModeViews()
        binding.apOverrideSwitch.isChecked = PrefManager.isApOverride()
        _setApFieldsVisible(PrefManager.isApOverride())
        val apMac = PrefManager.getApMac()
        if (apMac.isNotEmpty() && binding.edittextApMac.text.isNullOrEmpty()) {
            binding.edittextApMac.setText(apMac)
        }
        updatingUI = false
    }

    private fun _isSystemServerHooked(): Boolean {
        return getSharedPreferences(MacBroadcastReceiver.PREFS_NAME, MODE_PRIVATE)
            .getString("deviceMac", null) != null
    }

    private fun _updateStatusCard() {
        val enabled = XposedChecker.isEnabled()
        val hooked = enabled && _isSystemServerHooked()
        val hookOn = hooked && PrefManager.isHookOn()

        when {
            !enabled -> {
                binding.moduleStatusIcon.setImageResource(R.drawable.ic_disabled_24)
                binding.moduleStatus.text = getString(R.string.status_not_activated)
                binding.serviceStatus.text = getString(R.string.status_detail_not_activated)
            }
            !hooked -> {
                binding.moduleStatusIcon.setImageResource(R.drawable.ic_error_24)
                binding.moduleStatus.text = getString(R.string.status_inactive)
                binding.serviceStatus.text = getString(R.string.status_detail_inactive)
            }
            !hookOn -> {
                binding.moduleStatusIcon.setImageResource(R.drawable.ic_warning_24)
                binding.moduleStatus.text = getString(R.string.status_activated)
                binding.serviceStatus.text = getString(R.string.status_detail_hook_off)
            }
            else -> {
                binding.moduleStatusIcon.setImageResource(R.drawable.ic_baseline_router_24)
                binding.moduleStatus.text = getString(R.string.status_activated)
                binding.serviceStatus.text = getString(R.string.status_detail_hook_on)
            }
        }
    }

    private fun _setupToggles() {
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingUI) return@addOnButtonCheckedListener
            PrefManager.setMode(
                when (checkedId) {
                    R.id.btn_mode_global -> OverrideMode.GLOBAL
                    R.id.btn_mode_per_ssid -> OverrideMode.PER_SSID
                    else -> OverrideMode.OFF
                }
            )
            _updateModeViews()
            _updateStatusCard()
        }
        binding.forceRandomizationSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUI) return@setOnCheckedChangeListener
            PrefManager.setForceShowMacRandomization(checked)
        }
    }

    private fun _refreshDeviceMac() {
        val localPrefs = getSharedPreferences(MacBroadcastReceiver.PREFS_NAME, MODE_PRIVATE)
        val mac = localPrefs.getString("deviceMac", null)
        binding.textviewDeviceMac.text = mac ?: getString(R.string.mac_not_set)
    }

    private fun _refreshActiveMac() {
        val active = getSharedPreferences(MacBroadcastReceiver.PREFS_NAME, MODE_PRIVATE)
            .getString("activeMac", null)
        binding.textviewCurrentMac.text = active ?: getString(R.string.mac_not_set)
    }

    private fun _setupMacCard() {
        val editText = binding.edittextNewMac
        editText.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(17))
        editText.addTextChangedListener(MacTextWatcher())

        binding.btnGenerateMac.setOnClickListener {
            editText.setText(MacUtils.generateRandom())
        }

        binding.btnSetMac.setOnClickListener {
            val mac = editText.text.toString().uppercase()
            when (MacUtils.validate(mac)) {
                MacUtils.ValidationResult.BAD_LENGTH ->
                    _showError(getString(R.string.error_bad_length))
                MacUtils.ValidationResult.ALL_ZEROS ->
                    _showError(getString(R.string.error_all_zeros))
                MacUtils.ValidationResult.ODD_FIRST_OCTET ->
                    _showError(getString(R.string.error_odd_first_octet))
                MacUtils.ValidationResult.VALID -> {
                    if (MacUtils.collides(mac, listOf(PrefManager.getApMac()))) {
                        _showError(getString(R.string.error_mac_collision))
                        return@setOnClickListener
                    }
                    PrefManager.setCustomMac(mac)
                    _applyMac()
                }
            }
        }
    }

    private fun _updateModeViews() {
        val mode = PrefManager.getMode()
        val prev = updatingUI
        updatingUI = true
        binding.modeGroup.check(
            when (mode) {
                OverrideMode.OFF -> R.id.btn_mode_off
                OverrideMode.GLOBAL -> R.id.btn_mode_global
                OverrideMode.PER_SSID -> R.id.btn_mode_per_ssid
            }
        )
        updatingUI = prev
        binding.globalCard.visibility = if (mode == OverrideMode.GLOBAL) View.VISIBLE else View.GONE
        binding.rulesCard.visibility = if (mode == OverrideMode.PER_SSID) View.VISIBLE else View.GONE
        binding.modeHint.setText(
            when (mode) {
                OverrideMode.OFF -> R.string.mode_hint_off
                OverrideMode.GLOBAL -> R.string.mode_hint_global
                OverrideMode.PER_SSID -> R.string.mode_hint_per_ssid
            }
        )
        if (mode == OverrideMode.PER_SSID) _renderRules()
    }

    private fun _setupRulesCard() {
        binding.btnAddRule.setOnClickListener { _showRuleDialog(null) }
        binding.btnApplyRules.setOnClickListener {
            sendBroadcast(Intent(WifiServiceHooker.ACTION_APPLY_MAC))
        }
    }

    private fun _renderRules() {
        val rules = PrefManager.getRules()
        binding.rulesEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        binding.rulesContainer.removeAllViews()
        rules.forEachIndexed { index, rule ->
            val row = ItemRuleBinding.inflate(layoutInflater, binding.rulesContainer, false)
            row.ruleSsid.text = rule.ssid
            row.ruleMac.text = rule.mac
            row.ruleEnabled.isChecked = rule.enabled
            row.ruleEnabled.setOnCheckedChangeListener { _, checked ->
                PrefManager.setRules(PrefManager.getRules().toMutableList().also { it[index] = rule.copy(enabled = checked) })
            }
            row.root.setOnClickListener { _showRuleDialog(index) }
            row.ruleDelete.setOnClickListener {
                PrefManager.setRules(PrefManager.getRules().filterIndexed { i, _ -> i != index })
                _renderRules()
            }
            binding.rulesContainer.addView(row.root)
        }
    }

    private fun _showRuleDialog(index: Int?) {
        val rules = PrefManager.getRules()
        val existing = index?.let { rules.getOrNull(it) }
        val dialogBinding = DialogRuleBinding.inflate(layoutInflater)
        val macEdit: EditText = dialogBinding.edittextRuleMac
        macEdit.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(17))
        macEdit.addTextChangedListener(MacTextWatcher())
        existing?.let {
            dialogBinding.edittextRuleSsid.setText(it.ssid)
            macEdit.setText(it.mac)
        }
        dialogBinding.btnGenerateRuleMac.setOnClickListener { macEdit.setText(MacUtils.generateRandom()) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.rule_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // set here so validation errors keep the dialog open
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val ssid = SsidRules.normalizeSsid(dialogBinding.edittextRuleSsid.text.toString())
                val mac = macEdit.text.toString().uppercase()
                val error = when {
                    ssid.isEmpty() -> R.string.error_empty_ssid
                    rules.withIndex().any { (i, r) -> i != index && r.ssid == ssid } -> R.string.error_duplicate_ssid
                    MacUtils.validate(mac) == MacUtils.ValidationResult.BAD_LENGTH -> R.string.error_bad_length
                    MacUtils.validate(mac) == MacUtils.ValidationResult.ALL_ZEROS -> R.string.error_all_zeros
                    MacUtils.validate(mac) == MacUtils.ValidationResult.ODD_FIRST_OCTET -> R.string.error_odd_first_octet
                    MacUtils.collides(mac, listOf(PrefManager.getApMac())) -> R.string.error_mac_collision
                    else -> null
                }
                if (error != null) {
                    _showError(getString(error))
                    return@setOnClickListener
                }
                val rule = SsidRules.Rule(ssid, mac, existing?.enabled ?: true)
                PrefManager.setRules(if (index != null && existing != null) {
                    rules.toMutableList().also { it[index] = rule }
                } else rules + rule)
                _renderRules()
                Snackbar.make(binding.root, R.string.rule_saved, Snackbar.LENGTH_LONG).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun _setApFieldsVisible(on: Boolean) {
        binding.hotspotFields.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun _setupHotspotCard() {
        val editText = binding.edittextApMac
        editText.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(17))
        editText.addTextChangedListener(MacTextWatcher())

        binding.apOverrideSwitch.setOnCheckedChangeListener { _, checked ->
            _setApFieldsVisible(checked)
            if (updatingUI) return@setOnCheckedChangeListener
            PrefManager.setApOverride(checked)
        }

        binding.btnGenerateApMac.setOnClickListener {
            editText.setText(MacUtils.generateRandom())
        }

        binding.btnSetApMac.setOnClickListener {
            val mac = editText.text.toString().uppercase()
            when (MacUtils.validate(mac)) {
                MacUtils.ValidationResult.BAD_LENGTH ->
                    _showError(getString(R.string.error_bad_length))
                MacUtils.ValidationResult.ALL_ZEROS ->
                    _showError(getString(R.string.error_all_zeros))
                MacUtils.ValidationResult.ODD_FIRST_OCTET ->
                    _showError(getString(R.string.error_odd_first_octet))
                MacUtils.ValidationResult.VALID -> {
                    if (MacUtils.collides(mac, PrefManager.wifiMacs())) {
                        _showError(getString(R.string.error_mac_collision))
                        return@setOnClickListener
                    }
                    PrefManager.setApMac(mac)
                    Snackbar.make(binding.root, R.string.ap_mac_saved, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun _applyMac() {
        sendBroadcast(Intent(WifiServiceHooker.ACTION_APPLY_MAC))
        Snackbar.make(binding.root, R.string.mac_set_success, Snackbar.LENGTH_LONG)
            .setAction(R.string.open_wifi_settings) {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .show()
    }

    private fun _showError(msg: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
