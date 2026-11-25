package se.lublin.mumla.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import se.lublin.mumla.R;

/**
 * Dedicated in-memory PCM voice announcement engine for concise time & battery alerts.
 *
 * Responsibilities:
 * - Decodes raw 16kHz 16-bit mono WAV resources in RAM (zero Flash wear, <20ms latency).
 * - Owns its own single-thread worker, AudioTrack lifecycle, and short CPU WakeLock.
 * - Automatically stops & releases MODE_STATIC tracks after playback completes so Qualcomm
 *   AudioPolicyManagerCustom stream invalidations never replay finished announcements.
 * - Supports immediate interruption via {@link #stop()} when the user presses PTT.
 */
public class VoiceAnnouncementPlayer {
    private static final String TAG = "VoiceAnnouncementPlayer";
    private static final int VOICE_SAMPLE_RATE = 16000;
    private static final long TIME_ANNOUNCE_DEBOUNCE_MS = 3000L;
    private static final long TRACK_RELEASE_MARGIN_MS = 300L;
    private static final long LOCAL_SOUND_GUARD_MARGIN_MS = 350L;

    // Pre-resolved compile-time resource tables (eliminates slow getResources().getIdentifier() lookups)
    private static final int[] HOUR_RES_IDS = {
            R.raw.hour_1, R.raw.hour_2, R.raw.hour_3, R.raw.hour_4,
            R.raw.hour_5, R.raw.hour_6, R.raw.hour_7, R.raw.hour_8,
            R.raw.hour_9, R.raw.hour_10, R.raw.hour_11, R.raw.hour_12
    };
    private static final int[] MIN_RES_IDS = {
            R.raw.min_0,  R.raw.min_1,  R.raw.min_2,  R.raw.min_3,  R.raw.min_4,  R.raw.min_5,
            R.raw.min_6,  R.raw.min_7,  R.raw.min_8,  R.raw.min_9,  R.raw.min_10, R.raw.min_11,
            R.raw.min_12, R.raw.min_13, R.raw.min_14, R.raw.min_15, R.raw.min_16, R.raw.min_17,
            R.raw.min_18, R.raw.min_19, R.raw.min_20, R.raw.min_21, R.raw.min_22, R.raw.min_23,
            R.raw.min_24, R.raw.min_25, R.raw.min_26, R.raw.min_27, R.raw.min_28, R.raw.min_29,
            R.raw.min_30, R.raw.min_31, R.raw.min_32, R.raw.min_33, R.raw.min_34, R.raw.min_35,
            R.raw.min_36, R.raw.min_37, R.raw.min_38, R.raw.min_39, R.raw.min_40, R.raw.min_41,
            R.raw.min_42, R.raw.min_43, R.raw.min_44, R.raw.min_45, R.raw.min_46, R.raw.min_47,
            R.raw.min_48, R.raw.min_49, R.raw.min_50, R.raw.min_51, R.raw.min_52, R.raw.min_53,
            R.raw.min_54, R.raw.min_55, R.raw.min_56, R.raw.min_57, R.raw.min_58, R.raw.min_59
    };

    private final Context mAppContext;
    private final PttSoundManager.LocalSoundListener mLocalSoundListener;
    private final ScheduledExecutorService mExecutor;
    private final PowerManager.WakeLock mWakeLock;

    private AudioTrack mActiveTrack;
    private ScheduledFuture<?> mReleaseFuture;
    private long mPlaybackGeneration = 0;
    private boolean mReleased = false;
    private volatile long mLastTimeAnnounceTimestamp = 0;

