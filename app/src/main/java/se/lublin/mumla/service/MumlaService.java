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
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.BatteryManager;
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

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    /** Channel view overlay. */
    private MumlaOverlay mChannelOverlay;
    /** Proximity lock for handset mode. */
    private PowerManager.WakeLock mProximityLock;
    /** Play sound when push to talk key is pressed */
    private boolean mPTTSoundEnabled;
    /** Try to shorten spoken messages when using TTS */
    private boolean mShortTtsMessagesEnabled;
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;

    private TextToSpeech mTTS;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.ERROR)
                logWarning(getString(R.string.tts_failed));
        }
    };

    /** The view representing the hot corner. */
    private MumlaHotCorner mHotCorner;
    private MumlaHotCorner.MumlaHotCornerListener mHotCornerListener = new MumlaHotCorner.MumlaHotCornerListener() {
        @Override
        public void onHotCornerDown() {
            onTalkKeyDown();
        }

        @Override
        public void onHotCornerUp() {
            onTalkKeyUp();
        }
    };

    private static volatile MumlaService sInstance;
    public static MumlaService getInstance() { return sInstance; }

    private BroadcastReceiver mTalkReceiver;
    private PttSoundManager mPttSoundManager;
    private Handler mUiHandler;
    private long mLastIncomingVoiceEndTime = 0;
    private long mLastBusyToastTime = 0;
    private volatile long mLastLocalSoundEndTime = 0;

    public void markLocalSoundPlaying(long durationMs) {
        mLastLocalSoundEndTime = SystemClock.elapsedRealtime() + durationMs;
    }

    // Time announcement (WAV synthesis)
    private volatile long mLastTimeAnnounceTimestamp = 0;
    private MediaPlayer mTimePlayer;

    // Battery monitoring (Thresholds: 80, 60, 40, 20, 10, 5)
    private static final int[] BATTERY_THRESHOLDS = { 80, 60, 40, 20, 10, 5 };
    private BroadcastReceiver mBatteryReceiver;
    private int mLastAnnouncedBatteryThreshold = -1;
    private boolean mBatteryMonitorInitialized = false;
    private int mPendingBatteryResId = 0;
    private MediaPlayer mBatteryPlayer;

    // Time-Out Timer (TOT 60s)
    private static final long TOT_TIMEOUT_MS = 60000;
    private final Handler mTotHandler = new Handler(Looper.getMainLooper());
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

    // Background Logcat Monitor (G393 WindowManager F4 long press & Vol+ in sleep)
    private Thread mLogcatThread;
    private volatile boolean mLogcatRunning = false;
    private Process mLogcatProcess;
    private BroadcastReceiver mAuxCommandReceiver;
    private ToneGenerator mToneGenerator;

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            // Remove old notification left from reconnect,
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
            mNotification = MumlaConnectionNotification.create(MumlaService.this,
                    getString(R.string.mumlaConnecting) + tor,
                    MumlaService.this);
            mNotification.show();

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            if (mNotification != null) {
                final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
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
                                e.getMessage() + (mSettings.isTorEnabled() ? " (Tor)" : ""),
                                isReconnecting(), MumlaService.this);
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null &&
                    user.getTexture() == null) {
                // Request avatar data if available.
                requestAvatar(user.getSession());
            }
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
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened()); // Update settings mute/deafen state
                if(mNotification != null) {
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
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                // Update avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onMessageLogged(IMessage message) {
            // Split on / strip all HTML tags.
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();

            String ttsMessage;
            if(mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    // Get just the domain portion of links
                    String href = anchor.attr("href");
                    // Only shorten anchors without custom text
                    if (href != null && href.equals(anchor.text())) {
                        String urlHostname = HtmlUtils.getHostnameFromLink(href);
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname));
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            String formattedTtsMessage = getString(R.string.notification_message,
                    message.getActorName(), ttsMessage);

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not deafened
            if(mSettings.isTextToSpeechEnabled() &&
                    mTTS != null &&
                    formattedTtsMessage.length() <= TTS_THRESHOLD &&
                    getSessionUser() != null &&
                    !getSessionUser().isSelfDeafened()) {
                mTTS.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null);
            }

            // TODO: create a customizable notification sieve
            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message);
            }

            mMessageLog.add(new IChatMessage.TextMessage(message));
        }

        @Override
        public void onLogInfo(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message));
        }

        @Override
        public void onLogWarning(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message));
        }

        @Override
        public void onLogError(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message));
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
                if (isConnectionEstablished() &&
                        getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                        user.getTalkState() == TalkState.TALKING &&
                        mPTTSoundEnabled) {
                    AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
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
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        setTheme(R.style.Theme_Mumla);

        mMessageLog = new ArrayList<>();
        mMessageNotification = new MumlaMessageNotification(MumlaService.this);

        // Instantiate overlay view
        mChannelOverlay = new MumlaOverlay(this);
        mHotCorner = new MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener);

        // Set up TTS
        if(mSettings.isTextToSpeechEnabled())
            mTTS = new TextToSpeech(this, mTTSInitListener);

        // Initialize zero-latency PTT sound synthesizer
        mPttSoundManager = new PttSoundManager();
        try {
            mToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM, 90);
        } catch (Exception ignored) {}

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
                if ("se.lublin.mumla.action.ANNOUNCE_TIME".equals(action) ||
                    "com.anysecu.pttbridge.action.ANNOUNCE_TIME".equals(action)) {
                    announceCurrentTime();
                } else if ("se.lublin.mumla.action.POCKET_LOCK".equals(action)) {
                    triggerPocketLock();
                }
            }
        };
        IntentFilter auxFilter = new IntentFilter();
        auxFilter.addAction("se.lublin.mumla.action.ANNOUNCE_TIME");
        auxFilter.addAction("com.anysecu.pttbridge.action.ANNOUNCE_TIME");
        auxFilter.addAction("se.lublin.mumla.action.POCKET_LOCK");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mAuxCommandReceiver, auxFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(mAuxCommandReceiver, auxFilter);
        }

        // Start Timezone Guardian (IP auto-calibration & hourly system timezone guard)
        TimezoneGuardianManager.getInstance(this).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    @Override
    public void onDestroy() {
        TimezoneGuardianManager.getInstance(this).stop();
        stopLogcatMonitor();
        if (mBatteryReceiver != null) {
            try { unregisterReceiver(mBatteryReceiver); } catch (Exception ignored) {}
            mBatteryReceiver = null;
        }
        if (mAuxCommandReceiver != null) {
            try { unregisterReceiver(mAuxCommandReceiver); } catch (Exception ignored) {}
            mAuxCommandReceiver = null;
        }
        if (mTimePlayer != null) {
            try { mTimePlayer.release(); } catch (Exception ignored) {}
            mTimePlayer = null;
        }
        if (mBatteryPlayer != null) {
            try { mBatteryPlayer.release(); } catch (Exception ignored) {}
            mBatteryPlayer = null;
        }
        if (mToneGenerator != null) {
            try { mToneGenerator.release(); } catch (Exception ignored) {}
            mToneGenerator = null;
        }
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
        if(mTTS != null) mTTS.shutdown();
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

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true);
        }
        // Configure proximity sensor
        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true);
        }
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        super.onConnectionDisconnected(e);

        // Remove overlay if present.
        mChannelOverlay.hide();

        mHotCorner.setShown(false);

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
                mChannelOverlay.setPushToTalkShown(inputMethod == Constants.TRANSMIT_PUSH_TO_TALK);
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
            case Settings.PREF_HOT_CORNER_KEY:
                mHotCorner.setGravity(mSettings.getHotCornerGravity());
                mHotCorner.setShown(isConnectionEstablished() && mSettings.isHotCornerEnabled());
                break;
            case Settings.PREF_USE_TTS:
                if (mTTS == null && mSettings.isTextToSpeechEnabled())
                    mTTS = new TextToSpeech(this, mTTSInitListener);
                else if (mTTS != null && !mSettings.isTextToSpeechEnabled()) {
                    mTTS.shutdown();
                    mTTS = null;
                }
                break;
            case Settings.PREF_SHORT_TTS_MESSAGES:
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
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
    public void onOverlayToggled() {
        // Ditch notification shade/panel to make overlay presence/permission request visible.
        // But on Android 12 that's no longer allowed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Intent close = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            getApplicationContext().sendBroadcast(close);
        }

        if (!mChannelOverlay.isShown()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getApplicationContext())) {
                    Intent showSetting = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(showSetting);
                    Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
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
    public void setOverlayShown(boolean showOverlay) {
        if(!mChannelOverlay.isShown()) {
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mChannelOverlay.isShown();
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

    /**
     * Checks if ALSA sound hardware is actively playing audio (busy receiving voice).
     */
    public boolean isAlsaBusy() {
        if (SystemClock.elapsedRealtime() < mLastLocalSoundEndTime) {
            return false;
        }

        File asoundDir = new File("/proc/asound");
        if (!asoundDir.exists() || !asoundDir.isDirectory()) {
            return false;
        }
        try {
            File[] cards = asoundDir.listFiles();
            if (cards == null) return false;
            for (File card : cards) {
                if (card.isDirectory() && card.getName().startsWith("card")) {
                    File[] pcms = card.listFiles();
                    if (pcms == null) continue;
                    for (File pcm : pcms) {
                        // CRITICAL FIX: Must be endsWith("p"), NOT contains("p")!
                        // Previously used pcm.getName().contains("p"), which matched "pcm0c" (mic capture) because of "pcm"!
                        if (pcm.isDirectory() && pcm.getName().endsWith("p")) { // Playback only
                            File[] subs = pcm.listFiles();
                            if (subs == null) continue;
                            for (File sub : subs) {
                                File status = new File(sub, "status");
                                if (status.exists() && status.canRead()) {
                                    BufferedReader reader = new BufferedReader(new FileReader(status));
                                    String line = reader.readLine();
                                    reader.close();
                                    if (line != null && line.contains("RUNNING")) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking ALSA busy state", e);
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
            markLocalSoundPlaying(250);
            if (mPttSoundManager != null) {
                mPttSoundManager.playBusyTone();
            }
            showFloorBusyToast("未連線至對講伺服器");
            return;
        }

        // 2. Strict Exclusive Floor Control (搶MIC機制): prevent interrupting active incoming voice
        if (isFloorBusy()) {
            Log.w(TAG, "PTT rejected: Channel is busy with active speaker");
            markLocalSoundPlaying(250);
            if (mPttSoundManager != null) {
                mPttSoundManager.playBusyTone();
            }
            showFloorBusyToast("頻道佔線中");
            return;
        }

        // 3. Grant talk permit
        if (!isTalking()) {
            setTalkingState(true);
            markLocalSoundPlaying(180);
            if (mPttSoundManager != null) {
                mPttSoundManager.playPermitTone();
            }
            mTotHandler.removeCallbacks(mTotRunnable);
            mTotHandler.postDelayed(mTotRunnable, TOT_TIMEOUT_MS);
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
        mTotHandler.removeCallbacks(mTotRunnable);
        if (isTalking()) {
            setTalkingState(false);
            markLocalSoundPlaying(100);
            if (mPttSoundManager != null) {
                mPttSoundManager.playReleaseTone();
            }
            Log.i(TAG, "PTT released: Stopped transmitting and played release tone");

            if (mPendingBatteryResId != 0) {
                final int resId = mPendingBatteryResId;
                mPendingBatteryResId = 0;
                mUiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        playBatteryVoice(resId);
                    }
                }, 400);
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

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }

    // =========================================================================
    // Anysecu PTT Hardware Integration & Auxiliary Functions
    // =========================================================================

    private void playTotWarningTone() {
        markLocalSoundPlaying(500);
        try {
            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 100);
                mUiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (mToneGenerator != null) {
                                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 100);
                            }
                        } catch (Exception ignored) {}
                    }
                }, 140);
                mUiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (mToneGenerator != null) {
                                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 120);
                            }
                        } catch (Exception ignored) {}
                    }
                }, 280);
            }
        } catch (Exception ignored) {}
    }

    public synchronized void triggerPocketLock() {
        if (PocketLockActivity.isLocked()) {
            return;
        }
        Log.i(TAG, "F4 / Volume-Down Long Press -> Triggering Pocket Lock Mode");
        try {
            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
            }
        } catch (Exception ignored) {}

        Intent lockIntent = new Intent(this, PocketLockActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(lockIntent);
    }

    private void startLogcatMonitor() {
        mLogcatRunning = true;
        mLogcatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mLogcatRunning) {
                    try {
                        mLogcatProcess = Runtime.getRuntime().exec(new String[]{
                            "logcat", "-b", "system", "-b", "main", "-T", "1", "-v", "brief", "-s", "WindowManager:D"
                        });
                        BufferedReader reader = new BufferedReader(new InputStreamReader(mLogcatProcess.getInputStream()));
                        String line;
                        while (mLogcatRunning && (line = reader.readLine()) != null) {
                            if (line.contains("unipro.hotkey.ptt.down")) {
                                mUiHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        onTalkKeyDown();
                                    }
                                });
                            } else if (line.contains("unipro.hotkey.ptt.up")) {
                                mUiHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        onTalkKeyUp();
                                    }
                                });
                            } else if (line.contains("MSG_KEY_F4_LONG_PRESS_TIMEOUT")) {
                                mUiHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        triggerPocketLock();
                                    }
                                });
                            } else if (PocketLockActivity.isLocked() && line.contains("keycode=24 ") && line.contains("interactive=false")) {
                                Log.i(TAG, "Volume+ detected while screen asleep -> Wake and announce time directly");
                                mUiHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        announceCurrentTime();
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    } finally {
                        if (mLogcatProcess != null) {
                            mLogcatProcess.destroy();
                            mLogcatProcess = null;
                        }
                    }
                }
            }
        });
        mLogcatThread.setPriority(Thread.MAX_PRIORITY);
        mLogcatThread.start();
    }

    private void stopLogcatMonitor() {
        mLogcatRunning = false;
        if (mLogcatProcess != null) {
            try {
                mLogcatProcess.destroy();
            } catch (Exception ignored) {}
            mLogcatProcess = null;
        }
        if (mLogcatThread != null) {
            mLogcatThread.interrupt();
            mLogcatThread = null;
        }
    }

    public synchronized void announceCurrentTime() {
        if (isTalking()) {
            Log.d(TAG, "Ignore time announcement because PTT is active");
            return;
        }

        long now = System.currentTimeMillis();
        if (now - mLastTimeAnnounceTimestamp < 3000) {
            Log.d(TAG, "Ignore time announcement within debounce window (" + (now - mLastTimeAnnounceTimestamp) + "ms)");
            return;
        }
        mLastTimeAnnounceTimestamp = now;

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mumla:TimeAnnounce");
                wl.acquire(3500);
            }
        } catch (Exception ignored) {}

        try {
            Calendar cal = Calendar.getInstance();
            int hour24 = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            int hour12 = hour24 % 12;
            if (hour12 == 0) {
                hour12 = 12;
            }

            int hourResId = getResources().getIdentifier("hour_" + hour12, "raw", getPackageName());
            int minResId = getResources().getIdentifier("min_" + minute, "raw", getPackageName());

            if (hourResId == 0 || minResId == 0) {
                Log.e(TAG, "Missing audio res for hour=" + hour12 + " min=" + minute);
                return;
            }

            Log.i(TAG, "Announcing concise time: 24h=" + hour24 + " -> " + hour12 + "點 " + minute + "分");
            playSynthesizedWav(new int[]{ hourResId, minResId });
        } catch (Exception e) {
            Log.e(TAG, "Failed to announce time: " + e.getMessage(), e);
        }
    }

    private synchronized void playSynthesizedWav(int[] resIds) {
        markLocalSoundPlaying(3500);
        try {
            ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];

            for (int resId : resIds) {
                InputStream is = getResources().openRawResource(resId);
                ByteArrayOutputStream rawStream = new ByteArrayOutputStream();
                int read;
                while ((read = is.read(buf)) != -1) {
                    rawStream.write(buf, 0, read);
                }
                is.close();

                byte[] rawBytes = rawStream.toByteArray();
                appendPcmData(rawBytes, pcmStream);
            }

            byte[] allPcm = pcmStream.toByteArray();
            int totalPcm = allPcm.length;

            File cacheFile = new File(getCacheDir(), "current_time.wav");
            FileOutputStream fos = new FileOutputStream(cacheFile);
            DataOutputStream dos = new DataOutputStream(fos);

            // WAV Header (44 bytes, 22050Hz, 16bit, Mono)
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(36 + totalPcm));
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16)); // Subchunk1Size
            dos.writeShort(Short.reverseBytes((short) 1)); // AudioFormat = 1 (PCM)
            dos.writeShort(Short.reverseBytes((short) 1)); // NumChannels = 1 (Mono)
            dos.writeInt(Integer.reverseBytes(22050)); // SampleRate
            dos.writeInt(Integer.reverseBytes(44100)); // ByteRate (22050 * 1 * 2)
            dos.writeShort(Short.reverseBytes((short) 2)); // BlockAlign
            dos.writeShort(Short.reverseBytes((short) 16)); // BitsPerSample
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(totalPcm));
            dos.write(allPcm);
            dos.flush();
            dos.close();
            fos.close();

            if (mTimePlayer != null) {
                try {
                    if (mTimePlayer.isPlaying()) {
                        mTimePlayer.stop();
                    }
                    mTimePlayer.release();
                } catch (Exception ignored) {}
                mTimePlayer = null;
            }

            mTimePlayer = new MediaPlayer();
            mTimePlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mTimePlayer.setDataSource(cacheFile.getAbsolutePath());
            mTimePlayer.prepare();
            mTimePlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    try { mp.release(); } catch (Exception ignored) {}
                    if (mTimePlayer == mp) {
                        mTimePlayer = null;
                    }
                }
            });
            mTimePlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Error synthesizing or playing time WAV: " + e.getMessage(), e);
        }
    }

    private void appendPcmData(byte[] rawWav, ByteArrayOutputStream out) {
        for (int i = 12; i < rawWav.length - 8; i++) {
            if (rawWav[i] == 0x64 && rawWav[i + 1] == 0x61 && rawWav[i + 2] == 0x74 && rawWav[i + 3] == 0x61) {
                int dataSize = (rawWav[i + 4] & 0xFF) |
                               ((rawWav[i + 5] & 0xFF) << 8) |
                               ((rawWav[i + 6] & 0xFF) << 16) |
                               ((rawWav[i + 7] & 0xFF) << 24);
                int start = i + 8;
                int len = Math.min(dataSize, rawWav.length - start);
                if (len > 0) {
                    out.write(rawWav, start, len);
                }
                return;
            }
        }
    }

    private void initBatteryMonitoring() {
        if (mBatteryReceiver != null) return;
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;

                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                      status == BatteryManager.BATTERY_STATUS_FULL);

                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (scale <= 0) scale = 100;
                int percent = (int) ((level / (float) scale) * 100);

                handleBatteryUpdate(percent, isCharging);
            }
        };
        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void handleBatteryUpdate(int percent, boolean isCharging) {
        if (!mBatteryMonitorInitialized) {
            mBatteryMonitorInitialized = true;
            for (int t : BATTERY_THRESHOLDS) {
                if (percent <= t) {
                    mLastAnnouncedBatteryThreshold = t;
                }
            }
            Log.i(TAG, "Battery monitor initialized: " + percent + "%, charging=" + isCharging + ", baseThreshold=" + mLastAnnouncedBatteryThreshold);
            return;
        }

        if (isCharging) {
            if (mLastAnnouncedBatteryThreshold != -1 && percent > mLastAnnouncedBatteryThreshold + 3) {
                mLastAnnouncedBatteryThreshold = -1;
            }
            mPendingBatteryResId = 0;
            return;
        }

        for (int t : BATTERY_THRESHOLDS) {
            if (percent <= t && (mLastAnnouncedBatteryThreshold == -1 || mLastAnnouncedBatteryThreshold > t)) {
                mLastAnnouncedBatteryThreshold = t;
                int resId = getBatteryAudioRes(t);
                if (isTalking()) {
                    mPendingBatteryResId = resId;
                    Log.i(TAG, "Battery threshold " + t + "% hit during PTT, deferred.");
                } else {
                    Log.i(TAG, "Battery threshold " + t + "% hit (" + percent + "%), announcing now.");
                    playBatteryVoice(resId);
                }
                break;
            }
        }
    }

    private int getBatteryAudioRes(int threshold) {
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

    private synchronized void playBatteryVoice(int resId) {
        if (resId == 0) return;
        markLocalSoundPlaying(3000);
        try {
            if (mBatteryPlayer != null) {
                try {
                    if (mBatteryPlayer.isPlaying()) {
                        mBatteryPlayer.stop();
                    }
                    mBatteryPlayer.release();
                } catch (Exception ignored) {}
                mBatteryPlayer = null;
            }
            mBatteryPlayer = MediaPlayer.create(this, resId);
            if (mBatteryPlayer != null) {
                mBatteryPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mBatteryPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        try { mp.release(); } catch (Exception ignored) {}
                        if (mBatteryPlayer == mp) {
                            mBatteryPlayer = null;
                        }
                    }
                });
                mBatteryPlayer.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to play battery voice: " + e.getMessage());
        }
    }
}
