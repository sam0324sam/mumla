package se.lublin.mumla.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import se.lublin.mumla.Settings;

/**
 * Timezone Guardian for dedicated walkie-talkie devices.
 * Prevents system clock from drifting or resetting to GMT+0 when running over Wi-Fi without SIM/NITZ.
 * 
 * Features:
 * 1. Automatically resolves local timezone based on network IP upon connection, startup, and every hour.
 * 2. Provides toggle for Auto Timezone (IP-based) vs. Forced/Manual Timezone override.
 * 3. Enforces the selected manual timezone hourly when auto is disabled or network is offline.
 * 4. Applies timezone directly to Qualcomm system properties and Android AlarmManager.
 */
public class TimezoneGuardianManager {
    private static final String TAG = "TimezoneGuardian";
    public static final String ACTION_SYNC_TIMEZONE = "se.lublin.mumla.action.TIMEZONE_SYNC";

    private static volatile TimezoneGuardianManager sInstance;

    private final Context mContext;
    private final Settings mSettings;
    private final Handler mMainHandler;
    private final ScheduledExecutorService mExecutor;
    private final AtomicBoolean mIsSyncing = new AtomicBoolean(false);
    private final Set<String> mValidTimezones = new HashSet<>();

    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private BroadcastReceiver mSyncBroadcastReceiver;
    private boolean mStarted = false;
    private long mLastSyncTimestamp = 0;

    public static TimezoneGuardianManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (TimezoneGuardianManager.class) {
                if (sInstance == null) {
                    sInstance = new TimezoneGuardianManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private TimezoneGuardianManager(Context context) {
        mContext = context;
        mSettings = Settings.getInstance(context);
        mMainHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TimezoneGuardianWorker");
            t.setDaemon(true);
            return t;
        });

        // Cache valid Olson timezone IDs
        mValidTimezones.addAll(Arrays.asList(TimeZone.getAvailableIDs()));
    }

    /**
     * Start the guardian: setup hourly alarms, network callbacks, and run immediate sync.
     */
    public synchronized void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        Log.i(TAG, "Starting TimezoneGuardianManager...");

