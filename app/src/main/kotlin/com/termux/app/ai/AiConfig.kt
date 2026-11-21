package com.termux.app.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.termux.shared.logger.Logger

/**
 * Persistent settings for the optional assistant.
 *
 * Everything lives in [EncryptedSharedPreferences] because the API key is a credential: storing it in
 * plain preferences would expose it to any app with root or a backup.
 *
 * The assistant is off unless the user explicitly enables it and supplies an endpoint.
 */
class AiConfig private constructor(private val context: Context) {

    companion object {
        private const val LOG_TAG = "AiConfig"
        private const val PREFS_NAME = "termux_assistant_secure"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_ROUNDS = "max_rounds"
        private const val KEY_CONFIRM_COMMANDS = "confirm_commands"
        private const val KEY_CONFIRM_FILE_EDITS = "confirm_file_edits"

        /** Presets. All of them speak the OpenAI chat-completions protocol. */
        val PRESETS: List<Preset> = listOf(
            Preset("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat"),
            Preset("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
            Preset(
                "gemini",
                "Gemini (OpenAI compatible)",
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                "gemini-2.0-flash",
            ),
            Preset("ollama", "Ollama (local)", "http://127.0.0.1:11434/v1/chat/completions", "qwen2.5:7b"),
            Preset("custom", "Custom", "", ""),
        )

        const val DEFAULT_MAX_ROUNDS: Int = 12
        const val MIN_MAX_ROUNDS: Int = 1
        const val MAX_MAX_ROUNDS: Int = 30

        @Volatile
        private var instance: AiConfig? = null

        fun get(context: Context): AiConfig =
            instance ?: synchronized(this) {
                instance ?: AiConfig(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Encrypted storage can fail on unusual devices; degrade to plain preferences so the assistant
        // is still usable, but log loudly because the key is then less protected.
        Logger.logError(LOG_TAG, "Encrypted preferences unavailable, falling back to plain: ${e.message}")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (e: Error) {
        // Tink can fail to initialise with an Error rather than an Exception (missing provider, keystore
        // unavailable). Letting that escape would crash whichever screen touched the assistant settings.
        Logger.logError(LOG_TAG, "Encrypted preferences failed to load: ${e.message}")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Whether the user has switched the assistant on. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_URL, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /** Upper bound on tool-calling rounds per user message. */
    var maxRounds: Int
        get() = prefs.getInt(KEY_MAX_ROUNDS, DEFAULT_MAX_ROUNDS)
            .coerceIn(MIN_MAX_ROUNDS, MAX_MAX_ROUNDS)
        set(value) = prefs.edit()
            .putInt(KEY_MAX_ROUNDS, value.coerceIn(MIN_MAX_ROUNDS, MAX_MAX_ROUNDS))
            .apply()

    /** Whether running shell commands requires an explicit confirmation each time. */
    var confirmCommands: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_COMMANDS, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_COMMANDS, value).apply()

    /**
     * Whether editing files requires an explicit confirmation each time.
     *
     * Kept separate from [confirmCommands]: commands are opaque and can do anything, while file edits
     * are reversed by the backup store, so a user may reasonably want approval for one but not the
     * other.
     */
    var confirmFileEdits: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_FILE_EDITS, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_FILE_EDITS, value).apply()

    /** True when the assistant both is enabled and has enough configuration to run. */
    fun isUsable(): Boolean = enabled && apiUrl.isNotBlank() && model.isNotBlank()

    /**
     * A provider preset.
     *
     * @property id stable identifier used for persistence.
     * @property label user-visible name.
     * @property apiUrl default chat-completions endpoint.
     * @property model default model name.
     */
    data class Preset(
        val id: String,
        val label: String,
        val apiUrl: String,
        val model: String,
    )

    /** Applies a preset's defaults, keeping the stored API key. */
    fun applyPreset(preset: Preset) {
        if (preset.apiUrl.isNotBlank()) {
            apiUrl = preset.apiUrl
        }
        if (preset.model.isNotBlank()) {
            model = preset.model
        }
    }

    /** The preset matching the current endpoint, or the custom preset. */
    fun currentPreset(): Preset =
        PRESETS.firstOrNull { it.apiUrl.isNotBlank() && it.apiUrl == apiUrl } ?: PRESETS.last()
}
