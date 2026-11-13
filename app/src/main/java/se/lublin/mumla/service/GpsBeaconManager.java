package se.lublin.mumla.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;

/**
 * Low-power 5-minute GPS beacon manager for walkie-talkie devices.
 *
 * Key power-saving & reliability features:
 * 1. Samples every 5 minutes (plus initial fix & low-battery last-breath events).
 * 2. Stationary Wi-Fi BSSID gating: avoids waking hardware GPS when stationary at the same AP.
 * 3. Concurrent GPS + Network query with strict 8-second timeout to avoid indoor GPS spin drain,
 *    falling back cleanly to last known coordinates or IP geolocation.
 * 4. Size-1 overwrite queue: while offline, overwrites at most 1 pending record in place and
 *    flushes only that single latest record upon reconnection.
 * 5. Server-wide roster cache + staggered auto-summary when a new client (e.g. phone) joins.
 */
public class GpsBeaconManager {
    private static final String TAG = "GpsBeaconManager";

    public static final long BEACON_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes
    public static final long INITIAL_DELAY_MS = 15 * 1000L;      // 15 seconds after start
    public static final long GPS_FIX_TIMEOUT_MS = 30 * 1000L;    // 30 seconds hardware timeout

    public static final String HEADER_BEACON = "📍 [定位回報]";
    public static final String HEADER_ROSTER = "📋 【全員最近定位紀錄總表】";

    private static final String PREFS_NAME = "mumla_gps_beacon_prefs";
    private static final String KEY_PENDING_OUTGOING = "pending_outgoing_record";
    private static final String KEY_ROSTER_MAP = "roster_locations_json";
    private static final String MASKED_BSSID = "02:00:00:00:00:00";

    private static final Pattern BATTERY_PATTERN = Pattern.compile("🔋\\s*(\\d+)%");
    private static final Pattern COORDS_PATTERN = Pattern.compile("maps\\.google\\.com/\\?q=([-+]?\\d+(?:\\.\\d+)?),([-+]?\\d+(?:\\.\\d+)?)");
    private static final Pattern SOURCE_PATTERN = Pattern.compile("\\(([^()]+)\\)");

    private static volatile GpsBeaconManager sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mMainHandler;
    private final ScheduledExecutorService mExecutor;

    // Size-1 overwrite queue for offline resilience
    private volatile LocationRecord mPendingOutgoingRecord;

    // Persisted roster of last-known locations keyed by username
    private final Map<String, LocationRecord> mRosterLocations = new ConcurrentHashMap<>();

    private MumlaService mService;
    private boolean mStarted = false;
    private volatile long mSynchronizedAtMs = 0L;

    private Location mLastAccurateLocation;
    private String mLastFixBssid;

    private volatile int mLatestBatteryPercent = 100;
    private volatile boolean mLatestIsCharging = false;

    private volatile boolean mFixInProgress = false;
    private LocationListener mActiveListener;
    private Location mActiveNetworkFix;
    private Runnable mFixTimeoutRunnable;
    private long mLastRosterBroadcastAtMs = 0L;

    public static class LocationRecord {
        public String userName;
        public int batteryPercent;
        public boolean isCharging;
        public long timestampMs;
        public double latitude;
        public double longitude;
        public String sourceTag;

        public LocationRecord() {}

        public LocationRecord(String userName, int batteryPercent, boolean isCharging,
                              long timestampMs, double latitude, double longitude, String sourceTag) {
            this.userName = userName;
            this.batteryPercent = batteryPercent;
            this.isCharging = isCharging;
            this.timestampMs = timestampMs;
            this.latitude = latitude;
            this.longitude = longitude;
            this.sourceTag = sourceTag;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("userName", userName != null ? userName : "Unknown");
                obj.put("batteryPercent", batteryPercent);
                obj.put("isCharging", isCharging);
                obj.put("timestampMs", timestampMs);
                obj.put("latitude", latitude);
                obj.put("longitude", longitude);
                obj.put("sourceTag", sourceTag != null ? sourceTag : "定位");
            } catch (Exception ignored) {}
            return obj;
        }

