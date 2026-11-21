package com.termux.app.ai

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.termux.R

/**
 * Settings for the optional assistant.
 *
 * Kept as a plain preference screen rather than a bespoke UI: it is a small amount of configuration and
 * a preference screen is the least surprising place for a user to find it.
 *
 * The assistant stays disabled by default. Enabling it is an explicit act that also implies consent to
 * send terminal contents to the configured endpoint.
 */
class AssistantPreferencesFragment : PreferenceFragmentCompat() {

    private lateinit var config: AiConfig

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        config = AiConfig.get(requireContext())
        preferenceManager.sharedPreferencesName = null
        setPreferencesFromResource(R.xml.assistant_preferences, rootKey)

        bindEnabledSwitch()
        bindPreset()
        bindApiUrl()
        bindApiKey()
        bindModel()
        bindRounds()
        bindConfirmCommands()
    }

    private fun bindEnabledSwitch() {
        findPreference<SwitchPreferenceCompat>("assistant_enabled")?.apply {
            isChecked = config.enabled
            setOnPreferenceChangeListener { _, value ->
                config.enabled = value as Boolean
                if (config.enabled && !config.isUsable()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.assistant_enable_needs_config,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                true
            }
        }
    }

    private fun bindPreset() {
        findPreference<ListPreference>("assistant_preset")?.apply {
            entries = AiConfig.PRESETS.map { it.label }.toTypedArray()
            entryValues = AiConfig.PRESETS.map { it.id }.toTypedArray()
            value = config.currentPreset().id
            setOnPreferenceChangeListener { _, value ->
                val preset = AiConfig.PRESETS.firstOrNull { it.id == value }
                if (preset != null) {
                    config.applyPreset(preset)
                    // Reflect the preset's endpoint and model in the summary fields.
                    findPreference<EditTextPreference>("assistant_api_url")?.let {
                        it.text = config.apiUrl
                        it.summary = config.apiUrl
                    }
                    findPreference<EditTextPreference>("assistant_model")?.let {
                        it.text = config.model
                        it.summary = config.model
                    }
                }
                true
            }
        }
    }

    private fun bindApiUrl() {
        findPreference<EditTextPreference>("assistant_api_url")?.apply {
            text = config.apiUrl
            summary = config.apiUrl.ifBlank { getString(R.string.assistant_api_url_empty) }
            setOnPreferenceChangeListener { _, value ->
                config.apiUrl = value as String
                summary = config.apiUrl.ifBlank { getString(R.string.assistant_api_url_empty) }
                true
            }
        }
    }

    private fun bindApiKey() {
        findPreference<EditTextPreference>("assistant_api_key")?.apply {
            text = config.apiKey
            summary = maskKey(config.apiKey)
            setOnPreferenceChangeListener { _, value ->
                config.apiKey = value as String
                summary = maskKey(config.apiKey)
                true
            }
        }
    }

    private fun bindModel() {
        findPreference<EditTextPreference>("assistant_model")?.apply {
            text = config.model
            summary = config.model.ifBlank { getString(R.string.assistant_model_empty) }
            setOnPreferenceChangeListener { _, value ->
                config.model = value as String
                summary = config.model.ifBlank { getString(R.string.assistant_model_empty) }
                true
            }
        }
    }

    private fun bindRounds() {
        findPreference<EditTextPreference>("assistant_max_rounds")?.apply {
            text = config.maxRounds.toString()
            summary = config.maxRounds.toString()
            setOnPreferenceChangeListener { _, value ->
                val parsed = (value as String).toIntOrNull()
                if (parsed == null) return@setOnPreferenceChangeListener false
                config.maxRounds = parsed
                text = config.maxRounds.toString()
                summary = config.maxRounds.toString()
                true
            }
        }
    }

    private fun bindConfirmCommands() {
        findPreference<SwitchPreferenceCompat>("assistant_confirm_commands")?.apply {
            isChecked = config.confirmCommands
            setOnPreferenceChangeListener { _, value ->
                config.confirmCommands = value as Boolean
                true
            }
        }
        findPreference<SwitchPreferenceCompat>("assistant_confirm_file_edits")?.apply {
            isChecked = config.confirmFileEdits
            setOnPreferenceChangeListener { _, value ->
                config.confirmFileEdits = value as Boolean
                true
            }
        }
    }

    /** Shows only the first and last few characters of a key. */
    private fun maskKey(key: String): String = when {
        key.isBlank() -> getString(R.string.assistant_api_key_empty)
        key.length <= 8 -> "••••••••"
        else -> key.take(4) + "…" + key.takeLast(4)
    }

    companion object {
        /** Convenience helper for prompting for a single value, used by other screens if needed. */
        fun promptForValue(
            fragment: PreferenceFragmentCompat,
            title: String,
            initial: String,
            onValue: (String) -> Unit,
        ) {
            val context = fragment.requireContext()
            val input = EditText(context).apply {
                setText(initial)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val container = LinearLayout(context).apply {
                setPadding(48, 16, 48, 0)
                addView(input)
            }
            AlertDialog.Builder(context)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ -> onValue(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
