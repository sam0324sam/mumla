package se.lublin.mumla.preference;

import android.os.Bundle;

import androidx.preference.Preference;

import se.lublin.mumla.R;
import se.lublin.mumla.service.TimezoneGuardianManager;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference autoTzPref = findPreference(se.lublin.mumla.Settings.PREF_AUTO_TIMEZONE);
        if (autoTzPref != null) {
            autoTzPref.setOnPreferenceChangeListener((preference, newValue) -> {
                TimezoneGuardianManager.getInstance(requireContext()).triggerSyncDelayed(300);
                return true;
            });
        }

        Preference manualTzPref = findPreference(se.lublin.mumla.Settings.PREF_MANUAL_TIMEZONE);
        if (manualTzPref != null) {
            manualTzPref.setOnPreferenceChangeListener((preference, newValue) -> {
                TimezoneGuardianManager.getInstance(requireContext()).triggerSyncDelayed(300);
                return true;
            });
        }
    }
}
