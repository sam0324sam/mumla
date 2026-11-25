package se.lublin.mumla.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

/**
 * Low-resolution / small-screen display clarity helper for Android PoC walkie-talkies
 * (e.g. Anysecu G393 with 128x160 physical panel overridden to 200x240 @ 80dpi).
 *
 * Keeps normal smartphones completely untouched while automatically switching low-res
 * screens to high-contrast dark palette, Cubic-11 pixel font, and scaled legibility.
 */
public final class DisplayClarityHelper {
    private static final String TAG = "DisplayClarityHelper";

    private static final int LOW_RES_MAX_DENSITY_DPI = 120;
    private static final int ULTRA_LOW_DENSITY_DPI = 90;
    private static final int LOW_RES_MAX_SHORT_SIDE_PX = 240;

    private static final float FONT_SCALE_ULTRA_LOW_DPI = 1.95f;
    private static final float FONT_SCALE_LOW_DPI = 1.35f;
    private static final float FONT_SCALE_COMPACT = 1.15f;

    private static final int INDENT_DP_COMPACT = 12;
    private static final int INDENT_DP_DEFAULT = 25;

    private static volatile Typeface sCubicTypeface;

    private DisplayClarityHelper() {
    }

    /**
     * Detects low-resolution / ultra-low-DPI hardware screens without relying on
     * smallestScreenWidthDp (which can be inflated to 400dp+ when firmware overrides
     * density to 80dpi on a 128x160 physical panel).
     */
    public static boolean isLowResHardware(@NonNull Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int shortSidePx = Math.min(dm.widthPixels, dm.heightPixels);
        return dm.densityDpi <= LOW_RES_MAX_DENSITY_DPI || shortSidePx <= LOW_RES_MAX_SHORT_SIDE_PX;
    }

    public static boolean isClarityModeEnabled(@NonNull Context context) {
        String mode = readClarityModePref(context);
        if (Settings.LOW_RES_CLARITY_ON.equals(mode)) {
            return true;
        }
        if (Settings.LOW_RES_CLARITY_OFF.equals(mode)) {
            return false;
        }
        return isLowResHardware(context);
    }

    /**
     * Wraps an Activity base context with an adjusted fontScale when clarity mode is active.
     * Safe to call inside Activity.attachBaseContext().
     */
    @NonNull
    public static Context wrapContext(@NonNull Context base) {
        if (!isClarityModeEnabled(base)) {
            return base;
        }

        DisplayMetrics dm = base.getResources().getDisplayMetrics();
        Configuration currentConfig = base.getResources().getConfiguration();
        float targetScale = resolveTargetFontScale(dm, currentConfig.fontScale);
        if (Math.abs(targetScale - currentConfig.fontScale) < 0.01f) {
            return base;
        }

        Configuration overrideConfig = new Configuration(currentConfig);
        overrideConfig.fontScale = targetScale;
        Log.i(TAG, "Low-res clarity enabled: " + dm.widthPixels + "x" + dm.heightPixels
                + " @" + dm.densityDpi + "dpi -> fontScale=" + targetScale);
        return base.createConfigurationContext(overrideConfig);
    }

    /**
     * Applies the high-contrast Cubic-11 pixel font theme to an Activity before super.onCreate().
     */
    public static void applyActivityTheme(@NonNull Activity activity) {
        if (isClarityModeEnabled(activity)) {
            activity.setTheme(R.style.Theme_Mumla_LowResClarity);
        }
    }

    /**
     * Tightens Toolbar insets and caps title text size so titles never clip vertically on low-res screens.
     */
    public static void applyCompactToolbar(@NonNull Context context, @Nullable Toolbar toolbar) {
        if (toolbar == null || !isClarityModeEnabled(context)) {
            return;
        }
        toolbar.setContentInsetsRelative(4, 4);
        toolbar.setContentInsetStartWithNavigation(0);
        toolbar.setTitleTextAppearance(context, R.style.TextAppearance_Mumla_LowResClarity_ToolbarTitle);
    }

    /**
     * Works around Adreno 304 / msm8909go HWUI OpenGL texture/stride corruption
     * on overridden low-res displays (e.g. 128x160 physical -> 200x240 override)
     * by forcing crisp Skia software rendering on the Activity decor view.
     * Normal smartphones are completely unaffected.
     */
    public static void applySafeRendering(@NonNull Activity activity) {
        if (!isClarityModeEnabled(activity) || !isLowResHardware(activity)) {
            return;
        }
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor != null) {
            decor.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
    }

    /**
     * Applies BOLD / ITALIC style without wiping out the custom Cubic-11 font family.
     */
    public static void applyTypefaceStyle(@NonNull TextView textView, int style) {
        Context context = textView.getContext();
        if (isClarityModeEnabled(context)) {
            Typeface base = getCubicTypeface(context);
            if (base != null) {
                textView.setTypeface(base, style);
            } else {
                textView.setTypeface(textView.getTypeface(), style);
            }
            // Fake bold makes active/self items stand out cleanly on single-weight pixel font
            boolean wantBold = (style & Typeface.BOLD) != 0;
            textView.setPaintFlags(wantBold
                    ? (textView.getPaintFlags() | Paint.FAKE_BOLD_TEXT_FLAG)
                    : (textView.getPaintFlags() & ~Paint.FAKE_BOLD_TEXT_FLAG));
            return;
        }
        textView.setTypeface(null, style);
    }

    public static int getChannelIndentDp(@NonNull Context context) {
        return isClarityModeEnabled(context) ? INDENT_DP_COMPACT : INDENT_DP_DEFAULT;
    }

    @Nullable
    private static Typeface getCubicTypeface(@NonNull Context context) {
        if (sCubicTypeface == null) {
            try {
                sCubicTypeface = ResourcesCompat.getFont(context, R.font.cubic_11);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load Cubic-11 typeface", e);
            }
        }
        return sCubicTypeface;
    }

    private static float resolveTargetFontScale(@NonNull DisplayMetrics dm, float currentScale) {
        float minScale;
        if (dm.densityDpi <= ULTRA_LOW_DENSITY_DPI) {
            minScale = FONT_SCALE_ULTRA_LOW_DPI;
        } else if (dm.densityDpi <= LOW_RES_MAX_DENSITY_DPI) {
            minScale = FONT_SCALE_LOW_DPI;
        } else {
            minScale = FONT_SCALE_COMPACT;
        }
        return Math.max(currentScale, minScale);
    }

    @NonNull
    private static String readClarityModePref(@NonNull Context context) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String value = prefs.getString(Settings.PREF_LOW_RES_CLARITY_MODE, Settings.LOW_RES_CLARITY_AUTO);
            return value != null ? value : Settings.LOW_RES_CLARITY_AUTO;
        } catch (Exception e) {
            // Safe fallback (e.g. Direct Boot before credential-encrypted storage is unlocked)
            return Settings.LOW_RES_CLARITY_AUTO;
        }
    }
}
