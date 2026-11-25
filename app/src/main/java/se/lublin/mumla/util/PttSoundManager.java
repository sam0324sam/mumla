package se.lublin.mumla.util;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * High-performance, zero-latency PCM sound synthesizer for PTT operations.
 * Keeps only the 3 hot-path PTT tracks (Permit/Busy/Release) resident in AudioFlinger
 * and synthesizes infrequent auxiliary tones (Lock/Unlock/TOT) on demand via a single shared track.
 *
 * Lifecycle guarantee:
 * Every MODE_STATIC AudioTrack is automatically transitioned back to PLAYSTATE_STOPPED (or released
 * for auxiliary tones) once its PCM duration completes. This prevents Qualcomm AudioPolicyManagerCustom
 * stream invalidations (triggered on PTT startInput/stopInput) from causing Android's native
 * AudioTrackThread::processAudioBuffer() to auto-recreate and replay stale static buffers from frame 0.
 */
public class PttSoundManager {
    private static final String TAG = "PttSoundManager";
    // Match msm8909/msm8916 native 48kHz hardware backend to avoid 16kHz HAL direct-output fallback (-61)
    private static final int SAMPLE_RATE = 48000;

    // Extra drain margin before transitioning a finished MODE_STATIC track to STOPPED/released
    // (covers msm8909 deep-buffer-playback latency without clipping the tail).
    private static final long TRACK_STOP_MARGIN_MS = 220L;
    // Extra guard margin reported to local-sound ALSA floor-control suppression.
    private static final long LOCAL_SOUND_GUARD_MARGIN_MS = 100L;

    public interface LocalSoundListener {
        void onLocalSoundStarted(long guardDurationMs);
    }

    private enum ResidentSlot {
        PERMIT, BUSY, RELEASE
    }

    private final Handler mCleanupHandler = new Handler(Looper.getMainLooper());
    private final LocalSoundListener mLocalSoundListener;

    // Hot-path resident tracks (zero-latency) and backing PCM buffers for auto-recovery after HAL invalidation
    private short[] mPermitSamples;
    private short[] mBusySamples;
    private short[] mReleaseSamples;
    private AudioTrack mPermitTrack;
    private AudioTrack mBusyTrack;
    private AudioTrack mReleaseTrack;

    // Infrequent tones stored as lightweight PCM arrays and played via a single shared track
    private short[] mLockSamples;
    private short[] mUnlockAckSamples;
    private short[] mTotWarningSamples;
    private AudioTrack mAuxTrack;

    // Monotonic per-slot tokens prevent stale cleanup callbacks from stopping a newer playback
    private long mPermitGen = 0;
    private long mBusyGen = 0;
    private long mReleaseGen = 0;
    private long mAuxGen = 0;
    private boolean mReleased = false;

    // Unified reference amplitude: 1/3 of previous 8000 permit tone (~8.2% of full-scale 32767)
    private static final int PERMIT_TONE_AMPLITUDE = 2700;
    private static final int BUSY_TONE_AMPLITUDE = 2700;
    private static final int RELEASE_TONE_AMPLITUDE = 2700;
    private static final int LOCK_TONE_AMPLITUDE = 2700;
    private static final int UNLOCK_ACK_AMPLITUDE = 2700;
    private static final int TOT_WARNING_AMPLITUDE = 2700;

    public PttSoundManager() {
        this(null);
    }

    public PttSoundManager(LocalSoundListener listener) {
        mLocalSoundListener = listener;
        initTracks();
    }

