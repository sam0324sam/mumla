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
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private BroadcastReceiver mTalkReceiver;
    private PttSoundManager mPttSoundManager;
    private Handler mUiHandler;
    private long mLastIncomingVoiceEndTime = 0;
    private long mLastBusyToastTime = 0;

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

        // Register PTT broadcast receiver (Anysecu hardware key + Mumla standard IPC)
        mTalkReceiver = new TalkBroadcastReceiver(this);
        IntentFilter pttFilter = new IntentFilter();
        pttFilter.addAction(TalkBroadcastReceiver.BROADCAST_TALK);
        pttFilter.addAction(TalkBroadcastReceiver.BROADCAST_PTT_DOWN);
        pttFilter.addAction(TalkBroadcastReceiver.BROADCAST_PTT_UP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mTalkReceiver, pttFilter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTalkReceiver, pttFilter);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    @Override
    public void onDestroy() {
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
                        if (pcm.isDirectory() && pcm.getName().contains("p")) { // Playback
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
            return true;
        }

        // 2. AudioOutput active speech check
        if (isAudioOutputActive()) {
            return true;
        }

        // 3. Hardware ALSA playback check
        if (isAlsaBusy()) {
            return true;
        }

        // 4. Floor Hang Time (300ms cooldown after remote speech ends)
        long now = SystemClock.elapsedRealtime();
        if (now - mLastIncomingVoiceEndTime < 300) {
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
            setTalkingState(true);
            if (mPttSoundManager != null) {
                mPttSoundManager.playPermitTone();
            }
            Log.i(TAG, "PTT granted: Transmitting and played DMR permit tone");
        }
    }

    /**
     * Called when a user releases a talk key.
     * Stops transmission and plays DMR release roger tone.
     */
    @Override
    public void onTalkKeyUp() {
        Log.d(TAG, "onTalkKeyUp triggered");
        if (isTalking()) {
            setTalkingState(false);
            if (mPttSoundManager != null) {
                mPttSoundManager.playReleaseTone();
            }
            Log.i(TAG, "PTT released: Stopped transmitting and played release tone");
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
}