    public VoiceAnnouncementPlayer(Context context, PttSoundManager.LocalSoundListener listener) {
        mAppContext = context.getApplicationContext();
        mLocalSoundListener = listener;
        mExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MumlaVoiceWorker");
            t.setDaemon(true);
            return t;
        });

        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) mAppContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mumla:VoiceAnnounce");
                wl.setReferenceCounted(false);
            }
        } catch (Exception ignored) {}
        mWakeLock = wl;
    }

    /**
     * Announces the current time in concise 12-hour format (e.g., "11點 25分").
     * Ignored while PTT is active or within the 3-second debounce window.
     */
    public void announceCurrentTime(boolean isTalking) {
        if (isTalking) {
            Log.d(TAG, "Ignore time announcement because PTT is active");
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - mLastTimeAnnounceTimestamp < TIME_ANNOUNCE_DEBOUNCE_MS) {
            Log.d(TAG, "Ignore time announcement within debounce window (" + (now - mLastTimeAnnounceTimestamp) + "ms)");
            return;
        }
        mLastTimeAnnounceTimestamp = now;

        Calendar cal = Calendar.getInstance();
        int hour24 = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int hour12 = hour24 % 12;
        if (hour12 == 0) {
            hour12 = 12;
        }

        final int hourResId = HOUR_RES_IDS[hour12 - 1];
        final int minResId = (minute >= 0 && minute < MIN_RES_IDS.length) ? MIN_RES_IDS[minute] : MIN_RES_IDS[0];
        Log.i(TAG, "Announcing concise time: 24h=" + hour24 + " -> " + hour12 + "點 " + minute + "分");

        enqueuePlayback(new int[]{ hourResId, minResId }, 0.25f);
    }

    /**
     * Plays a single battery voice alert resource.
     */
    public void playBatteryVoice(int resId) {
        if (resId == 0) return;
        enqueuePlayback(new int[]{ resId }, 0.20f);
    }

    /**
     * Immediately interrupts any ongoing or pending voice announcement (e.g. when user presses PTT).
     * Guarantees no MODE_STATIC voice AudioTrack remains in PLAYSTATE_PLAYING during HAL invalidation.
     */
    public synchronized void stop() {
        mPlaybackGeneration++;
        releaseActiveTrackLocked();
        releaseWakeLock();
    }

    /**
     * Permanently stops playback and shuts down the background worker.
     */
    public void release() {
        synchronized (this) {
            mReleased = true;
            mPlaybackGeneration++;
            releaseActiveTrackLocked();
            releaseWakeLock();
        }
        mExecutor.shutdownNow();
    }

    private void enqueuePlayback(final int[] resIds, final float volume) {
        final long gen;
        synchronized (this) {
            if (mReleased || mExecutor.isShutdown()) {
                return;
            }
            gen = ++mPlaybackGeneration;
        }
        acquireWakeLock(3500L);
        try {
            mExecutor.execute(() -> playRawWavResourcesInMemory(resIds, volume, gen));
        } catch (Exception e) {
            Log.w(TAG, "Failed to enqueue voice announcement", e);
            releaseWakeLock();
        }
    }

    /**
     * Extracts raw PCM from 16kHz 16-bit Mono WAV resources and plays directly from RAM via AudioTrack.
     */
    private void playRawWavResourcesInMemory(int[] resIds, float volume, long gen) {
        AudioTrack track = null;
        try {
            ByteArrayOutputStream pcmStream = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[4096];

            for (int resId : resIds) {
                ByteArrayOutputStream rawStream = new ByteArrayOutputStream(32768);
                try (InputStream is = mAppContext.getResources().openRawResource(resId)) {
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        rawStream.write(buf, 0, read);
                    }
                }
                appendPcmData(rawStream.toByteArray(), pcmStream);
            }

            byte[] allPcm = pcmStream.toByteArray();
            if (allPcm.length == 0) {
                releaseWakeLockIfCurrent(gen);
                return;
            }

            // 16000 Hz * 2 bytes/sample = 32000 bytes/sec
            long durationMs = Math.max(200L, (allPcm.length * 1000L) / (VOICE_SAMPLE_RATE * 2L));

            synchronized (this) {
                if (mReleased || mPlaybackGeneration != gen) {
                    return;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                track = new AudioTrack(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        new AudioFormat.Builder()
                                .setSampleRate(VOICE_SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build(),
                        allPcm.length,
                        AudioTrack.MODE_STATIC,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                );
                track.setVolume(volume);
            } else {
                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        VOICE_SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        allPcm.length,
                        AudioTrack.MODE_STATIC
                );
                track.setStereoVolume(volume, volume);
            }

            track.write(allPcm, 0, allPcm.length);

            synchronized (this) {
                if (mReleased || mPlaybackGeneration != gen) {
                    releaseTrackQuietly(track);
                    return;
                }

                notifyLocalSound(durationMs + LOCAL_SOUND_GUARD_MARGIN_MS);
                acquireWakeLock(durationMs + 500L);

                releaseActiveTrackLocked();
                mActiveTrack = track;
                track.play();

                if (!mExecutor.isShutdown()) {
                    final AudioTrack launchedTrack = track;
                    mReleaseFuture = mExecutor.schedule(() -> {
                        synchronized (VoiceAnnouncementPlayer.this) {
                            if (mActiveTrack == launchedTrack) {
                                releaseTrackQuietly(launchedTrack);
                                mActiveTrack = null;
                                mReleaseFuture = null;
                                releaseWakeLock();
                            }
                        }
                    }, durationMs + TRACK_RELEASE_MARGIN_MS, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing in-memory voice AudioTrack: " + e.getMessage(), e);
            releaseTrackQuietly(track);
            releaseWakeLockIfCurrent(gen);
        }
    }

    private void notifyLocalSound(long guardDurationMs) {
        if (mLocalSoundListener != null) {
            mLocalSoundListener.onLocalSoundStarted(guardDurationMs);
        }
    }

    private void acquireWakeLock(long timeoutMs) {
        if (mWakeLock != null) {
            try {
                mWakeLock.acquire(timeoutMs);
            } catch (Exception ignored) {}
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null) {
            try {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            } catch (Exception ignored) {}
        }
    }

    private synchronized void releaseWakeLockIfCurrent(long gen) {
        if (mPlaybackGeneration == gen && mActiveTrack == null) {
            releaseWakeLock();
        }
    }

    private void releaseActiveTrackLocked() {
        if (mReleaseFuture != null) {
            mReleaseFuture.cancel(false);
            mReleaseFuture = null;
        }
        if (mActiveTrack != null) {
            releaseTrackQuietly(mActiveTrack);
            mActiveTrack = null;
        }
    }

    private static void releaseTrackQuietly(AudioTrack track) {
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
        }
    }

    private static void appendPcmData(byte[] rawWav, ByteArrayOutputStream out) {
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
}