    private void initTracks() {
        try {
            // 1. Permit Tone (Motorola / DMR style: 900Hz 50ms + 1200Hz 50ms)
            mPermitSamples = generateDualTone(900, 50, 1200, 50, PERMIT_TONE_AMPLITUDE);
            mPermitTrack = createTrack(mPermitSamples);

            // 2. Busy Tone (Commercial LMR/DMR reject bonk: 320Hz 90ms + 30ms silence + 260Hz 120ms)
            mBusySamples = generateChirp(320, 90, 30, 260, 120, BUSY_TONE_AMPLITUDE);
            mBusyTrack = createTrack(mBusySamples);

            // 3. Release Tone (PTT release roger: 650Hz 40ms)
            mReleaseSamples = generateTone(650, 40, RELEASE_TONE_AMPLITUDE);
            mReleaseTrack = createTrack(mReleaseSamples);

            // 4-6. Auxiliary low-frequency tones kept in compact short[] buffers
            mLockSamples = generateChirp(1200, 70, 25, 800, 100, LOCK_TONE_AMPLITUDE);
            mUnlockAckSamples = generateChirp(800, 60, 25, 1200, 90, UNLOCK_ACK_AMPLITUDE);
            mTotWarningSamples = generateTripleBeep(1000, 80, 40, TOT_WARNING_AMPLITUDE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize PttSoundManager AudioTracks", e);
        }
    }

    private AudioTrack createTrack(short[] samples) {
        int bufferSize = samples.length * 2;
        AudioTrack track;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    bufferSize,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
        } else {
            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STATIC
            );
        }
        track.write(samples, 0, samples.length);
        return track;
    }

    private static long durationMs(short[] samples) {
        if (samples == null || samples.length == 0) return 0L;
        return (samples.length * 1000L) / SAMPLE_RATE;
    }

    private short[] generateDualTone(int freq1, int ms1, int freq2, int ms2, int amplitude) {
        int count1 = (SAMPLE_RATE * ms1) / 1000;
        int count2 = (SAMPLE_RATE * ms2) / 1000;
        short[] buffer = new short[count1 + count2];
        for (int i = 0; i < count1; i++) {
            double angle = 2.0 * Math.PI * i / (SAMPLE_RATE / (double) freq1);
            buffer[i] = (short) (Math.sin(angle) * amplitude);
        }
        for (int i = 0; i < count2; i++) {
            double angle = 2.0 * Math.PI * i / (SAMPLE_RATE / (double) freq2);
            buffer[count1 + i] = (short) (Math.sin(angle) * amplitude);
        }
        return buffer;
    }

    private short[] generateTone(int freq, int ms, int amplitude) {
        int count = (SAMPLE_RATE * ms) / 1000;
        short[] buffer = new short[count];
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / (SAMPLE_RATE / (double) freq);
            buffer[i] = (short) (Math.sin(angle) * amplitude);
        }
        return buffer;
    }

    private short[] generateChirp(int freq1, int ms1, int gapMs, int freq2, int ms2, int amplitude) {
        int count1 = (SAMPLE_RATE * ms1) / 1000;
        int gapCount = (SAMPLE_RATE * gapMs) / 1000;
        int count2 = (SAMPLE_RATE * ms2) / 1000;
        short[] buffer = new short[count1 + gapCount + count2];
        for (int i = 0; i < count1; i++) {
            double angle = 2.0 * Math.PI * i / (SAMPLE_RATE / (double) freq1);
            buffer[i] = (short) (Math.sin(angle) * amplitude);
        }
        for (int i = 0; i < count2; i++) {
            double angle = 2.0 * Math.PI * i / (SAMPLE_RATE / (double) freq2);
            buffer[count1 + gapCount + i] = (short) (Math.sin(angle) * amplitude);
        }
        return buffer;
    }

    private short[] generateTripleBeep(int freq, int beepMs, int gapMs, int amplitude) {
        int beepCount = (SAMPLE_RATE * beepMs) / 1000;
        int gapCount = (SAMPLE_RATE * gapMs) / 1000;
        short[] buffer = new short[beepCount * 3 + gapCount * 2];
        for (int b = 0; b < 3; b++) {
            int offset = b * (beepCount + gapCount);
            for (int i = 0; i < beepCount; i++) {
                double angle = 2.0 * Math.PI * i / (SAMPLE_RATE / (double) freq);
                buffer[offset + i] = (short) (Math.sin(angle) * amplitude);
            }
        }
        return buffer;
    }

    public synchronized void playPermitTone() {
        if (mReleased) return;
        long gen = ++mPermitGen;
        notifyLocalSound(durationMs(mPermitSamples) + LOCAL_SOUND_GUARD_MARGIN_MS);
        mPermitTrack = playTrackWithRecovery(mPermitTrack, mPermitSamples);
        scheduleResidentStop(ResidentSlot.PERMIT, gen, durationMs(mPermitSamples) + TRACK_STOP_MARGIN_MS);
    }

    public synchronized void playBusyTone() {
        if (mReleased) return;
        long gen = ++mBusyGen;
        notifyLocalSound(durationMs(mBusySamples) + LOCAL_SOUND_GUARD_MARGIN_MS);
        mBusyTrack = playTrackWithRecovery(mBusyTrack, mBusySamples);
        scheduleResidentStop(ResidentSlot.BUSY, gen, durationMs(mBusySamples) + TRACK_STOP_MARGIN_MS);
    }

    public synchronized void playReleaseTone() {
        if (mReleased) return;
        long gen = ++mReleaseGen;
        notifyLocalSound(durationMs(mReleaseSamples) + LOCAL_SOUND_GUARD_MARGIN_MS);
        mReleaseTrack = playTrackWithRecovery(mReleaseTrack, mReleaseSamples);
        scheduleResidentStop(ResidentSlot.RELEASE, gen, durationMs(mReleaseSamples) + TRACK_STOP_MARGIN_MS);
    }

    public synchronized void playLockTone() {
        playAuxSamples(mLockSamples);
    }

    public synchronized void playUnlockAckTone() {
        playAuxSamples(mUnlockAckSamples);
    }

    public synchronized void playTotWarningTone() {
        playAuxSamples(mTotWarningSamples);
    }

    /**
     * Transitions all resident tracks to PLAYSTATE_STOPPED and releases any auxiliary track.
     * Call right before starting hardware mic capture (PTT down) so HAL stream invalidation
     * cannot auto-replay any previously played MODE_STATIC tone.
     */
    public synchronized void stopAllActiveTones() {
        mPermitGen++;
        mBusyGen++;
        mReleaseGen++;
        mAuxGen++;
        stopTrackQuietly(mPermitTrack);
        stopTrackQuietly(mBusyTrack);
        stopTrackQuietly(mReleaseTrack);
        releaseTrack(mAuxTrack);
        mAuxTrack = null;
    }

    private void playAuxSamples(short[] samples) {
        if (mReleased || samples == null) return;
        long gen = ++mAuxGen;
        long durMs = durationMs(samples);
        notifyLocalSound(durMs + LOCAL_SOUND_GUARD_MARGIN_MS + 100L);
        try {
            releaseTrack(mAuxTrack);
            mAuxTrack = null;
            mAuxTrack = createTrack(samples);
            mAuxTrack.play();
            mCleanupHandler.postDelayed(() -> releaseAuxTrackIfCurrent(gen), durMs + TRACK_STOP_MARGIN_MS);
        } catch (Exception e) {
            Log.e(TAG, "Error playing auxiliary audio track", e);
        }
    }

    private void scheduleResidentStop(final ResidentSlot slot, final long gen, long delayMs) {
        mCleanupHandler.postDelayed(() -> stopResidentTrackIfCurrent(slot, gen), delayMs);
    }

    private synchronized void stopResidentTrackIfCurrent(ResidentSlot slot, long gen) {
        if (mReleased) return;
        switch (slot) {
            case PERMIT:
                if (mPermitGen == gen) stopTrackQuietly(mPermitTrack);
                break;
            case BUSY:
                if (mBusyGen == gen) stopTrackQuietly(mBusyTrack);
                break;
            case RELEASE:
                if (mReleaseGen == gen) stopTrackQuietly(mReleaseTrack);
                break;
        }
    }

    private synchronized void releaseAuxTrackIfCurrent(long gen) {
        if (mReleased || mAuxGen != gen) return;
        releaseTrack(mAuxTrack);
        mAuxTrack = null;
    }

    private void notifyLocalSound(long guardDurationMs) {
        if (mLocalSoundListener != null) {
            mLocalSoundListener.onLocalSoundStarted(guardDurationMs);
        }
    }

    private AudioTrack playTrackWithRecovery(AudioTrack track, short[] samples) {
        if (samples == null) return track;
        try {
            if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
                releaseTrack(track);
                track = createTrack(samples);
            } else {
                track.stop();
                int reloadResult = track.reloadStaticData();
                if (reloadResult != AudioTrack.SUCCESS) {
                    releaseTrack(track);
                    track = createTrack(samples);
                }
            }
            track.play();
            return track;
        } catch (Exception e) {
            Log.w(TAG, "AudioTrack playback failed (likely HAL invalidation), recreating track", e);
            releaseTrack(track);
            try {
                AudioTrack recreated = createTrack(samples);
                recreated.play();
                return recreated;
            } catch (Exception retryEx) {
                Log.e(TAG, "Failed to recreate and play audio track", retryEx);
                return null;
            }
        }
    }

    public synchronized void release() {
        mReleased = true;
        mPermitGen++;
        mBusyGen++;
        mReleaseGen++;
        mAuxGen++;
        mCleanupHandler.removeCallbacksAndMessages(null);
        releaseTrack(mPermitTrack);
        mPermitTrack = null;
        releaseTrack(mBusyTrack);
        mBusyTrack = null;
        releaseTrack(mReleaseTrack);
        mReleaseTrack = null;
        releaseTrack(mAuxTrack);
        mAuxTrack = null;
    }

    private static void stopTrackQuietly(AudioTrack track) {
        if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED) {
            try {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                    track.stop();
                }
            } catch (Exception ignored) {}
        }
    }

    private static void releaseTrack(AudioTrack track) {
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {}
            try {
                track.release();
            } catch (Exception ignored) {}
        }
    }
}
