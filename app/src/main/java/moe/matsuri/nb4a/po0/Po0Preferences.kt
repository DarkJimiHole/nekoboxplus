package moe.matsuri.nb4a.po0

import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

class Po0Preferences(private val fragment: PreferenceFragmentCompat) {
    private val context get() = fragment.requireContext()
    private val status = fragment.findPreference<Preference>(Po0Settings.STATUS)!!
    private val refresh = fragment.findPreference<Preference>(Po0Settings.REFRESH)!!
    private val configurations = fragment.findPreference<Preference>(Po0Settings.CONFIGURATIONS)!!

    init {
        val enabled = fragment.findPreference<SwitchPreference>(Po0Settings.ENABLED)!!
        enabled.isChecked = DataStore.po0WhitelistEnabled
        enabled.setOnPreferenceChangeListener { _, value ->
            DataStore.po0WhitelistEnabled = value as Boolean
            notifyService()
            renderStatus()
            true
        }
        configurations.setOnPreferenceClickListener {
            context.startActivity(Intent(context, Po0ApiSettingsActivity::class.java))
            true
        }
        refresh.setOnPreferenceClickListener {
            notifyService()
            true
        }
        renderStatus()
    }

    private fun notifyService() {
        context.sendBroadcast(Intent(Action.PO0_WHITELIST_REFRESH).setPackage(context.packageName))
    }

    fun renderStatus() {
        val count = runCatching { Po0Settings.readEndpoints(context).size }.getOrDefault(0)
        configurations.summary = if (count == 0) context.getString(R.string.po0_configurations_empty)
        else context.getString(R.string.po0_configurations_count, count)

        val running = DataStore.serviceState.started
        refresh.isEnabled = running && DataStore.po0WhitelistEnabled && count > 0
        val last = DataStore.po0WhitelistStatus
        status.summary = when {
            !DataStore.po0WhitelistEnabled -> context.getString(R.string.po0_off)
            !running -> context.getString(R.string.po0_paused) + if (last.isBlank()) "" else "\n$last"
            last.isBlank() -> context.getString(R.string.po0_waiting_network)
            else -> last
        }
    }
}
