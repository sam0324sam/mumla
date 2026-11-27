package se.lublin.mumla.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import se.lublin.humla.model.IUser;
import se.lublin.mumla.Settings;

/**
 * Lightweight, low-power GPS beacon uploader for ski walkie-talkies.
 *
 * Architecture (decoupled from Mumble voice chat):
 * 1. Samples GPS (with Network fallback) at adaptive intervals:
 *    - 5 min while moving / skiing
 *    - 10 min when stationary outdoors
 *    - 15-30 min on low battery or stationary Wi-Fi
 * 2. Persists up to 30 unsent track points in SharedPreferences so 4G dead zones on
 *    chairlifts never lose full-day ski tracks.
 * 3. HTTP POSTs batched JSON points directly to <hubUrl>/gps with X-Mumla-Token so
 *    phones can view live markers + full-day tracks on <hubUrl>/map.
 */
public class GpsBeaconManager {
    private static final String TAG = "GpsBeaconManager";

    public static final long BEACON_INTERVAL_MS = 5 * 60 * 1000L;
    public static final long STATIONARY_OUTDOOR_INTERVAL_MS = 10 * 60 * 1000L;
    public static final long STATIONARY_WIFI_DEEP_INTERVAL_MS = 30 * 60 * 1000L;
    public static final long LOW_BATTERY_INTERVAL_MS = 15 * 60 * 1000L;
    public static final long CRITICAL_BATTERY_INTERVAL_MS = 30 * 60 * 1000L;
    public static final long MAX_COLD_BACKOFF_INTERVAL_MS = 15 * 60 * 1000L;
    public static final long INITIAL_DELAY_MS = 15 * 1000L;
    public static final long GPS_FIX_TIMEOUT_MS = 45 * 1000L;
    public static final long GPS_COLD_FIX_TIMEOUT_MS = 90 * 1000L;
    private static final float STATIONARY_DISTANCE_METERS = 30.0f;
    private static final int MAX_PENDING_QUEUE = 30;
    private static final String MASKED_BSSID = "02:00:00:00:00:00";

    private static final String PREFS_NAME = "mumla_gps_beacon_prefs";
    private static final String KEY_PENDING_QUEUE = "pending_gps_points_v2";

    private static volatile GpsBeaconManager sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMainHandler;
    private final ScheduledExecutorService mExecutor;

    private final Object mQueueLock = new Object();
    private final List<JSONObject> mPendingQueue = new ArrayList<>();

    private MumlaService mService;
    private boolean mStarted = false;

    private Location mLastAccurateLocation;
    private String mLastFixBssid;
    private int mConsecutiveColdFailures = 0;
    private boolean mOutdoorStationary = false;
    private boolean mWifiStationary = false;

    private volatile int mLatestBatteryPercent = 100;
    private volatile boolean mLatestIsCharging = false;
    private volatile int mLastReportedBatteryPercent = -1;
    private volatile boolean mLastReportedIsCharging = false;

    private volatile boolean mFixInProgress = false;
    private LocationListener mActiveListener;
    private Location mActiveNetworkFix;
    private Runnable mFixTimeoutRunnable;

