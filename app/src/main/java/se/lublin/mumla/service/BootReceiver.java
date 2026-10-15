package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import se.lublin.mumla.app.MumlaActivity;

/**
 * High-reliability BootReceiver for dedicated walkie-talkie devices.
 * Wakes up and auto-launches Mumla immediately on system boot or quick power-on.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "MumlaBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received system boot broadcast: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            try {
                Intent launchIntent = new Intent(context, MumlaActivity.class);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                launchIntent.putExtra(MumlaActivity.EXTRA_AUTO_CONNECT, true);
                context.startActivity(launchIntent);
                Log.i(TAG, "Successfully launched MumlaActivity on system boot");
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch MumlaActivity on boot", e);
            }
        }
    }
}