        // 1. Register broadcast receiver for hourly AlarmManager wakeup (and manual testing)
        mSyncBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_SYNC_TIMEZONE.equals(intent.getAction())) {
                    if (intent.hasExtra("auto_timezone")) {
                        boolean auto = intent.getBooleanExtra("auto_timezone", true);
                        mSettings.setAutoTimezoneEnabled(auto);
                        Log.i(TAG, "Updated auto_timezone setting via broadcast: " + auto);
                    }
                    if (intent.hasExtra("manual_timezone")) {
                        String manual = intent.getStringExtra("manual_timezone");
                        if (manual != null && isValidTimezone(manual)) {
                            mSettings.setManualTimezone(manual);
                            Log.i(TAG, "Updated manual_timezone setting via broadcast: " + manual);
                        }
                    }
                    Log.i(TAG, "Received sync broadcast, triggering sync");
                    syncNow();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_SYNC_TIMEZONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mContext.registerReceiver(mSyncBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            mContext.registerReceiver(mSyncBroadcastReceiver, filter);
        }

        // 2. Schedule recurring AlarmManager (every 1 hour) for deep sleep survival
        scheduleHourlyAlarm();

        // 3. Register NetworkCallback for connectivity state changes (e.g. Wi-Fi connected)
        try {
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        Log.i(TAG, "Network became available, scheduling timezone sync...");
                        triggerSyncDelayed(3000); // 3-second delay to ensure DNS/DHCP is stable
                    }
                };
                cm.registerNetworkCallback(request, mNetworkCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register NetworkCallback", e);
        }

        // 4. Also schedule executor fixed delay as in-process fallback
        mExecutor.scheduleWithFixedDelay(this::syncNow, 1, 1, TimeUnit.HOURS);

        // 5. Initial sync
        triggerSyncDelayed(1500);
    }

    /**
     * Stop the guardian and clean up resources.
     */
    public synchronized void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        Log.i(TAG, "Stopping TimezoneGuardianManager...");

        if (mSyncBroadcastReceiver != null) {
            try {
                mContext.unregisterReceiver(mSyncBroadcastReceiver);
            } catch (Exception ignored) {}
            mSyncBroadcastReceiver = null;
        }

        if (mNetworkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(mNetworkCallback);
                }
            } catch (Exception ignored) {}
            mNetworkCallback = null;
        }

        cancelHourlyAlarm();
    }

    /**
     * Trigger a sync after a brief delay (debouncing).
     */
    public void triggerSyncDelayed(long delayMs) {
        mMainHandler.removeCallbacks(this::syncNow);
        mMainHandler.postDelayed(this::syncNow, delayMs);
    }

    /**
     * Run timezone synchronization immediately on a background thread.
     */
    public void syncNow() {
        mExecutor.execute(this::performSync);
    }

    private void performSync() {
        if (!mIsSyncing.compareAndSet(false, true)) {
            Log.d(TAG, "Sync already in progress, skipping.");
            return;
        }

        try {
            boolean autoEnabled = mSettings.isAutoTimezoneEnabled();
            String manualTz = mSettings.getManualTimezone();
            Log.i(TAG, "performSync: autoEnabled=" + autoEnabled + ", manualTz=" + manualTz);

            String targetTz = null;

            if (autoEnabled) {
                // Try resolving via IP
                if (isNetworkConnected()) {
                    targetTz = fetchTimezoneFromIp();
                    if (targetTz != null && isValidTimezone(targetTz)) {
                        Log.i(TAG, "Resolved IP timezone: " + targetTz);
                    } else {
                        Log.w(TAG, "Failed to resolve valid IP timezone, falling back to manual setting");
                        targetTz = manualTz;
                    }
                } else {
                    Log.i(TAG, "Device is offline, enforcing fallback manual timezone: " + manualTz);
                    targetTz = manualTz;
                }
            } else {
                Log.i(TAG, "Auto timezone is disabled by user, enforcing manual timezone: " + manualTz);
                targetTz = manualTz;
            }

            if (targetTz != null && isValidTimezone(targetTz)) {
                applySystemTimezone(targetTz);
                mLastSyncTimestamp = SystemClock.elapsedRealtime();
            } else {
                Log.e(TAG, "Invalid target timezone: " + targetTz);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during timezone sync", e);
        } finally {
            mIsSyncing.set(false);
        }
    }

    /**
     * Query IP timezone with fallback service.
     */
    private String fetchTimezoneFromIp() {
        // Attempt 1: ip-api.com
        String tz = queryHttpTimezone("http://ip-api.com/json/?fields=status,timezone", json -> {
            if ("success".equalsIgnoreCase(json.optString("status"))) {
                return json.optString("timezone", null);
            }
            return null;
        });
        if (tz != null && !tz.isEmpty()) {
            return tz;
        }

        // Attempt 2: worldtimeapi.org
        tz = queryHttpTimezone("https://worldtimeapi.org/api/ip", json -> json.optString("timezone", null));
        return tz;
    }

    private interface JsonParser<T> {
        T parse(JSONObject json) throws Exception;
    }

    private String queryHttpTimezone(String endpoint, JsonParser<String> parser) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mumla-WalkieTalkie/1.0");
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    JSONObject json = new JSONObject(sb.toString());
                    return parser.parse(json);
                }
            } else {
                Log.w(TAG, "HTTP query failed for " + endpoint + " with code " + code);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query timezone from " + endpoint + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    /**
     * Applies the timezone to Android AlarmManager and system property persist.sys.timezone.
     */
    public void applySystemTimezone(String tzId) {
        String currentProp = getSystemProperty("persist.sys.timezone", "");
        String currentDefault = TimeZone.getDefault().getID();
        Log.i(TAG, "applySystemTimezone: target=" + tzId + ", currentDefault=" + currentDefault + ", persist.sys.timezone=" + currentProp);

        // 1. AlarmManager.setTimeZone
        try {
            AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setTimeZone(tzId);
                Log.i(TAG, "AlarmManager.setTimeZone(" + tzId + ") succeeded");
            }
        } catch (SecurityException se) {
            Log.w(TAG, "AlarmManager.setTimeZone requires SET_TIME_ZONE system permission: " + se.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "AlarmManager.setTimeZone failed: " + e.getMessage());
        }

        // 2. Set persist.sys.timezone via SystemProperties reflection
        boolean propSet = false;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method setMethod = sp.getMethod("set", String.class, String.class);
            setMethod.invoke(null, "persist.sys.timezone", tzId);
            propSet = true;
            Log.i(TAG, "Set persist.sys.timezone via SystemProperties.set succeeded: " + tzId);
        } catch (Exception e) {
            Log.w(TAG, "SystemProperties.set failed: " + e.getMessage());
        }

        // 3. Fallback to process exec "setprop persist.sys.timezone"
        if (!propSet || !tzId.equals(getSystemProperty("persist.sys.timezone", ""))) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"setprop", "persist.sys.timezone", tzId});
                p.waitFor();
                Log.i(TAG, "Executed setprop persist.sys.timezone " + tzId);
            } catch (Exception e) {
                Log.e(TAG, "Process exec setprop failed", e);
            }
        }

        // 4. Update JVM default timezone
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(tzId));
        } catch (Exception ignored) {}

        Log.i(TAG, "Timezone Guardian: Applied " + tzId + ". Local time is now: " + new Date());
    }

    private boolean isValidTimezone(String tzId) {
        if (tzId == null || tzId.trim().isEmpty()) {
            return false;
        }
        return mValidTimezones.contains(tzId);
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network network = cm.getActiveNetwork();
                    if (network != null) {
                        NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                        return capabilities != null &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    }
                } else {
                    android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                    return info != null && info.isConnected();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check network connectivity", e);
        }
        return false;
    }

    private String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method getMethod = sp.getMethod("get", String.class, String.class);
            return (String) getMethod.invoke(null, key, def);
        } catch (Exception ignored) {}
        return def;
    }

    private void scheduleHourlyAlarm() {
        try {
            AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                Intent intent = new Intent(ACTION_SYNC_TIMEZONE);
                PendingIntent pi = PendingIntent.getBroadcast(
                        mContext,
                        1001,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
                );

                long triggerAtMillis = SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR;
                am.setInexactRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        AlarmManager.INTERVAL_HOUR,
                        pi
                );
                Log.i(TAG, "Scheduled hourly AlarmManager timezone sync.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule AlarmManager sync", e);
        }
    }

    private void cancelHourlyAlarm() {
        try {
            AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                Intent intent = new Intent(ACTION_SYNC_TIMEZONE);
                PendingIntent pi = PendingIntent.getBroadcast(
                        mContext,
                        1001,
                        intent,
                        PendingIntent.FLAG_NO_CREATE | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
                );
                if (pi != null) {
                    am.cancel(pi);
                    pi.cancel();
                }
            }
        } catch (Exception ignored) {}
    }
}
