package se.lublin.mumla.app;

import static androidx.core.text.HtmlCompat.fromHtml;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.service.GpsBeaconManager;

public final class DialogUtils {
    // Preventing instantiation
    private DialogUtils() {
    }

    private static final Map<String, Integer> NEWS_ITEMS;
    private static final List<String> RELEVANT_VERSIONS;

    static {
        // Using LinkedHasMap because order matters
        NEWS_ITEMS = new LinkedHashMap<>();
        // These *must* be added in ascending semver order (1.2.3, 1.10.1, ...)
        NEWS_ITEMS.put("3.7.0", R.string.app_news_items_v3_7_0);
        NEWS_ITEMS.put("3.7.1", R.string.app_news_items_v3_7_1);
        NEWS_ITEMS.put("3.7.2", R.string.app_news_items_v3_7_2);
        NEWS_ITEMS.put("3.7.3", R.string.app_news_items_v3_7_3);
        RELEVANT_VERSIONS = new ArrayList<>();
        for (String candidate : NEWS_ITEMS.keySet()) {
            // Relevant as long as candidate-version <= build-version; otherwise break
            if (new Version(candidate).compareTo(new Version(BuildConfig.VERSIONTAG)) > 0) {
                break;
            }
            RELEVANT_VERSIONS.add(candidate);
        }
    }

    public static boolean maybeShowNewsDialog(Context context) {
        List<String> toShow = new ArrayList<>();
        Set<String> shown = Settings.getInstance(context).getNewsShownVersions();
        for (String version : RELEVANT_VERSIONS) {
            if (!shown.contains(version)) {
                toShow.add(version);
            }
        }

        if (toShow.isEmpty()) {
            return false;
        }

        showNewsDialogForVersions(context, toShow, true);
        return true;
    }

    public static void showAllNewsDialog(Context context) {
        showNewsDialogForVersions(context, RELEVANT_VERSIONS, false);
    }

