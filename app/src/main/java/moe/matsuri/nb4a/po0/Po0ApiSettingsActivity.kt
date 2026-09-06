package moe.matsuri.nb4a.po0

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.ListListener

/** NekoBox-style secondary settings page for complete API endpoints. */
class Po0ApiSettingsActivity : ThemedActivity(R.layout.layout_settings_activity) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.po0_configurations)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, ApiListFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class ApiListFragment : PreferenceFragmentCompat() {
        private var endpoints = emptyList<Po0Protocol.Endpoint>()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            render()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onResume() {
            super.onResume()
            render()
        }

        private fun render() {
            endpoints = try {
                Po0Settings.readEndpoints(requireContext())
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.po0_invalid_configuration, Toast.LENGTH_LONG).show()
                emptyList()
            }

            val screen = preferenceScreen ?: return
            screen.removeAll()
            val category = PreferenceCategory(requireContext()).apply {
                title = getString(R.string.po0_configurations)
            }
            screen.addPreference(category)

            endpoints.forEachIndexed { index, endpoint ->
                category.addPreference(Preference(requireContext()).apply {
                    isPersistent = false
                    title = getString(R.string.po0_api_number, index + 1)
                    summary = Po0Protocol.displayOrigin(endpoint) + "\n" + slotText(endpoint.slot)
                    setOnPreferenceClickListener {
                        showEditor(index)
                        true
                    }
                })
            }

            screen.addPreference(Preference(requireContext()).apply {
                isPersistent = false
                title = getString(R.string.po0_add_api)
                summary = if (endpoints.isEmpty()) getString(R.string.po0_add_api_summary) else null
                isEnabled = endpoints.size < Po0Protocol.MAX_ENDPOINTS
                setIcon(R.drawable.ic_action_note_add)
                setOnPreferenceClickListener {
                    showEditor(null)
                    true
                }
            })
        }

        private fun slotText(slot: Int?): String = if (slot == null) {
            getString(R.string.po0_unpinned_slot)
        } else {
            getString(R.string.po0_fixed_slot, slot)
        }

        private fun showEditor(index: Int?) {
            val current = index?.let { endpoints.getOrNull(it) }
            val content = layoutInflater.inflate(R.layout.po0_api_dialog, null)
            val inputLayout = content.findViewById<TextInputLayout>(R.id.po0_api_url_layout)
            val input = content.findViewById<TextInputEditText>(R.id.po0_api_url)
            val slots = content.findViewById<RadioGroup>(R.id.po0_slot_group)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            input.setText(current?.url.orEmpty())
            current?.let { input.setSelection(input.text?.length ?: 0) }
            slots.check(slotButton(current?.slot))

            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (current == null) R.string.po0_add_api else R.string.po0_edit_api)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.po0_save, null)
            if (current != null) builder.setNeutralButton(R.string.po0_delete, null)
            val dialog = builder.create()
            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    inputLayout.error = null
                    val endpoint = try {
                        Po0Protocol.endpoint(input.text?.toString().orEmpty(), selectedSlot(slots.checkedRadioButtonId))
                    } catch (_: IllegalArgumentException) {
                        inputLayout.error = getString(R.string.po0_api_invalid)
                        return@setOnClickListener
                    }
                    val updated = endpoints.toMutableList().apply {
                        if (index == null) add(endpoint) else this[index] = endpoint
                    }
                    try {
                        Po0Settings.writeEndpoints(requireContext(), updated)
                    } catch (_: IllegalArgumentException) {
                        inputLayout.error = getString(R.string.po0_api_duplicate)
                        return@setOnClickListener
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), R.string.po0_save_failed, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    configurationChanged()
                    dialog.dismiss()
                    render()
                }
                if (current != null) {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage(R.string.po0_delete_confirm)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.po0_delete) { _, _ ->
                                try {
                                    Po0Settings.writeEndpoints(requireContext(), endpoints.filterIndexed { i, _ -> i != index })
                                    configurationChanged()
                                    dialog.dismiss()
                                    render()
                                } catch (_: Exception) {
                                    Toast.makeText(requireContext(), R.string.po0_save_failed, Toast.LENGTH_LONG).show()
                                }
                            }.show()
                    }
                }
            }
            dialog.show()
        }

        private fun configurationChanged() {
            val context = requireContext()
            DataStore.po0WhitelistStatus = ""
            context.sendBroadcast(Intent(Action.PO0_WHITELIST_REFRESH).setPackage(context.packageName))
        }

        private fun slotButton(slot: Int?): Int = when (slot) {
            0 -> R.id.po0_slot_0
            1 -> R.id.po0_slot_1
            2 -> R.id.po0_slot_2
            3 -> R.id.po0_slot_3
            4 -> R.id.po0_slot_4
            else -> R.id.po0_slot_unpinned
        }

        private fun selectedSlot(button: Int): Int? = when (button) {
            R.id.po0_slot_0 -> 0
            R.id.po0_slot_1 -> 1
            R.id.po0_slot_2 -> 2
            R.id.po0_slot_3 -> 3
            R.id.po0_slot_4 -> 4
            else -> null
        }
    }
}
