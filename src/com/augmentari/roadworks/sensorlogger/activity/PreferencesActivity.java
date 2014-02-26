package com.augmentari.roadworks.sensorlogger.activity;

import android.app.Activity;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceFragment;
import com.augmentari.roadworks.sensorlogger.R;

/**
 * Activity for settings (preferences here).
 */
public class PreferencesActivity extends Activity {
    public static final String KEY_PREF_API_BASE_URL = "pref_api_base_url";

    public static class SettingsFragment extends PreferenceFragment {

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.preferences);

            Preference connectionPref = findPreference(KEY_PREF_API_BASE_URL);
            connectionPref.setSummary(getPreferenceScreen().getSharedPreferences().getString(KEY_PREF_API_BASE_URL, ""));

            //correct way for updating preference summary
            connectionPref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {

                    if (newValue instanceof CharSequence) {
                        preference.setSummary((CharSequence) newValue);
                    }

                    return true;
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }

}
