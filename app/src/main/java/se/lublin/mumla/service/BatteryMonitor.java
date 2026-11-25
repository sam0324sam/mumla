package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import se.lublin.mumla.R;

/**
 * Dedicated battery & voltage monitor with stabilization tailored for voltage-based
 * fuel gauges on PoC walkie-talkies (e.g., Anysecu G393 / msm8909 VM_BMS):
 *
 * 1. Unplug settle window (75s): ignores charger-removal surface-voltage rebound
 *    while continuously re-baselining the threshold (except critical <= 20%).
 * 2. Lowest-threshold collapse: when battery jumps across multiple thresholds at once
 *    (e.g. 89% -> 55%), announces only the single closest threshold (60%).
 * 3. Discharge debounce (10s): ignores brief RF TX voltage sag dips before announcing
 *    (critical <= 10% announces immediately).
 * 4. PTT deferral: defers voice announcements until active PTT transmission finishes.
 */
public final class BatteryMonitor {
    private static final String TAG = "BatteryMonitor";

    private static final int[] THRESHOLDS = { 80, 60, 40, 20, 10, 5 };
    private static final long UNPLUG_SETTLE_MS = 75_000L;
    private static final long DISCHARGE_DEBOUNCE_MS = 10_000L;
    private static final int CRITICAL_UNPLUG_THRESHOLD = 20;
    private static final int CRITICAL_IMMEDIATE_THRESHOLD = 10;
    private static final int HYSTERESIS_MARGIN_PERCENT = 2;

    public static final class Snapshot {
        public final int percent;
        public final int voltageMv;
        public final boolean isCharging;

        public Snapshot(int percent, int voltageMv, boolean isCharging) {
            this.percent = percent;
            this.voltageMv = voltageMv;
            this.isCharging = isCharging;
        }
    }

    public interface Listener {
        void onBatteryStatusChanged(@NonNull Snapshot snapshot);
        boolean isTalking();
        void onAnnounceThreshold(int threshold, int audioResId, @NonNull Snapshot snapshot);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;

    private BroadcastReceiver mReceiver;
    private boolean mInitialized = false;
    private boolean mWasCharging = false;
    private long mUnplugSettleUntilMs = 0L;
    private int mLastAnnouncedThreshold = -1;
    private Snapshot mLatestSnapshot;

    // Pending debounced threshold check (guards against brief PTT TX voltage sag)
    private int mCandidateThreshold = -1;
    private final Runnable mDebounceRunnable = this::confirmCandidateThreshold;

    // Deferred announcement when threshold triggers while user is transmitting on PTT
    private int mDeferredThreshold = -1;
    private int mDeferredResId = 0;
    private final Runnable mDeferredAnnounceRunnable = this::flushDeferredAnnouncement;

    public BatteryMonitor(@NonNull Context context, @NonNull Handler handler, @NonNull Listener listener) {
        mContext = context.getApplicationContext();
        mHandler = handler;
        mListener = listener;
    }

    public void start() {
        if (mReceiver != null) return;
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Snapshot snap = parseIntent(intent);
                if (snap != null) {
                    onBatteryUpdate(snap);
                }
            }
        };
        mContext.registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    public void stop() {
        mHandler.removeCallbacks(mDebounceRunnable);
        mHandler.removeCallbacks(mDeferredAnnounceRunnable);
        mCandidateThreshold = -1;
        mDeferredThreshold = -1;
        mDeferredResId = 0;
        if (mReceiver != null) {
            try {
                mContext.unregisterReceiver(mReceiver);
            } catch (Exception ignored) {
            }
            mReceiver = null;
        }
    }

    /**
     * Called by MumlaService when the user releases the PTT key so any deferred
     * battery announcement can be played cleanly after the roger tone.
     */
    public void onTalkEnded() {
        if (mDeferredResId != 0) {
            mHandler.removeCallbacks(mDeferredAnnounceRunnable);
            mHandler.postDelayed(mDeferredAnnounceRunnable, 400L);
        }
    }

    @Nullable
    public static Snapshot readSnapshot(@NonNull Context context) {
        try {
            Intent intent = context.getApplicationContext()
                    .registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return parseIntent(intent);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static Snapshot parseIntent(@Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
            return null;
        }
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean isCharging = plugged != 0
                || status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0) return null;
        if (scale <= 0) scale = 100;
        int percent = Math.max(0, Math.min(100, (int) ((level / (float) scale) * 100f)));
        int voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        return new Snapshot(percent, voltageMv, isCharging);
    }

