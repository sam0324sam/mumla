package se.lublin.mumla.preference;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.DrawerAdapter;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.util.DisplayClarityHelper;

public class SettingsActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(DisplayClarityHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DisplayClarityHelper.applyActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        DisplayClarityHelper.applySafeRendering(this);

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        DisplayClarityHelper.applyCompactToolbar(this, toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.action_settings);
        }

        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!getSupportFragmentManager().popBackStackImmediate()) {
                    // Finish activity if nothing was popped from stack
                    finish();
                }
            }
        });

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new RootPreferenceFragment())
                    .commit();
        }

        getSupportFragmentManager().setFragmentResultListener("launchFragment", this, (requestKey, result) -> {
            String fragmentClassName = result.getString("fragmentClassName");
            if (fragmentClassName != null) {
                try {
                    Class<?> fragmentClass = Class.forName(fragmentClassName);
                    Fragment fragment = (Fragment) fragmentClass.newInstance();
                    // Pass it on to the fragment so its onResume can set the title, see MumlaPreferenceFragment
                    fragment.setArguments(result);
                    getSupportFragmentManager()
                            .beginTransaction()
                            .replace(R.id.settings_container, fragment)
                            .addToBackStack(null)
                            .commit();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (Settings.PREF_LOW_RES_CLARITY_MODE.equals(key)) {
            recreate();
        }
    }

    public static class RootPreferenceFragment extends MumlaPreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preference_headers, rootKey);
            Preference serverInfoPref = findPreference("server_info");
            if (serverInfoPref != null) {
                serverInfoPref.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(requireContext(), MumlaActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_INFO);
                    startActivity(intent);
                    requireActivity().finish();
                    return true;
                });
            }
        }
    }
}