    private final Runnable mPeriodicBeaconRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mStarted) return;
            performLocationSample(false, null);
            long nextIntervalMs = computeNextBeaconIntervalMs();
            Log.i(TAG, "Next GPS sample scheduled in " + (nextIntervalMs / 60000L) + "m.");
            mMainHandler.postDelayed(this, nextIntervalMs);
        }
    };

    public static GpsBeaconManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (GpsBeaconManager.class) {
                if (sInstance == null) {
                    sInstance = new GpsBeaconManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private GpsBeaconManager(Context context) {
        mContext = context;
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mMainHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GpsBeaconWorker");
            t.setDaemon(true);
            return t;
        });
        loadPendingQueue();
    }

    public synchronized void start(MumlaService service) {
        mService = service;
        if (mStarted) {
            return;
        }
        mStarted = true;
        Log.i(TAG, "GpsBeaconManager started (HTTP Hub mode, queued=" + mPendingQueue.size() + ")");
        mMainHandler.removeCallbacks(mPeriodicBeaconRunnable);
        mMainHandler.postDelayed(mPeriodicBeaconRunnable, INITIAL_DELAY_MS);
        flushPendingQueueAsync();
    }

    public synchronized void stop() {
        if (!mStarted) return;
        mStarted = false;
        mMainHandler.removeCallbacksAndMessages(null);
        cancelActiveFix();
        mService = null;
        Log.i(TAG, "GpsBeaconManager stopped.");
    }

    public void onServerSynchronized() {
        flushPendingQueueAsync();
    }

    public void updateBatteryStatus(int percent, boolean isCharging) {
        mLatestBatteryPercent = percent;
        mLatestIsCharging = isCharging;
    }

    public void triggerImmediateBeacon(String reasonTag) {
        if (!mStarted) return;
        Log.i(TAG, "Immediate GPS beacon requested: " + reasonTag);
        mMainHandler.post(() -> performLocationSample(true, reasonTag));
    }

    public String getConnectedUserName() {
        try {
            if (mService != null && mService.isConnected()) {
                IUser self = mService.HumlaSession().getSessionUser();
                if (self != null && self.getName() != null && !self.getName().trim().isEmpty()) {
                    return self.getName().trim();
                }
            }
        } catch (Exception ignored) {}
        try {
            if (mService != null && mService.getTargetServer() != null) {
                String u = mService.getTargetServer().getUsername();
                if (u != null && !u.trim().isEmpty()) return u.trim();
            }
        } catch (Exception ignored) {}
        String def = Settings.getInstance(mContext).getDefaultUsername();
        return (def != null && !def.trim().isEmpty()) ? def.trim() : "WalkieTalkie";
    }

    private long computeNextBeaconIntervalMs() {
        if (!mLatestIsCharging) {
            if (mLatestBatteryPercent <= 10) return CRITICAL_BATTERY_INTERVAL_MS;
            if (mLatestBatteryPercent <= 20) return LOW_BATTERY_INTERVAL_MS;
        }
        if (mLastAccurateLocation == null && mConsecutiveColdFailures > 0) {
            return Math.min(BEACON_INTERVAL_MS * (mConsecutiveColdFailures + 1), MAX_COLD_BACKOFF_INTERVAL_MS);
        }
        if (mWifiStationary) {
            boolean batterySteady = mLastReportedBatteryPercent >= 0
                    && Math.abs(mLatestBatteryPercent - mLastReportedBatteryPercent) < 2
                    && mLatestIsCharging == mLastReportedIsCharging;
            return batterySteady ? STATIONARY_WIFI_DEEP_INTERVAL_MS : STATIONARY_OUTDOOR_INTERVAL_MS;
        }
        if (mOutdoorStationary) {
            return STATIONARY_OUTDOOR_INTERVAL_MS;
        }
        return BEACON_INTERVAL_MS;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String getCurrentWifiBssid() {
        try {
            WifiManager wm = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String bssid = info.getBSSID();
            if (bssid == null || bssid.isEmpty() || MASKED_BSSID.equals(bssid)) return null;
            return bssid;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private void performLocationSample(boolean forceImmediate, String reasonTag) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing; skipping GPS sample.");
            return;
        }

        // Stationary Wi-Fi gating: avoid waking GPS hardware indoors on the same AP
        String bssid = getCurrentWifiBssid();
        if (!forceImmediate && bssid != null && bssid.equals(mLastFixBssid) && mLastAccurateLocation != null) {
            mWifiStationary = true;
            enqueueAndUpload(mLastAccurateLocation, "Wi-Fi靜止", reasonTag);
            return;
        }
        mWifiStationary = false;

        if (mFixInProgress) {
            return;
        }

        LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        boolean gpsEnabled = false;
        boolean netEnabled = false;
        try { gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
        try { netEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}

        if (!gpsEnabled && !netEnabled) {
            Location last = getBestLastKnownLocation(lm);
            if (last != null) {
                enqueueAndUpload(last, "快取定位", reasonTag);
            }
            return;
        }

        mFixInProgress = true;
        mActiveNetworkFix = null;
        long timeoutMs = (mLastAccurateLocation == null) ? GPS_COLD_FIX_TIMEOUT_MS : GPS_FIX_TIMEOUT_MS;

        mActiveListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                if (loc == null) return;
                if (LocationManager.GPS_PROVIDER.equals(loc.getProvider())) {
                    if (loc.hasAccuracy() && loc.getAccuracy() > 120f) {
                        return;
                    }
                    onFixResolved(loc, "GPS", reasonTag);
                } else {
                    mActiveNetworkFix = loc;
                }
            }

            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            if (gpsEnabled) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, mActiveListener, Looper.getMainLooper());
            }
            if (netEnabled) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, mActiveListener, Looper.getMainLooper());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to request location updates: " + e.getMessage());
            cancelActiveFix();
            return;
        }

        mFixTimeoutRunnable = () -> {
            if (!mFixInProgress) return;
            Location fallback = mActiveNetworkFix != null ? mActiveNetworkFix : getBestLastKnownLocation(lm);
            if (mActiveNetworkFix != null) {
                onFixResolved(mActiveNetworkFix, "網路定位", reasonTag);
            } else if (fallback != null) {
                mConsecutiveColdFailures++;
                onFixResolved(fallback, "最後已知", reasonTag);
            } else {
                mConsecutiveColdFailures++;
                Log.w(TAG, "GPS fix timed out with no fallback (coldFailures=" + mConsecutiveColdFailures + ")");
                cancelActiveFix();
            }
        };
        mMainHandler.postDelayed(mFixTimeoutRunnable, timeoutMs);
    }

    private void onFixResolved(Location loc, String sourceTag, String reasonTag) {
        cancelActiveFix();
        if ("GPS".equals(sourceTag)) {
            mConsecutiveColdFailures = 0;
            if (mLastAccurateLocation != null) {
                float dist = mLastAccurateLocation.distanceTo(loc);
                mOutdoorStationary = dist < STATIONARY_DISTANCE_METERS;
            } else {
                mOutdoorStationary = false;
            }
            mLastAccurateLocation = new Location(loc);
            mLastFixBssid = getCurrentWifiBssid();
        }
        enqueueAndUpload(loc, sourceTag, reasonTag);
    }

    private void cancelActiveFix() {
        mFixInProgress = false;
        if (mFixTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mFixTimeoutRunnable);
            mFixTimeoutRunnable = null;
        }
        if (mActiveListener != null) {
            try {
                LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) lm.removeUpdates(mActiveListener);
            } catch (Exception ignored) {}
            mActiveListener = null;
        }
        mActiveNetworkFix = null;
    }

    @SuppressLint("MissingPermission")
    private Location getBestLastKnownLocation(LocationManager lm) {
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location cand = lm.getLastKnownLocation(p);
                if (cand != null && (best == null || cand.getTime() > best.getTime())) {
                    best = cand;
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private void enqueueAndUpload(Location loc, String sourceTag, String reasonTag) {
        JSONObject point = new JSONObject();
        try {
            point.put("user", getConnectedUserName());
            point.put("lat", loc.getLatitude());
            point.put("lng", loc.getLongitude());
            point.put("ts", System.currentTimeMillis());
            point.put("bat", mLatestBatteryPercent);
            point.put("charging", mLatestIsCharging);
            point.put("src", sourceTag);
            if (loc.hasAltitude()) point.put("alt", loc.getAltitude());
            if (loc.hasAccuracy()) point.put("acc", loc.getAccuracy());
            if (reasonTag != null && !reasonTag.isEmpty()) point.put("note", reasonTag);
        } catch (Exception e) {
            return;
        }

        mLastReportedBatteryPercent = mLatestBatteryPercent;
        mLastReportedIsCharging = mLatestIsCharging;

        synchronized (mQueueLock) {
            mPendingQueue.add(point);
            while (mPendingQueue.size() > MAX_PENDING_QUEUE) {
                mPendingQueue.remove(0);
            }
            savePendingQueueLocked();
        }
        flushPendingQueueAsync();
    }

    private void flushPendingQueueAsync() {
        mExecutor.execute(this::flushPendingQueueSync);
    }

    private void flushPendingQueueSync() {
        List<JSONObject> batch;
        synchronized (mQueueLock) {
            if (mPendingQueue.isEmpty()) return;
            batch = new ArrayList<>(mPendingQueue);
        }

        Settings settings = Settings.getInstance(mContext);
        String hubUrl = settings.getHubUrl();
        if (hubUrl == null || hubUrl.trim().isEmpty()) {
            Log.w(TAG, "Hub URL not configured; keeping " + batch.size() + " GPS point(s) queued.");
            return;
        }
        String endpoint = hubUrl.trim().replaceAll("/+$", "") + "/gps";
        String token = settings.getHubToken();

        HttpURLConnection conn = null;
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject p : batch) arr.put(p);
            byte[] body = arr.toString().getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (token != null && !token.trim().isEmpty()) {
                conn.setRequestProperty("X-Mumla-Token", token.trim());
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                synchronized (mQueueLock) {
                    int removeCount = Math.min(batch.size(), mPendingQueue.size());
                    for (int i = 0; i < removeCount; i++) {
                        mPendingQueue.remove(0);
                    }
                    savePendingQueueLocked();
                }
                Log.i(TAG, "Uploaded " + batch.size() + " GPS point(s) to " + endpoint + " (HTTP " + code + ")");
            } else {
                Log.w(TAG, "GPS upload HTTP " + code + " to " + endpoint + "; will retry later.");
            }
        } catch (Exception e) {
            Log.w(TAG, "GPS upload failed (offline?): " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void loadPendingQueue() {
        synchronized (mQueueLock) {
            mPendingQueue.clear();
            String raw = mPrefs.getString(KEY_PENDING_QUEUE, null);
            if (raw == null || raw.isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null) mPendingQueue.add(obj);
                }
            } catch (Exception ignored) {}
        }
    }

    private void savePendingQueueLocked() {
        JSONArray arr = new JSONArray();
        for (JSONObject p : mPendingQueue) arr.put(p);
        mPrefs.edit().putString(KEY_PENDING_QUEUE, arr.toString()).apply();
    }
}