        public static LocationRecord fromJson(JSONObject obj) {
            if (obj == null) return null;
            try {
                if (!obj.has("latitude") || !obj.has("longitude")) return null;
                return new LocationRecord(
                        obj.optString("userName", "Unknown"),
                        obj.optInt("batteryPercent", 0),
                        obj.optBoolean("isCharging", false),
                        obj.optLong("timestampMs", System.currentTimeMillis()),
                        obj.getDouble("latitude"),
                        obj.getDouble("longitude"),
                        obj.optString("sourceTag", "定位")
                );
            } catch (Exception e) {
                return null;
            }
        }
    }

    private final Runnable mPeriodicBeaconRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mStarted) return;
            Log.i(TAG, "5-minute periodic beacon tick triggered.");
            performLocationSample(false, null);
            mMainHandler.postDelayed(this, BEACON_INTERVAL_MS);
        }
    };

    private final Runnable mRosterBroadcastRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mStarted) return;
            postRosterSummaryNow();
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
    }

    /**
     * Start the 5-minute GPS beacon scheduler and load persisted state.
     */
    public synchronized void start(MumlaService service) {
        mService = service;
        if (mStarted) {
            return;
        }
        mStarted = true;
        readInitialBatterySnapshot();
        loadPersistedState();

        mMainHandler.removeCallbacks(mPeriodicBeaconRunnable);
        mMainHandler.postDelayed(mPeriodicBeaconRunnable, INITIAL_DELAY_MS);
        Log.i(TAG, "GpsBeaconManager started (interval=" + (BEACON_INTERVAL_MS / 1000) + "s, loaded roster=" + mRosterLocations.size() + ")");
    }

    /**
     * Stop periodic sampling and release any active location listener.
     */
    public synchronized void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        mMainHandler.removeCallbacks(mPeriodicBeaconRunnable);
        mMainHandler.removeCallbacks(mRosterBroadcastRunnable);
        cancelActiveFixListener();
        mService = null;
        Log.i(TAG, "GpsBeaconManager stopped.");
    }

    public void updateBatteryStatus(int percent, boolean isCharging) {
        mLatestBatteryPercent = Math.max(0, Math.min(100, percent));
        mLatestIsCharging = isCharging;
    }

    /**
     * Trigger an immediate location fix & report (used for low battery 20/10/5% or chat query).
     */
    public void triggerImmediateBeacon(String reasonTag) {
        if (!mStarted) return;
        Log.i(TAG, "Immediate beacon triggered: " + reasonTag);
        mMainHandler.post(() -> performLocationSample(true, reasonTag));
    }

    /**
     * Called when MumlaService finishes Mumble server synchronization.
     * Flushes any pending record in the size-1 queue, or runs an initial fix if none exists.
     */
    public void onServerSynchronized() {
        mSynchronizedAtMs = System.currentTimeMillis();
        mMainHandler.postDelayed(() -> {
            if (!mStarted) return;
            if (mPendingOutgoingRecord != null) {
                Log.i(TAG, "Server synchronized: flushing queued latest record.");
                flushPendingRecordIfConnected();
            } else {
                Log.i(TAG, "Server synchronized with empty queue: running initial fix.");
                performLocationSample(false, null);
            }
        }, 800L);
    }

    /**
     * Called when a user connects to the server. If this device is synchronized,
     * updates the channel description location board (Plan 1) without spamming chat.
     */
    public void onUserJoinedServer(IUser joinedUser) {
        if (!mStarted || joinedUser == null || mService == null || !mService.isConnectionEstablished()) {
            return;
        }
        try {
            updateChannelDescriptionWithRoster(mService.getSessionChannel());
        } catch (Exception ignored) {}
    }

    /**
     * Called when any user's profile/comment is updated.
     * Extracts beacon data if present and updates the server-wide roster cache.
     */
    public void onUserCommentUpdated(IUser user) {
        if (user == null || user.getComment() == null) return;
        String comment = user.getComment();
        if (comment.contains(HEADER_BEACON)) {
            String plain = Jsoup.parseBodyFragment(comment).text().trim();
            LocationRecord parsed = parseSingleBeaconMessage(user.getName(), comment, plain);
            if (parsed != null) {
                updateRosterRecord(parsed);
                if (mService != null) {
                    try {
                        updateChannelDescriptionWithRoster(mService.getSessionChannel());
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * Inspects incoming chat messages:
     * - Updates local roster if it's a beacon message.
     * - Cancels pending staggered summary if another device already posted the roster summary.
     * - Responds to "loc" / "?loc" / "定位" text queries.
     *
     * @return true if the message is a GPS beacon/summary message (so MumlaService suppresses TTS).
     */
    public boolean onIncomingChatMessage(IMessage message) {
        if (message == null || message.getMessage() == null) {
            return false;
        }
        String rawHtml = message.getMessage();
        String plain = Jsoup.parseBodyFragment(rawHtml).text().trim();

        if (plain.contains(HEADER_ROSTER)) {
            mMainHandler.removeCallbacks(mRosterBroadcastRunnable);
            mLastRosterBroadcastAtMs = System.currentTimeMillis();
            parseRosterSummaryIntoCache(rawHtml);
            return true;
        }

        if (plain.contains(HEADER_BEACON)) {
            LocationRecord parsed = parseSingleBeaconMessage(message.getActorName(), rawHtml, plain);
            if (parsed != null) {
                updateRosterRecord(parsed);
            }
            return true;
        }

        if ("loc".equalsIgnoreCase(plain) || "?loc".equalsIgnoreCase(plain) || "定位".equals(plain)) {
            mMainHandler.post(() -> {
                postRosterSummaryNow();
                triggerImmediateBeacon("手動查詢");
            });
            return false;
        }

        return false;
    }

    private void readInitialBatterySnapshot() {
        try {
            Intent statusIntent = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (statusIntent != null) {
                int level = statusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = statusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = statusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (scale <= 0) scale = 100;
                if (level >= 0) {
                    mLatestBatteryPercent = Math.max(0, Math.min(100, (int) ((level / (float) scale) * 100)));
                }
                mLatestIsCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Exception ignored) {}
    }

    private void performLocationSample(boolean forceFreshGps, String customReasonTag) {
        String currentBssid = getConnectedWifiBssid();
        if (!forceFreshGps
                && mLastAccurateLocation != null
                && currentBssid != null
                && currentBssid.equals(mLastFixBssid)) {
            String tag = customReasonTag != null ? customReasonTag : "定點快取";
            Log.i(TAG, "Stationary Wi-Fi BSSID match (" + currentBssid + "), skipping hardware GPS.");
            onLocationResolved(
                    mLastAccurateLocation.getLatitude(),
                    mLastAccurateLocation.getLongitude(),
                    tag,
                    false
            );
            return;
        }

        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing, skipping location sample.");
            return;
        }

        requestHardwareOrNetworkFix(customReasonTag);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void requestHardwareOrNetworkFix(String customReasonTag) {
        LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            Log.w(TAG, "LocationManager not available; skipping location sample.");
            return;
        }

        if (mFixInProgress) {
            Log.d(TAG, "Location fix already in progress, skipping duplicate request.");
            return;
        }

        cancelActiveFixListener();
        mFixInProgress = true;
        mActiveNetworkFix = null;

        boolean gpsEnabled = false;
        boolean netEnabled = false;
        try {
            gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {}
        try {
            netEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {}

        if (!gpsEnabled && !netEnabled) {
            mFixInProgress = false;
            Log.w(TAG, "Both GPS and Network providers disabled; using fallback.");
            resolveFallbackAfterTimeout(lm, customReasonTag);
            return;
        }

        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location == null || !mFixInProgress) return;
                String provider = location.getProvider();
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    Log.i(TAG, "Received hardware GPS fix: " + location.getLatitude() + "," + location.getLongitude());
                    cancelActiveFixListener();
                    mLastAccurateLocation = location;
                    mLastFixBssid = getConnectedWifiBssid();
                    String tag = customReasonTag != null ? customReasonTag : "GPS衛星";
                    onLocationResolved(location.getLatitude(), location.getLongitude(), tag, true);
                } else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
                    mActiveNetworkFix = location;
                    Log.d(TAG, "Buffered Network fix while waiting up to 8s for GPS.");
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };

        mActiveListener = listener;

        try {
            if (gpsEnabled) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper());
            }
            if (netEnabled) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper());
            }
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException requesting location updates: " + se.getMessage());
            cancelActiveFixListener();
            resolveFallbackAfterTimeout(lm, customReasonTag);
            return;
        } catch (Exception e) {
            Log.w(TAG, "Failed requesting location updates: " + e.getMessage());
            cancelActiveFixListener();
            resolveFallbackAfterTimeout(lm, customReasonTag);
            return;
        }

        mFixTimeoutRunnable = () -> {
            if (!mFixInProgress) return;
            Location bufferedNet = mActiveNetworkFix;
            cancelActiveFixListener();
            if (bufferedNet != null) {
                Log.i(TAG, "GPS 8s timeout reached; using Network fix.");
                mLastAccurateLocation = bufferedNet;
                mLastFixBssid = getConnectedWifiBssid();
                String tag = customReasonTag != null ? customReasonTag : "基地台/Wi-Fi";
                onLocationResolved(bufferedNet.getLatitude(), bufferedNet.getLongitude(), tag, true);
            } else {
                Log.i(TAG, "GPS 8s timeout reached with no active fix; checking lastKnown / IP fallback.");
                resolveFallbackAfterTimeout(lm, customReasonTag);
            }
        };
        mMainHandler.postDelayed(mFixTimeoutRunnable, GPS_FIX_TIMEOUT_MS);
    }

    private void cancelActiveFixListener() {
        mFixInProgress = false;
        if (mFixTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mFixTimeoutRunnable);
            mFixTimeoutRunnable = null;
        }
        if (mActiveListener != null) {
            try {
                LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    lm.removeUpdates(mActiveListener);
                }
            } catch (Exception ignored) {}
            mActiveListener = null;
        }
        mActiveNetworkFix = null;
    }

    @SuppressLint("MissingPermission")
    private void resolveFallbackAfterTimeout(LocationManager lm, String customReasonTag) {
        Location bestLast = null;
        if (lm != null && hasLocationPermission()) {
            try {
                Location lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location lastNet = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (lastGps != null && lastNet != null) {
                    bestLast = lastGps.getTime() >= lastNet.getTime() ? lastGps : lastNet;
                } else {
                    bestLast = lastGps != null ? lastGps : lastNet;
                }
            } catch (Exception ignored) {}
        }

        if (bestLast == null) {
            bestLast = mLastAccurateLocation;
        }

        if (bestLast != null) {
            mLastAccurateLocation = bestLast;
            mLastFixBssid = getConnectedWifiBssid();
            String tag = customReasonTag != null ? customReasonTag : "最後已知GPS";
            onLocationResolved(bestLast.getLatitude(), bestLast.getLongitude(), tag, false);
        } else {
            Log.w(TAG, "No GPS fix or last known coordinates available; skipping beacon to avoid fake IP location.");
        }
    }

    /**
     * Updates the Size-1 Overwrite Queue with the latest record and flushes immediately if online.
     */
    private void onLocationResolved(double lat, double lon, String sourceTag, boolean saveAsAccurate) {
        if (saveAsAccurate && mLastAccurateLocation == null) {
            Location loc = new Location("gps");
            loc.setLatitude(lat);
            loc.setLongitude(lon);
            loc.setTime(System.currentTimeMillis());
            mLastAccurateLocation = loc;
        }

        String userName = resolveSelfUserName();
        LocationRecord record = new LocationRecord(
                userName,
                mLatestBatteryPercent,
                mLatestIsCharging,
                System.currentTimeMillis(),
                lat,
                lon,
                sourceTag
        );

        // Size-1 overwrite queue: always overwrite previous unsent record
        mPendingOutgoingRecord = record;
        persistPendingRecord(record);

        flushPendingRecordIfConnected();
    }

    /**
     * Sends the single pending record in the size-1 queue if connected and synchronized.
     * Leaves it in the slot (without duplicating) if currently disconnected.
     */
    public synchronized void flushPendingRecordIfConnected() {
        LocationRecord record = mPendingOutgoingRecord;
        if (record == null) {
            return;
        }
        if (mService == null || !mService.isConnectionEstablished()) {
            Log.i(TAG, "Offline: holding single latest record in size-1 overwrite queue (at " + formatTime(record.timestampMs) + ").");
            return;
        }

        IUser selfUser;
        IChannel selfChannel;
        int selfSession;
        try {
            selfUser = mService.getSessionUser();
            selfChannel = mService.getSessionChannel();
            selfSession = mService.getSessionId();
        } catch (Exception e) {
            Log.d(TAG, "Session not ready yet for flush: " + e.getMessage());
            return;
        }

        if (selfUser == null || selfChannel == null) {
            return;
        }

        record.userName = selfUser.getName();
        mPendingOutgoingRecord = null;
        persistPendingRecord(null);

        updateRosterRecord(record);

        try {
            String htmlMessage = formatSingleRecordHtml(record);
            // Plan 1: Only update user comment (zero chat spam)
            mService.setUserComment(selfSession, htmlMessage);
            // Update channel description location board for all members
            updateChannelDescriptionWithRoster(selfChannel);
            Log.i(TAG, "Flushed GPS beacon for " + record.userName + " (" + record.sourceTag + ", " + record.batteryPercent + "%) to User Comment & Channel Description.");
        } catch (Exception e) {
            Log.w(TAG, "Failed updating user comment/description, restoring to queue: " + e.getMessage());
            mPendingOutgoingRecord = record;
            persistPendingRecord(record);
        }
    }

    /**
     * Updates the current channel description with the persistent roster summary (Plan 1).
     */
    public void updateChannelDescriptionWithRoster(IChannel channel) {
        if (channel == null || mService == null || !mService.isConnectionEstablished() || mRosterLocations.isEmpty()) {
            return;
        }
        try {
            String summaryHtml = formatRosterSummaryHtml();
            mService.setChannelDescription(channel.getId(), summaryHtml);
            Log.i(TAG, "Updated channel " + channel.getId() + " description with roster summary (" + mRosterLocations.size() + " members).");
        } catch (Exception e) {
            Log.d(TAG, "Channel description update skipped: " + e.getMessage());
        }
    }

    private void postRosterSummaryNow() {
        if (mService == null || !mService.isConnectionEstablished()) {
            return;
        }
        IChannel channel;
        try {
            channel = mService.getSessionChannel();
        } catch (Exception e) {
            return;
        }
        if (channel == null || mRosterLocations.isEmpty()) {
            return;
        }
        mLastRosterBroadcastAtMs = System.currentTimeMillis();
        try {
            String summaryHtml = formatRosterSummaryHtml();
            mService.sendChannelTextMessage(channel.getId(), summaryHtml, false);
            Log.i(TAG, "Posted server-wide roster summary (" + mRosterLocations.size() + " members).");
        } catch (Exception e) {
            Log.w(TAG, "Failed posting roster summary: " + e.getMessage());
        }
    }

    private String formatSingleRecordHtml(LocationRecord rec) {
        String latStr = String.format(Locale.US, "%.6f", rec.latitude);
        String lonStr = String.format(Locale.US, "%.6f", rec.longitude);
        String mapsUrl = "https://maps.google.com/?q=" + latStr + "," + lonStr;
        String chargeMark = rec.isCharging ? "⚡" : "";
        return "<b>" + HEADER_BEACON + "</b> " + escapeHtml(rec.userName)
                + " | 🔋 " + rec.batteryPercent + "%" + chargeMark
                + " | 🕒 " + formatTime(rec.timestampMs)
                + " (" + escapeHtml(rec.sourceTag) + ")"
                + "<br/><a href=\"" + mapsUrl + "\">📍 點擊開啟 Google 地圖 (" + latStr + ", " + lonStr + ")</a>";
    }

    private String formatRosterSummaryHtml() {
        Set<String> onlineNames = collectOnlineUserNames();
        List<LocationRecord> sorted = new ArrayList<>(mRosterLocations.values());
        Collections.sort(sorted, new Comparator<LocationRecord>() {
            @Override
            public int compare(LocationRecord a, LocationRecord b) {
                return Long.compare(b.timestampMs, a.timestampMs);
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(HEADER_ROSTER).append("</b>");
        for (LocationRecord rec : sorted) {
            boolean online = onlineNames.contains(rec.userName);
            String statusIcon = online ? "🟢 在線" : "🔴 離線";
            String latStr = String.format(Locale.US, "%.6f", rec.latitude);
            String lonStr = String.format(Locale.US, "%.6f", rec.longitude);
            String mapsUrl = "https://maps.google.com/?q=" + latStr + "," + lonStr;
            sb.append("<br/>• ").append(statusIcon).append(" <b>").append(escapeHtml(rec.userName)).append("</b>")
                    .append(" (電量 ").append(rec.batteryPercent).append("% | ")
                    .append(formatTime(rec.timestampMs)).append(" | ")
                    .append(escapeHtml(rec.sourceTag)).append(") 👉 ")
                    .append("<a href=\"").append(mapsUrl).append("\">📍 地圖 (")
                    .append(latStr).append(", ").append(lonStr).append(")</a>");
        }
        return sb.toString();
    }

    private Set<String> collectOnlineUserNames() {
        Set<String> names = new HashSet<>();
        if (mService == null || !mService.isConnectionEstablished()) {
            return names;
        }
        try {
            IChannel root = mService.getRootChannel();
            if (root != null) {
                collectChannelUsersRecursive(root, names);
            }
        } catch (Exception ignored) {}
        return names;
    }

    private void collectChannelUsersRecursive(IChannel channel, Set<String> names) {
        if (channel == null) return;
        List<? extends IUser> users = channel.getUsers();
        if (users != null) {
            for (IUser u : users) {
                if (u != null && u.getName() != null) {
                    names.add(u.getName());
                }
            }
        }
        List<? extends IChannel> subs = channel.getSubchannels();
        if (subs != null) {
            for (IChannel sub : subs) {
                collectChannelUsersRecursive(sub, names);
            }
        }
    }

    private LocationRecord parseSingleBeaconMessage(String actorName, String rawHtml, String plain) {
        Matcher coordMatcher = COORDS_PATTERN.matcher(rawHtml);
        if (!coordMatcher.find()) {
            return null;
        }
        try {
            double lat = Double.parseDouble(coordMatcher.group(1));
            double lon = Double.parseDouble(coordMatcher.group(2));

            int battery = 0;
            Matcher batMatcher = BATTERY_PATTERN.matcher(plain);
            if (batMatcher.find()) {
                battery = Integer.parseInt(batMatcher.group(1));
            }

            String source = "定位";
            Matcher srcMatcher = SOURCE_PATTERN.matcher(plain);
            if (srcMatcher.find()) {
                source = srcMatcher.group(1);
            }

            String name = actorName;
            int idxHeader = plain.indexOf(HEADER_BEACON);
            int idxPipe = plain.indexOf('|');
            if (idxHeader >= 0 && idxPipe > idxHeader + HEADER_BEACON.length()) {
                String parsedName = plain.substring(idxHeader + HEADER_BEACON.length(), idxPipe).trim();
                if (!parsedName.isEmpty()) {
                    name = parsedName;
                }
            }
            if (name == null || name.isEmpty()) {
                name = "Unknown";
            }

            return new LocationRecord(name, battery, plain.contains("⚡"),
                    System.currentTimeMillis(), lat, lon, source);
        } catch (Exception e) {
            return null;
        }
    }

    private void parseRosterSummaryIntoCache(String rawHtml) {
        String[] lines = rawHtml.split("<br\\s*/?>");
        for (String line : lines) {
            String plainLine = Jsoup.parseBodyFragment(line).text().trim();
            if (!plainLine.contains("maps.google.com/?q=") && !line.contains("maps.google.com/?q=")) {
                continue;
            }
            Matcher coordMatcher = COORDS_PATTERN.matcher(line);
            if (!coordMatcher.find()) continue;
            try {
                double lat = Double.parseDouble(coordMatcher.group(1));
                double lon = Double.parseDouble(coordMatcher.group(2));
                int battery = 0;
                Matcher batMatcher = Pattern.compile("電量\\s*(\\d+)%").matcher(plainLine);
                if (batMatcher.find()) {
                    battery = Integer.parseInt(batMatcher.group(1));
                }
                int start = plainLine.indexOf("在線");
                if (start < 0) start = plainLine.indexOf("離線");
                int paren = plainLine.indexOf('(', Math.max(0, start));
                if (start >= 0 && paren > start + 2) {
                    String name = plainLine.substring(start + 2, paren).trim();
                    if (!name.isEmpty() && !mRosterLocations.containsKey(name)) {
                        mRosterLocations.put(name, new LocationRecord(
                                name, battery, false, System.currentTimeMillis(), lat, lon, "彙整紀錄"));
                        persistRosterMap();
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void updateRosterRecord(LocationRecord record) {
        if (record == null || record.userName == null) return;
        mRosterLocations.put(record.userName, record);
        persistRosterMap();
    }

    private void loadPersistedState() {
        try {
            String pendingStr = mPrefs.getString(KEY_PENDING_OUTGOING, null);
            if (pendingStr != null) {
                mPendingOutgoingRecord = LocationRecord.fromJson(new JSONObject(pendingStr));
            }
        } catch (Exception ignored) {}

        try {
            String rosterStr = mPrefs.getString(KEY_ROSTER_MAP, null);
            if (rosterStr != null) {
                JSONArray arr = new JSONArray(rosterStr);
                for (int i = 0; i < arr.length(); i++) {
                    LocationRecord rec = LocationRecord.fromJson(arr.optJSONObject(i));
                    if (rec != null && rec.userName != null) {
                        mRosterLocations.put(rec.userName, rec);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void persistPendingRecord(LocationRecord record) {
        if (record == null) {
            mPrefs.edit().remove(KEY_PENDING_OUTGOING).apply();
        } else {
            mPrefs.edit().putString(KEY_PENDING_OUTGOING, record.toJson().toString()).apply();
        }
    }

    private void persistRosterMap() {
        try {
            JSONArray arr = new JSONArray();
            for (LocationRecord rec : mRosterLocations.values()) {
                arr.put(rec.toJson());
            }
            mPrefs.edit().putString(KEY_ROSTER_MAP, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private String resolveSelfUserName() {
        if (mService != null) {
            try {
                IUser user = mService.getSessionUser();
                if (user != null && user.getName() != null) {
                    return user.getName();
                }
            } catch (Exception ignored) {}
        }
        if (mPendingOutgoingRecord != null && mPendingOutgoingRecord.userName != null) {
            return mPendingOutgoingRecord.userName;
        }
        return android.os.Build.MODEL != null ? android.os.Build.MODEL : "Radio";
    }

    private String getConnectedWifiBssid() {
        try {
            WifiManager wm = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String bssid = info.getBSSID();
            if (bssid == null || bssid.isEmpty() || MASKED_BSSID.equals(bssid)) {
                return null;
            }
            return bssid;
        } catch (Exception e) {
            return null;
        }
    }

    private String formatTime(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(timestampMs));
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
