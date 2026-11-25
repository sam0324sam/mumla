package se.lublin.mumla.preference;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;

import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.service.TimezoneGuardianManager;
import se.lublin.mumla.util.AppUpdaterManager;
import se.lublin.mumla.util.RemoteLogUploader;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference uploadLogPref = findPreference("action_upload_debug_log");
        if (uploadLogPref != null) {
            uploadLogPref.setOnPreferenceClickListener(preference -> {
                Toast.makeText(requireContext(), R.string.pref_uploading_log, Toast.LENGTH_SHORT).show();
                RemoteLogUploader.uploadRecentLogsAsync(requireContext(), (success, message) -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    }
                });
                return true;
            });
        }

        Preference checkUpdatePref = findPreference("action_check_update_now");
        if (checkUpdatePref != null) {
            checkUpdatePref.setSummary(getString(R.string.pref_check_update_now_sum)
                    + " (" + BuildConfig.GIT_SHA + ")");
            checkUpdatePref.setOnPreferenceClickListener(preference -> {
                AppUpdaterManager.checkAndDownloadUpdate(requireContext(), true, message -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    }
                });
                return true;
            });
        }

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
