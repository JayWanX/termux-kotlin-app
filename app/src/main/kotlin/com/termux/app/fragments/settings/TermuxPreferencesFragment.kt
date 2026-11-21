package com.termux.app.fragments.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.termux.R
import com.termux.app.styling.StylingActivity
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

@Keep
class TermuxPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return

        val preferenceManager = preferenceManager
        preferenceManager.preferenceDataStore = TermuxPreferencesDataStore.getInstance(context)

        setPreferencesFromResource(R.xml.termux_preferences, rootKey)

        findPreference<Preference>("styling")?.setOnPreferenceClickListener {
            ActivityUtils.startActivity(context, Intent(context, StylingActivity::class.java))
            true
        }
    }
}

class TermuxPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context
    private val mPreferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context, true)

    companion object {
        @Volatile
        private var instance: TermuxPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TermuxPreferencesDataStore {
            return instance ?: TermuxPreferencesDataStore(context).also { instance = it }
        }
    }
}