    private void onBatteryUpdate(@NonNull Snapshot snap) {
        mLatestSnapshot = snap;
        mListener.onBatteryStatusChanged(snap);

        if (!mInitialized) {
            mInitialized = true;
            mWasCharging = snap.isCharging;
            mLastAnnouncedThreshold = computeBaselineThreshold(snap.percent);
            Log.i(TAG, "Initialized: " + snap.percent + "% (" + snap.voltageMv + "mV), charging="
                    + snap.isCharging + ", baseThreshold=" + mLastAnnouncedThreshold);
            return;
        }

        if (snap.isCharging) {
            mWasCharging = true;
            mUnplugSettleUntilMs = 0L;
            cancelPendingChecks();
            return;
        }

        long now = SystemClock.elapsedRealtime();

        // Transition from charging -> discharging: start settle window for voltage rebound
        if (mWasCharging) {
            mWasCharging = false;
            mUnplugSettleUntilMs = now + UNPLUG_SETTLE_MS;
            mLastAnnouncedThreshold = computeBaselineThreshold(snap.percent);
            cancelPendingChecks();
            Log.i(TAG, "Unplugged at " + snap.percent + "% (" + snap.voltageMv
                    + "mV), entering " + (UNPLUG_SETTLE_MS / 1000L)
                    + "s settle window, baseThreshold=" + mLastAnnouncedThreshold);
            return;
        }

        // During settle window after unplugging, silently track real settled voltage baseline
        // unless battery is genuinely critically low (<= 20%).
        if (now < mUnplugSettleUntilMs) {
            if (snap.percent > CRITICAL_UNPLUG_THRESHOLD) {
                int newBase = computeBaselineThreshold(snap.percent);
                if (newBase != mLastAnnouncedThreshold) {
                    mLastAnnouncedThreshold = newBase;
                    Log.i(TAG, "Settle window re-baseline: " + snap.percent + "% (" + snap.voltageMv
                            + "mV) -> baseThreshold=" + mLastAnnouncedThreshold);
                }
                return;
            }
            // Genuine critical low battery right after unplug: end settle window immediately
            mUnplugSettleUntilMs = 0L;
        }

        // Find the single lowest threshold newly crossed by current percent
        int targetThreshold = findLowestNewlyCrossedThreshold(snap.percent);
        if (targetThreshold == -1) {
            // If voltage bounced back above candidate threshold + hysteresis, cancel pending debounce
            if (mCandidateThreshold != -1 && snap.percent > mCandidateThreshold + HYSTERESIS_MARGIN_PERCENT) {
                Log.d(TAG, "Voltage recovered to " + snap.percent + "%, cancelling pending "
                        + mCandidateThreshold + "% alert.");
                mHandler.removeCallbacks(mDebounceRunnable);
                mCandidateThreshold = -1;
            }
            return;
        }

        // Critical thresholds (<= 10%) announce immediately; others wait 10s debounce for TX sag
        if (targetThreshold <= CRITICAL_IMMEDIATE_THRESHOLD) {
            mHandler.removeCallbacks(mDebounceRunnable);
            mCandidateThreshold = -1;
            triggerThreshold(targetThreshold, snap);
            return;
        }

        if (mCandidateThreshold != targetThreshold) {
            mCandidateThreshold = targetThreshold;
            mHandler.removeCallbacks(mDebounceRunnable);
            mHandler.postDelayed(mDebounceRunnable, DISCHARGE_DEBOUNCE_MS);
            Log.d(TAG, "Battery threshold " + targetThreshold + "% candidate (" + snap.percent
                    + "% / " + snap.voltageMv + "mV), confirming in " + (DISCHARGE_DEBOUNCE_MS / 1000L) + "s.");
        }
    }

    private void confirmCandidateThreshold() {
        int threshold = mCandidateThreshold;
        mCandidateThreshold = -1;
        Snapshot snap = mLatestSnapshot;
        if (threshold == -1 || snap == null || snap.isCharging) {
            return;
        }
        int lowestCrossed = findLowestNewlyCrossedThreshold(snap.percent);
        if (lowestCrossed != -1) {
            triggerThreshold(lowestCrossed, snap);
        }
    }

    private void triggerThreshold(int threshold, @NonNull Snapshot snap) {
        mLastAnnouncedThreshold = threshold;
        int resId = getAudioRes(threshold);
        if (resId == 0) return;

        if (mListener.isTalking()) {
            mDeferredThreshold = threshold;
            mDeferredResId = resId;
            Log.i(TAG, "Battery threshold " + threshold + "% hit (" + snap.percent + "% / "
                    + snap.voltageMv + "mV) during PTT, deferred.");
        } else {
            Log.i(TAG, "Battery threshold " + threshold + "% hit (" + snap.percent + "% / "
                    + snap.voltageMv + "mV), announcing now.");
            mListener.onAnnounceThreshold(threshold, resId, snap);
        }
    }

    private void flushDeferredAnnouncement() {
        int resId = mDeferredResId;
        int threshold = mDeferredThreshold;
        mDeferredResId = 0;
        mDeferredThreshold = -1;
        Snapshot snap = mLatestSnapshot;
        if (resId != 0 && snap != null && !snap.isCharging) {
            mListener.onAnnounceThreshold(threshold, resId, snap);
        }
    }

    private void cancelPendingChecks() {
        mHandler.removeCallbacks(mDebounceRunnable);
        mHandler.removeCallbacks(mDeferredAnnounceRunnable);
        mCandidateThreshold = -1;
        mDeferredThreshold = -1;
        mDeferredResId = 0;
    }

    private int computeBaselineThreshold(int percent) {
        int baseline = -1;
        for (int t : THRESHOLDS) {
            if (percent <= t) {
                baseline = t;
            }
        }
        return baseline;
    }

    private int findLowestNewlyCrossedThreshold(int percent) {
        int lowest = -1;
        for (int t : THRESHOLDS) {
            if (percent <= t && (mLastAnnouncedThreshold == -1 || mLastAnnouncedThreshold > t)) {
                lowest = t;
            }
        }
        return lowest;
    }

    private static int getAudioRes(int threshold) {
        switch (threshold) {
            case 80: return R.raw.battery_80;
            case 60: return R.raw.battery_60;
            case 40: return R.raw.battery_40;
            case 20: return R.raw.battery_20;
            case 10: return R.raw.battery_10;
            case 5:  return R.raw.battery_5;
            default: return 0;
        }
    }
}
