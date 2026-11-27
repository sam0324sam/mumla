package se.lublin.mumla.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import se.lublin.mumla.Settings;

/**
 * Opens the live ski GPS map hosted on mumla-hub (/map) in the device browser.
 */
public class DialogUtils {

    public static void showRosterLocationsDialog(Context context) {
        if (context == null) return;
        String hubUrl = Settings.getInstance(context).getHubUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            Toast.makeText(context, "尚未設定 Mumla Hub 網址", Toast.LENGTH_LONG).show();
            return;
        }
        String mapUrl = hubUrl.trim().replaceAll("/+$", "") + "/map";
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "無法開啟地圖：" + mapUrl, Toast.LENGTH_LONG).show();
        }
    }
}
