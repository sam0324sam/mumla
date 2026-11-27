/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Context;
import se.lublin.mumla.app.PocketLockActivity;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.HtmlUtils;
import se.lublin.mumla.util.PttSoundManager;
import se.lublin.mumla.util.VoiceAnnouncementPlayer;

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
public class MumlaService extends HumlaService implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        MumlaConnectionNotification.OnActionListener,
        MumlaReconnectNotification.OnActionListener, IMumlaService {
    private static final String TAG = MumlaService.class.getName();

    /** Undocumented constant that permits a proximity-sensing wake lock. */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int TTS_THRESHOLD = 250; // Maximum number of characters to read
    public static final int RECONNECT_DELAY = 10000;
    private static final int MAX_MESSAGE_LOG_SIZE = 200; // Prevent unbounded RAM growth during multi-week uptime

    private void appendMessageLog(IChatMessage msg) {
        if (mMessageLog == null) return;
        if (mMessageLog.size() >= MAX_MESSAGE_LOG_SIZE) {
            mMessageLog.remove(0);
        }
        mMessageLog.add(msg);
    }

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    /** Proximity lock for handset mode. */
    private PowerManager.WakeLock mProximityLock;
    /** Play sound when push to talk key is pressed */
    private boolean mPTTSoundEnabled;
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;

    private static volatile MumlaService sInstance;
    public static MumlaService getInstance() { return sInstance; }

    private BroadcastReceiver mTalkReceiver;
    private PttSoundManager mPttSoundManager;
    private Handler mUiHandler;
    private long mLastIncomingVoiceEndTime = 0;
    private long mLastBusyToastTime = 0;
    private volatile long mLastLocalSoundEndTime = 0;

    public synchronized void markLocalSoundPlaying(long durationMs) {
        long end = SystemClock.elapsedRealtime() + durationMs;
        if (end > mLastLocalSoundEndTime) {
            mLastLocalSoundEndTime = end;
        }
    }

    // High-speed cached ALSA playback status nodes (zero-allocation scan + Move-to-Front)
    private static final Set<String> PREFERRED_ALSA_PCM_NODES = new HashSet<>(Arrays.asList(
        "pcm0p", "pcm1p", "pcm3p", "pcm4p", "pcm12p", "pcm14p", "pcm18p", "pcm36p"
    ));
    private static final byte[] RUNNING_ASCII = { 'R', 'U', 'N', 'N', 'I', 'N', 'G' };
    private String[] mAlsaPlaybackPaths = new String[0];
    private final byte[] mAlsaReadBuf = new byte[32];

    // Dedicated in-memory WAV player for time & battery voice announcements
    private VoiceAnnouncementPlayer mVoiceAnnouncementPlayer;
    // Short CPU wakelock for lock tone playback while screen turns off
    private PowerManager.WakeLock mToneWakeLock;

    // Battery & voltage monitor (unplug settle window, TX-sag debounce, PTT deferral)
    private BatteryMonitor mBatteryMonitor;

    // Time-Out Timer (TOT 60s)
    private static final long TOT_TIMEOUT_MS = 60000;
    private final Runnable mTotRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTalking()) {
                Log.w(TAG, "TOT Limit (60s) reached! Force talk OFF to free channel");
                onTalkKeyUp();
                playTotWarningTone();
                showFloorBusyToast("發話超時 (60s) 強制切斷");
            }
        }
    };

    // Background Logcat Monitor (G393 WindowManager PTT, F4 long press, & Vol+ in sleep)
    private Thread mLogcatThread;
    private volatile boolean mLogcatRunning = false;
    private Process mLogcatProcess;
    private BroadcastReceiver mAuxCommandReceiver;

    // Pre-allocated runnables for zero-allocation logcat dispatch
    private final Runnable mLogcatTalkDownRunnable = this::onTalkKeyDown;
    private final Runnable mLogcatTalkUpRunnable = this::onTalkKeyUp;
    private final Runnable mLogcatPocketLockRunnable = this::triggerPocketLock;
    private final Runnable mLogcatVolDownClickRunnable = PocketLockActivity::notifyHardwareVolumeDownClick;
    private final Runnable mLogcatAnnounceTimeRunnable = this::announceCurrentTime;

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            // Remove old notification left from reconnect,
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            mNotification = MumlaConnectionNotification.create(MumlaService.this,
                    getString(R.string.mumlaConnecting),
                    MumlaService.this);
            mNotification.show();

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            if (mNotification != null) {
                mNotification.setCustomContentText(getString(R.string.connected));
                mNotification.setActionsShown(true);
                mNotification.show();
            }
        }

        @Override
        public void onDisconnected(HumlaException e) {
            if (mNotification != null) {
                mNotification.hide();
                mNotification = null;
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification =
                        MumlaReconnectNotification.show(MumlaService.this,
                                e.getMessage(),
                                isReconnecting(), MumlaService.this);
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            // Skip automatic avatar blob fetching on walkie-talkie to save bandwidth and RAM
            if (user.getCommentHash() != null && user.getComment() == null) {
                // Automatically fetch user comment (>128 bytes) to sync GPS beacons.
                requestComment(user.getSession());
            }
            GpsBeaconManager.getInstance(MumlaService.this).onUserJoinedServer(user);
            GpsBeaconManager.getInstance(MumlaService.this).onUserCommentUpdated(user);
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (user == null) {
                return;
            }

            int selfSession;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                if (user.getChannel() != null && user.getChannel().getId() > 0) {
                    saveLastChannel(user.getChannel().getId());
                }
                boolean effectiveMuted = user.isSelfMuted() || user.isSelfDeafened();
                if (effectiveMuted != mSettings.isMuted() || user.isSelfDeafened() != mSettings.isDeafened()) {
                    mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened());
                }
                if (mNotification != null) {
                    String contentText;
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        contentText = getString(R.string.status_notify_muted_and_deafened);
                    else if (user.isSelfMuted())
                        contentText = getString(R.string.status_notify_muted);
                    else
                        contentText = getString(R.string.connected);
                    mNotification.setCustomContentText(contentText);
                    mNotification.show();
                }
                // Skip requesting/re-parsing our own comment blob (GpsBeaconManager already persisted it locally)
                return;
            }

            // Skip automatic avatar blob fetching to save RAM/bandwidth on walkie-talkie
            if (user.getCommentHash() != null && user.getComment() == null) {
                // Automatically fetch other users' updated comment (>128 bytes) to sync GPS beacons.
                requestComment(user.getSession());
            }

            GpsBeaconManager.getInstance(MumlaService.this).onUserCommentUpdated(user);
        }

        @Override
        public void onMessageLogged(IMessage message) {
            boolean isGpsMessage = GpsBeaconManager.getInstance(MumlaService.this).onIncomingChatMessage(message);
            if (isGpsMessage && message != null && message.getMessage() != null
                    && message.getMessage().contains(GpsBeaconManager.HEADER_CLEAR_USER)) {
                // Silent admin purge control command: do not show chat notification or clutter chat log.
                return;
            }

            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message);
            }

            appendMessageLog(new IChatMessage.TextMessage(message));
        }

        @Override
        public void onLogInfo(String message) {
            appendMessageLog(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message));
        }

        @Override
        public void onLogWarning(String message) {
            appendMessageLog(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message));
        }

        @Override
        public void onLogError(String message) {
            appendMessageLog(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message));
        }

        @Override
        public void onPermissionDenied(String reason) {
            if(mNotification != null && !mSuppressNotifications) {
                mNotification.show();
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            int selfSession = -1;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
            }

            if (user.getSession() == selfSession) {
                if (mPttSoundManager == null &&
                        isConnectionEstablished() &&
                        getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                        user.getTalkState() == TalkState.TALKING &&
                        mPTTSoundEnabled) {
                    AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (audioManager != null) {
                        audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.15f);
                    }
                }
            } else {
                // Incoming voice from another user in current channel
                IChannel currentChannel = getSessionChannel();
                if (currentChannel != null && user.getChannel() != null &&
                        user.getChannel().getId() == currentChannel.getId()) {
                    if (user.getTalkState() != TalkState.PASSIVE) {
                        // Someone else is speaking in our channel!
                        // If we are currently transmitting, arbitrate collision: yield floor immediately!
                        if (isTalking()) {
                            Log.w(TAG, "Floor conflict detected! Yielding floor to remote user: " + user.getName());
                            setTalkingState(false);
                            if (mPttSoundManager != null) {
                                mPttSoundManager.playBusyTone();
                            }
                            showFloorBusyToast("搶MIC失敗，頻道已被佔用");
                        }
                    } else {
                        // Remote speaker stopped speaking -> record floor release cooldown time
                        mLastIncomingVoiceEndTime = SystemClock.elapsedRealtime();
                    }
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mUiHandler = new Handler(Looper.getMainLooper());
        registerObserver(mObserver);

        // Register for preference changes
        mSettings = Settings.getInstance(this);
        mPTTSoundEnabled = mSettings.isPttSoundEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        mMessageLog = new ArrayList<>();
        mMessageNotification = new MumlaMessageNotification(MumlaService.this);

        // Initialize zero-latency PTT sound synthesizer, voice announcement player & cached ALSA paths
        mPttSoundManager = new PttSoundManager(this::markLocalSoundPlaying);
        mVoiceAnnouncementPlayer = new VoiceAnnouncementPlayer(this, this::markLocalSoundPlaying);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                mToneWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mumla:TonePlayback");
                mToneWakeLock.setReferenceCounted(false);
            }
        } catch (Exception ignored) {}
        initAlsaPlaybackPaths();

        // Register PTT broadcast receiver (Anysecu hardware key + Mumla standard IPC)
        mTalkReceiver = new TalkBroadcastReceiver(this);
        IntentFilter pttFilter = new IntentFilter();
        pttFilter.addAction(TalkBroadcastReceiver.BROADCAST_TALK);
        pttFilter.addAction(TalkBroadcastReceiver.BROADCAST_PTT_DOWN);
        pttFilter.addAction(TalkBroadcastReceiver.BROADCAST_PTT_UP);
        pttFilter.addAction("android.intent.action.PTT.down");
        pttFilter.addAction("android.intent.action.PTT.up");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mTalkReceiver, pttFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTalkReceiver, pttFilter);
        }

        sInstance = this;
        initBatteryMonitoring();
        startLogcatMonitor();

        // Register auxiliary broadcast commands (ANNOUNCE_TIME, POCKET_LOCK)
        mAuxCommandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();
                if ("se.lublin.mumla.action.ANNOUNCE_TIME".equals(action)) {
                    announceCurrentTime();
                } else if ("se.lublin.mumla.action.POCKET_LOCK".equals(action)) {
                    triggerPocketLock();
                }
            }
        };
        IntentFilter auxFilter = new IntentFilter();
        auxFilter.addAction("se.lublin.mumla.action.ANNOUNCE_TIME");
        auxFilter.addAction("se.lublin.mumla.action.POCKET_LOCK");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mAuxCommandReceiver, auxFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(mAuxCommandReceiver, auxFilter);
        }

        // Start Timezone Guardian (IP auto-calibration & hourly system timezone guard)
        TimezoneGuardianManager.getInstance(this).start();
        // Start Low-Power 5-Minute GPS Beacon Manager
        GpsBeaconManager.getInstance(this).start(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    @Override
    public void onDestroy() {
        GpsBeaconManager.getInstance(this).stop();
        TimezoneGuardianManager.getInstance(this).stop();
        stopLogcatMonitor();
        if (mBatteryMonitor != null) {
            mBatteryMonitor.stop();
            mBatteryMonitor = null;
        }
        if (mAuxCommandReceiver != null) {
            try { unregisterReceiver(mAuxCommandReceiver); } catch (Exception ignored) {}
            mAuxCommandReceiver = null;
        }
        if (mUiHandler != null) {
            mUiHandler.removeCallbacksAndMessages(null);
        }
        if (mVoiceAnnouncementPlayer != null) {
            mVoiceAnnouncementPlayer.release();
            mVoiceAnnouncementPlayer = null;
        }
        releaseToneWakeLock();
        if (sInstance == this) {
            sInstance = null;
        }

        if (mNotification != null) {
            mNotification.hide();
            mNotification = null;
        }
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        if (mPttSoundManager != null) {
            mPttSoundManager.release();
            mPttSoundManager = null;
        }

        unregisterObserver(mObserver);
        mMessageLog = null;
        mMessageNotification.dismiss();
        super.onDestroy();
    }

    @Override
    public void onConnectionSynchronized() {
        try {
            super.onConnectionSynchronized();
        } catch (RuntimeException e) {
            Log.d(TAG, "exception in onConnectionSynchronized: " + e);
            return;
        }

        // Restore mute/deafen state
        if(mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened());
        }

        // Configure proximity sensor
        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true);
        }

        GpsBeaconManager.getInstance(this).onServerSynchronized();
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        super.onConnectionDisconnected(e);

        setProximitySensorOn(false);

        clearMessageLog();
        mMessageNotification.dismiss();
    }

    /**
     * Called when the user makes a change to their preferences.
     * Should update all preferences relevant to the service.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key) {
            case Settings.PREF_INPUT_METHOD:
                /* Convert input method defined in settings to an integer format used by Humla. */
                int inputMethod = mSettings.getHumlaInputMethod();
                changedExtras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
                break;
            case Settings.PREF_HANDSET_MODE:
                setProximitySensorOn(isConnectionEstablished() && mSettings.isHandsetMode());
                changedExtras.putInt(HumlaService.EXTRAS_AUDIO_STREAM, mSettings.isHandsetMode() ?
                                     AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
            case Settings.PREF_THRESHOLD:
                changedExtras.putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD,
                        mSettings.getDetectionThreshold());
                break;
            case Settings.PREF_AMPLITUDE_BOOST:
                changedExtras.putFloat(EXTRAS_AMPLITUDE_BOOST,
                        mSettings.getAmplitudeBoostMultiplier());
                break;
            case Settings.PREF_HALF_DUPLEX:
                changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
                break;
            case Settings.PREF_PREPROCESSOR_ENABLED:
                changedExtras.putBoolean(EXTRAS_ENABLE_PREPROCESSOR,
                        mSettings.isPreprocessorEnabled());
                break;
            case Settings.PREF_ECHO_CANCELLATION_METHOD:
                changedExtras.putString(EXTRAS_ECHO_CANCELLATION_METHOD,
                        mSettings.getEchoCancellationMethod());
                break;
            case Settings.PREF_PTT_SOUND:
                mPTTSoundEnabled = mSettings.isPttSoundEnabled();
                break;
            case Settings.PREF_INPUT_QUALITY:
                changedExtras.putInt(EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
                break;
            case Settings.PREF_INPUT_RATE:
                changedExtras.putInt(EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
                break;
            case Settings.PREF_FRAMES_PER_PACKET:
                changedExtras.putInt(EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
                break;
            case Settings.PREF_CERT_ID:
            case Settings.PREF_FORCE_TCP:
            case Settings.PREF_USE_TOR:
            case Settings.PREF_DISABLE_OPUS:
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true;
                break;
        }
        if (changedExtras.size() > 0) {
            try {
                // Reconfigure the service appropriately.
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show();
        }
    }

    private void setProximitySensorOn(boolean on) {
        if(on) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            mProximityLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity");
            mProximityLock.acquire();
        } else {
            if(mProximityLock != null) mProximityLock.release();
            mProximityLock = null;
        }
    }

    @Override
    public void onMuteToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            boolean muted = !user.isSelfMuted();
            boolean deafened = user.isSelfDeafened() && muted;
            setSelfMuteDeafState(muted, deafened);
        }
    }

    @Override
    public void onDeafenToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened(), !user.isSelfDeafened());
        }
    }

    @Override
    public void onReconnectNotificationDismissed() {
        mErrorShown = true;
    }

    @Override
    public void reconnect() {
        connect();
    }

    @Override
    public void cancelReconnect() {
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
        super.cancelReconnect();
    }

    @Override
    public void clearChatNotifications() {
        mMessageNotification.dismiss();
    }

    @Override
    public void markErrorShown() {
        mErrorShown = true;
        // Dismiss the reconnection prompt if a reconnection isn't in progress.
        if (mReconnectNotification != null && !isReconnecting()) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
    }

    @Override
    public boolean isErrorShown() {
        return mErrorShown;
    }

    private void showFloorBusyToast(final String message) {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastBusyToastTime > 1500) {
            mLastBusyToastTime = now;
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void initAlsaPlaybackPaths() {
        List<String> preferred = new ArrayList<>();
        List<String> allFound = new ArrayList<>();
        File asoundDir = new File("/proc/asound");
        if (asoundDir.exists() && asoundDir.isDirectory()) {
            File[] cards = asoundDir.listFiles();
            if (cards != null) {
                for (File card : cards) {
                    if (!card.isDirectory() || !card.getName().startsWith("card")) continue;
                    File[] pcms = card.listFiles();
                    if (pcms == null) continue;
                    for (File pcm : pcms) {
                        // Playback nodes strictly end with "p" (never "c" capture nodes)
                        String pcmName = pcm.getName();
                        if (!pcm.isDirectory() || !pcmName.endsWith("p")) continue;
                        File[] subs = pcm.listFiles();
                        if (subs == null) continue;
                        for (File sub : subs) {
                            File status = new File(sub, "status");
                            if (status.exists() && status.canRead()) {
                                String path = status.getAbsolutePath();
                                allFound.add(path);
                                if (PREFERRED_ALSA_PCM_NODES.contains(pcmName)) {
                                    preferred.add(path);
                                }
                            }
                        }
                    }
                }
            }
        }
        List<String> selected = !preferred.isEmpty() ? preferred : allFound;
        mAlsaPlaybackPaths = selected.toArray(new String[0]);
        Log.i(TAG, "Cached " + mAlsaPlaybackPaths.length + " ALSA playback status paths (filtered from " + allFound.size() + " total).");
    }

    private static boolean containsRunningAscii(byte[] buf, int len) {
        int limit = len - RUNNING_ASCII.length;
        for (int i = 0; i <= limit; i++) {
            if (buf[i] == 'R') {
                boolean match = true;
                for (int j = 1; j < RUNNING_ASCII.length; j++) {
                    if (buf[i + j] != RUNNING_ASCII[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) return true;
            }
        }
        return false;
    }

    /**
     * Zero-allocation, sub-millisecond hardware ALSA playback status check with Move-to-Front caching.
     */
    public boolean isAlsaBusy() {
        if (SystemClock.elapsedRealtime() < mLastLocalSoundEndTime) {
            return false;
        }
        String[] paths = mAlsaPlaybackPaths;
        if (paths == null || paths.length == 0) {
            return false;
        }
        synchronized (mAlsaReadBuf) {
            for (int i = 0; i < paths.length; i++) {
                String path = paths[i];
                try (FileInputStream fis = new FileInputStream(path)) {
                    int n = fis.read(mAlsaReadBuf);
                    // Idle nodes start with "closed"; skip immediately on first byte 'c'
                    if (n >= RUNNING_ASCII.length && mAlsaReadBuf[0] != 'c' && containsRunningAscii(mAlsaReadBuf, n)) {
                        if (i > 0) {
                            // Move-to-Front heuristic: keep active playback node at index 0
                            paths[i] = paths[0];
                            paths[0] = path;
                        }
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    /**
     * Checks if any other user in the current channel is actively talking (not PASSIVE).
     */
    public boolean isChannelBusy() {
        try {
            if (!isSynchronized()) return false;
            IChannel channel = getSessionChannel();
            if (channel == null) return false;
            int selfSession = getSessionId();
            for (IUser user : channel.getUsers()) {
                if (user.getSession() != selfSession && user.getTalkState() != TalkState.PASSIVE) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking channel talk state", e);
        }
        return false;
    }

    /**
     * Comprehensive 4-tier check to determine if the channel floor is busy:
     * 1. Channel state: Any remote user in the current channel is speaking (TALKING/SHOUTING/WHISPERING).
     * 2. AudioOutput state: Actively decoding or playing voice stream packets within last 400ms.
     * 3. Hardware ALSA state: Linux sound card playback is in RUNNING state.
     * 4. Floor Hang Time: Guard interval (300ms) after the last speaker stopped to avoid clipping tail.
     */
    public boolean isFloorBusy() {
        // 1. Channel users check
        if (isChannelBusy()) {
            Log.w(TAG, "Floor busy: Channel has active remote speaker");
            return true;
        }

        // 2. AudioOutput active speech check
        if (isAudioOutputActive()) {
            Log.w(TAG, "Floor busy: AudioOutput is receiving/playing remote voice");
            return true;
        }

        // 3. Hardware ALSA playback check
        if (isAlsaBusy()) {
            Log.w(TAG, "Floor busy: Hardware ALSA playback is RUNNING");
            return true;
        }

        // 4. Floor Hang Time (300ms cooldown after remote speech ends)
        long now = SystemClock.elapsedRealtime();
        if (now - mLastIncomingVoiceEndTime < 300) {
            Log.w(TAG, "Floor busy: Floor Hang Time cooldown (300ms)");
            return true;
        }

        return false;
    }

    public boolean isChannelOrAlsaBusy() {
        return isFloorBusy();
    }

    /**
     * Called when a user presses a talk key down (Anysecu PTT hardware key or screen widget).
     * Enforces Strict Exclusive Floor Control (搶MIC機制) and plays Talk Permit Tone when granted.
     */
    @Override
    public void onTalkKeyDown() {
        Log.d(TAG, "onTalkKeyDown triggered");

        // 0. Auto-switch to PTT mode defensively if not already in PTT
        if (getTransmitMode() != Constants.TRANSMIT_PUSH_TO_TALK) {
            Log.i(TAG, "onTalkKeyDown: Auto-correcting transmit mode to PUSH_TO_TALK");
            try {
                mSettings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
                Bundle extras = new Bundle();
                extras.putInt(EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK);
                configureExtras(extras);
            } catch (Exception e) {
                Log.w(TAG, "Error switching to PTT mode", e);
            }
        }

        // 1. Connection check: if not established or synchronized, reject with busy/reject tone
        if (!isConnectionEstablished() || !isSynchronized()) {
            Log.w(TAG, "PTT rejected: Client not connected or not synchronized yet");
            if (mPttSoundManager != null) {
                mPttSoundManager.playBusyTone();
            }
            showFloorBusyToast("未連線至對講伺服器");
            return;
        }

        // 2. Strict Exclusive Floor Control (搶MIC機制): prevent interrupting active incoming voice
        if (isFloorBusy()) {
            Log.w(TAG, "PTT rejected: Channel is busy with active speaker");
            if (mPttSoundManager != null) {
                mPttSoundManager.playBusyTone();
            }
            showFloorBusyToast("頻道佔線中");
            return;
        }

        // 3. Grant talk permit
        if (!isTalking()) {
            // Stop any active local voice announcement or lingering static tone BEFORE
            // starting hardware mic capture, which triggers Qualcomm HAL stream invalidation.
            if (mVoiceAnnouncementPlayer != null) {
                mVoiceAnnouncementPlayer.stop();
            }
            if (mPttSoundManager != null) {
                mPttSoundManager.stopAllActiveTones();
            }
            setTalkingState(true);
            if (mPttSoundManager != null) {
                mPttSoundManager.playPermitTone();
            }
            mUiHandler.removeCallbacks(mTotRunnable);
            mUiHandler.postDelayed(mTotRunnable, TOT_TIMEOUT_MS);
            Log.i(TAG, "PTT granted: Transmitting and played DMR permit tone (TOT 60s started)");
        }
    }

    /**
     * Called when a user releases a talk key.
     * Stops transmission and plays DMR release roger tone.
     */
    @Override
    public void onTalkKeyUp() {
        Log.d(TAG, "onTalkKeyUp triggered");
        mUiHandler.removeCallbacks(mTotRunnable);
        if (isTalking()) {
            setTalkingState(false);
            if (mPttSoundManager != null) {
                mPttSoundManager.playReleaseTone();
            }
            Log.i(TAG, "PTT released: Stopped transmitting and played release tone");

            if (mBatteryMonitor != null) {
                mBatteryMonitor.onTalkEnded();
            }
        }
    }

    @Override
    public List<IChatMessage> getMessageLog() {
        return Collections.unmodifiableList(mMessageLog);
    }

    @Override
    public void clearMessageLog() {
        if (mMessageLog != null) {
            mMessageLog.clear();
        }
    }

    /**
     * Sets whether or not notifications should be suppressed.
     *
     * It's typically a good idea to do this when the main activity is foreground, so that the user
     * is not bombarded with redundant alerts.
     *
     * <b>Chat notifications are NOT suppressed.</b> They may be if a chat indicator is added in the
     * activity itself. For now, the user may disable chat notifications manually.
     *
     * @param suppressNotifications true if Mumla is to disable notifications.
     */
    @Override
    public void setSuppressNotifications(boolean suppressNotifications) {
        mSuppressNotifications = suppressNotifications;
    }

    public static class MumlaBinder extends Binder {
        private final MumlaService mService;

        private MumlaBinder(MumlaService service) {
            mService = service;
        }

        public IMumlaService getService() {
            return mService;
        }
    }

    @Override
    public Message sendUserTextMessage(int session, String message) {
        Message msg = super.sendUserTextMessage(session, message);

        appendMessageLog(new IChatMessage.TextMessage(msg));
        return msg;
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);

        appendMessageLog(new IChatMessage.TextMessage(msg));
        return msg;
    }

    // =========================================================================
    // Anysecu PTT Hardware Integration & Auxiliary Functions
    // =========================================================================

    private void playTotWarningTone() {
        if (mPttSoundManager != null) {
            mPttSoundManager.playTotWarningTone();
        }
    }

    public void playPocketUnlockAckTone() {
        if (mPttSoundManager != null) {
            mPttSoundManager.playUnlockAckTone();
        }
    }

    public synchronized void triggerPocketLock() {
        if (PocketLockActivity.isLocked()) {
            return;
        }
        Log.i(TAG, "F4 / Volume-Down Long Press -> Triggering Pocket Lock Mode");
        acquireToneWakeLock(800);

        if (mPttSoundManager != null) {
            mPttSoundManager.playLockTone();
        }

        Intent lockIntent = new Intent(this, PocketLockActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(lockIntent);
    }

    private void startLogcatMonitor() {
        mLogcatRunning = true;
        mLogcatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mLogcatRunning) {
                    Process proc = null;
                    try {
                        proc = Runtime.getRuntime().exec(new String[]{
                            "logcat", "-b", "system", "-b", "main", "-T", "1", "-v", "raw", "-s", "WindowManager:D"
                        });
                        mLogcatProcess = proc;
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                            String line;
                            while (mLogcatRunning && (line = reader.readLine()) != null) {
                                if (line.contains("unipro.hotkey.ptt.down")) {
                                    mUiHandler.post(mLogcatTalkDownRunnable);
                                } else if (line.contains("unipro.hotkey.ptt.up")) {
                                    mUiHandler.post(mLogcatTalkUpRunnable);
                                } else if (line.contains("MSG_KEY_F4_LONG_PRESS_TIMEOUT")) {
                                    mUiHandler.post(mLogcatPocketLockRunnable);
                                } else if (PocketLockActivity.isLocked() && line.contains("unipro.hotkey.p3.down")) {
                                    mUiHandler.post(mLogcatVolDownClickRunnable);
                                } else if (PocketLockActivity.isLocked() && line.contains("keycode=24 ") && line.contains("interactive=false")) {
                                    Log.i(TAG, "Volume+ detected while screen asleep -> Wake and announce time directly");
                                    mUiHandler.post(mLogcatAnnounceTimeRunnable);
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (!mLogcatRunning) break;
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    } finally {
                        destroyProcessQuietly(proc);
                        if (mLogcatProcess == proc) {
                            mLogcatProcess = null;
                        }
                    }
                }
            }
        }, "MumlaLogcatMonitor");
        mLogcatThread.setPriority(Thread.MAX_PRIORITY);
        mLogcatThread.start();
    }

    private static void destroyProcessQuietly(Process proc) {
        if (proc == null) return;
        try { proc.getInputStream().close(); } catch (Exception ignored) {}
        try { proc.getErrorStream().close(); } catch (Exception ignored) {}
        try { proc.getOutputStream().close(); } catch (Exception ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                proc.destroyForcibly();
            } else {
                proc.destroy();
            }
        } catch (Exception ignored) {}
    }

    private void stopLogcatMonitor() {
        mLogcatRunning = false;
        Process proc = mLogcatProcess;
        mLogcatProcess = null;
        destroyProcessQuietly(proc);
        if (mLogcatThread != null) {
            mLogcatThread.interrupt();
            mLogcatThread = null;
        }
    }

    private void acquireToneWakeLock(long timeoutMs) {
        PowerManager.WakeLock wl = mToneWakeLock;
        if (wl != null) {
            try {
                wl.acquire(timeoutMs);
            } catch (Exception ignored) {}
        }
    }

    private void releaseToneWakeLock() {
        PowerManager.WakeLock wl = mToneWakeLock;
        if (wl != null) {
            try {
                if (wl.isHeld()) {
                    wl.release();
                }
            } catch (Exception ignored) {}
        }
    }

    public void announceCurrentTime() {
        if (mVoiceAnnouncementPlayer != null) {
            mVoiceAnnouncementPlayer.announceCurrentTime(isTalking());
        }
    }

    private void initBatteryMonitoring() {
        if (mBatteryMonitor != null) return;
        mBatteryMonitor = new BatteryMonitor(this, mUiHandler, new BatteryMonitor.Listener() {
            @Override
            public void onBatteryStatusChanged(@androidx.annotation.NonNull BatteryMonitor.Snapshot snapshot) {
                GpsBeaconManager.getInstance(MumlaService.this)
                        .updateBatteryStatus(snapshot.percent, snapshot.isCharging);
            }

            @Override
            public boolean isTalking() {
                return MumlaService.this.isTalking();
            }

            @Override
            public void onAnnounceThreshold(int threshold, int audioResId,
                                            @androidx.annotation.NonNull BatteryMonitor.Snapshot snapshot) {
                playBatteryVoice(audioResId);
                if (threshold <= 20) {
                    GpsBeaconManager.getInstance(MumlaService.this)
                            .triggerImmediateBeacon("低電量" + threshold + "%告警");
                }
            }
        });
        mBatteryMonitor.start();
    }

    private void playBatteryVoice(final int resId) {
        if (mVoiceAnnouncementPlayer != null) {
            mVoiceAnnouncementPlayer.playBatteryVoice(resId);
        }
    }
}