    private static void showNewsDialogForVersions(Context context, List<String> versions, boolean markShown) {
        StringBuilder sb = new StringBuilder();
        for (int i = versions.size() - 1; i >= 0; i--) {
            String version = versions.get(i);
            Integer resId = NEWS_ITEMS.get(version);
            if (resId == null) {
                continue;
            }
            sb.append(String.format("<b>%s %s</b><br/>", context.getString(R.string.version), version));
            sb.append(context.getString(resId).replace("\n", "<br/>"));
            sb.append("<br/>");
        }

        if (markShown) {
            sb.append(String.format("<em>%s</em>", context.getString(R.string.app_news_footer_on_startup)));
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.dialog_news, null);
        TextView newsTextView = dialogView.findViewById(R.id.news_text_view);
        newsTextView.setText(fromHtml(sb.toString(), 0));

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_news)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (markShown) {
                        Settings.getInstance(context).addNewsShownVersions(versions);
                    }
                    dialog.dismiss();
                })
                .show();
    }

    /**
     * Level 1: Shows a concise, scannable list of team members (online status, battery, relative time).
     * Tapping a member opens Level 2 with full coordinates and a direct Map button.
     */
    public static void showRosterLocationsDialog(Context context) {
        if (context == null) return;
        GpsBeaconManager mgr = GpsBeaconManager.getInstance(context);
        List<GpsBeaconManager.LocationRecord> records = mgr != null ? mgr.getSortedRosterRecords() : null;
        if (records == null || records.isEmpty()) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.action_roster_locations)
                    .setMessage(R.string.roster_locations_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        CharSequence[] items = new CharSequence[records.size()];
        for (int i = 0; i < records.size(); i++) {
            GpsBeaconManager.LocationRecord rec = records.get(i);
            boolean online = mgr.isUserOnline(rec.userName);
            String dotHtml = online
                    ? "<font color='#2E7D32'>●</font>"
                    : "<font color='#888888'>●</font>";
            items[i] = fromHtml(dotHtml + "  <b>" + rec.userName + "</b>", 0);
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.action_roster_locations)
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < records.size()) {
                        showMemberLocationDetailDialog(context, mgr, records.get(which));
                    }
                })
                .setNegativeButton("關閉", null)
                .show();
    }

    /**
     * Level 2: Displays detailed location info for a single selected member with a Map launch button.
     */
    private static void showMemberLocationDetailDialog(
            Context context,
            GpsBeaconManager mgr,
            GpsBeaconManager.LocationRecord rec) {
        if (context == null || rec == null) return;

        boolean online = mgr != null && mgr.isUserOnline(rec.userName);
        String statusBadgeHtml = online
                ? "<font color='#2E7D32'>● 在線</font>"
                : "<font color='#888888'>● 離線</font>";
        String chargeText = rec.isCharging ? " (⚡充電)" : "";
        String latStr = String.format(Locale.US, "%.6f", rec.latitude);
        String lonStr = String.format(Locale.US, "%.6f", rec.longitude);
        String mapsUrl = "https://maps.google.com/?q=" + latStr + "," + lonStr;

        String detailHtml = "<b>🔋 電量：</b>" + rec.batteryPercent + "%" + chargeText + "<br/>"
                + "<b>🕒 時間：</b>" + GpsBeaconManager.formatFullTimeWithRelative(rec.timestampMs) + "<br/>"
                + "<b>🛰️ 來源：</b>" + (rec.sourceTag != null ? rec.sourceTag : "定位") + "<br/>"
                + "<b>🌐 座標：</b>" + latStr + ", " + lonStr;

        TextView textView = new TextView(context);
        float density = context.getResources().getDisplayMetrics().density;
        int padH = (int) (16 * density);
        int padV = (int) (8 * density);
        textView.setPadding(padH, padV, padH, padV);
        textView.setText(fromHtml(detailHtml, 0));
        textView.setTextSize(13.5f);
        textView.setLineSpacing(4f, 1.15f);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(textView);

        boolean isAdmin = mgr != null && mgr.isCurrentUserAdmin();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(fromHtml("📍 <b>" + rec.userName + "</b> (" + statusBadgeHtml + ")", 0))
                .setView(scrollView)
                .setPositiveButton("📍 地圖", (dialog, which) -> {
                    try {
                        Intent mapIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
                        context.startActivity(mapIntent);
                    } catch (Exception e) {
                        Toast.makeText(context, "此裝置未安裝地圖或瀏覽器應用程式", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("⬅️ 返回", (dialog, which) -> showRosterLocationsDialog(context));

        if (isAdmin) {
            builder.setNegativeButton("🗑️ 清除", (dialog, which) -> {
                new MaterialAlertDialogBuilder(context)
                        .setTitle("🗑️ 確認清除定位")
                        .setMessage("確定要清除「" + rec.userName + "」的定位紀錄嗎？")
                        .setPositiveButton("確定清除", (d2, w2) -> {
                            mgr.removeRosterRecordAsAdmin(rec.userName);
                            Toast.makeText(context, "已清除 " + rec.userName + " 的定位紀錄", Toast.LENGTH_SHORT).show();
                            showRosterLocationsDialog(context);
                        })
                        .setNegativeButton("取消", (d2, w2) -> showMemberLocationDetailDialog(context, mgr, rec))
                        .show();
            });
        } else {
            builder.setNegativeButton("關閉", null);
        }

        builder.show();
    }

    private static class Version implements Comparable<Version> {
        final int major;
        final int minor;
        final int patch;

        Version(String versionString) {
            // Removing any "-suffix"
            String[] parts = versionString.split("-")[0].split("\\.");
            int maj = 0, min = 0, pat = 0;
            try {
                if (parts.length > 0) maj = Integer.parseInt(parts[0]);
                if (parts.length > 1) min = Integer.parseInt(parts[1]);
                if (parts.length > 2) pat = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                Log.d("DialogUtils", "Failed to parse version string: " + versionString, e);
            }
            this.major = maj;
            this.minor = min;
            this.patch = pat;
        }

        @Override
        public int compareTo(Version other) {
            if (this.major != other.major) {
                return Integer.compare(this.major, other.major);
            }
            if (this.minor != other.minor) {
                return Integer.compare(this.minor, other.minor);
            }
            return Integer.compare(this.patch, other.patch);
        }
    }
}
