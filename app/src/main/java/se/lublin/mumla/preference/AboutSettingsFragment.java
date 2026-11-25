package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;
import static se.lublin.mumla.app.DialogUtils.showAllNewsDialog;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

public class AboutSettingsFragment extends MumlaPreferenceFragment {
    private static final String VERSION_KEY = "version";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_about, rootKey);

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        String buildTime = df.format(new Date(BuildConfig.TIMESTAMP));

        String summary = String.format(Locale.US, "%s (code %d)\nCommit: %s\nBuild: %s (GMT+8)",
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA, buildTime);
        if (BuildConfig.FLAVOR.equals("donation")) {
            summary += String.format("\n*) %s", getString(R.string.donation_thanks));
        }
        Preference versionPreference = getPreferenceScreen().findPreference(VERSION_KEY);
        requireNonNull(versionPreference).setSummary(summary);
        requireNonNull(versionPreference).setOnPreferenceClickListener(preference -> {
            Settings.getInstance(requireContext()).resetNewsShownVersion();
            return true;
        });
        Preference showNewsPreference = getPreferenceScreen().findPreference("showNews");
        requireNonNull(showNewsPreference).setOnPreferenceClickListener(preference -> {
            showAllNewsDialog(requireContext());
            return true;
        });
    }
}
