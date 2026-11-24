package se.lublin.mumla.util;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

/**
 * High-performance, zero-latency PCM sound synthesizer for PTT operations.
 * Keeps only the 3 hot-path PTT tracks (Permit/Busy/Release) resident in AudioFlinger
 * and synthesizes infrequent auxiliary tones (Lock/Unlock/TOT) on demand via a single shared track.
 */
public class PttSoundManager {
    private static final String TAG = "PttSoundManager";
    private static final int SAMPLE_RATE = 16000;

    // Hot-path resident tracks (zero-latency)
    private AudioTrack mPermitTrack;
    private AudioTrack mBusyTrack;
    private AudioTrack mReleaseTrack;

    // Infrequent tones stored as lightweight PCM arrays (~15KB total) and played via a single shared track
    private short[] mLockSamples;
    private short[] mUnlockAckSamples;
    private short[] mTotWarningSamples;
    private AudioTrack mAuxTrack;

    // Unified reference amplitude: 1/3 of previous 8000 permit tone (~8.2% of full-scale 32767)
    private static final int PERMIT_TONE_AMPLITUDE = 2700;
    private static final int BUSY_TONE_AMPLITUDE = 2700;
    private static final int RELEASE_TONE_AMPLITUDE = 2700;
    private static final int LOCK_TONE_AMPLITUDE = 2700;
    private static final int UNLOCK_ACK_AMPLITUDE = 2700;
    private static final int TOT_WARNING_AMPLITUDE = 2700;

    public PttSoundManager() {
        initTracks();
    }

    private void initTracks() {
        try {
            // 1. Permit Tone (Motorola / DMR style: 900Hz 50ms + 1200Hz 50ms)
            short[] permitSamples = generateDualTone(900, 50, 1200, 50, PERMIT_TONE_AMPLITUDE);
            mPermitTrack = createTrack(permitSamples);

            // 2. Busy Tone (Commercial LMR/DMR reject bonk: 320Hz 90ms + 30ms silence + 260Hz 120ms)
            short[] busySamples = generateChirp(320, 90, 30, 260, 120, BUSY_TONE_AMPLITUDE);
            mBusyTrack = createTrack(busySamples);

            // 3. Release Tone (PTT release roger: 650Hz 40ms)
            short[] releaseSamples = generateTone(650, 40, RELEASE_TONE_AMPLITUDE);
            mReleaseTrack = createTrack(releaseSamples);

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
        playTrack(mPermitTrack);
    }

    public synchronized void playBusyTone() {
        playTrack(mBusyTrack);
    }

    public synchronized void playReleaseTone() {
        playTrack(mReleaseTrack);
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

    private void playAuxSamples(short[] samples) {
        if (samples == null) return;
        try {
            if (mAuxTrack != null) {
                try {
                    mAuxTrack.stop();
                    mAuxTrack.release();
                } catch (Exception ignored) {}
                mAuxTrack = null;
            }
            mAuxTrack = createTrack(samples);
            mAuxTrack.play();
        } catch (Exception e) {
            Log.e(TAG, "Error playing auxiliary audio track", e);
        }
    }

    private void playTrack(AudioTrack track) {
        if (track != null) {
            try {
                track.stop();
                track.reloadStaticData();
                track.play();
            } catch (Exception e) {
                Log.e(TAG, "Error playing audio track", e);
            }
        }
    }

    public synchronized void release() {
        releaseTrack(mPermitTrack);
        mPermitTrack = null;
        releaseTrack(mBusyTrack);
        mBusyTrack = null;
        releaseTrack(mReleaseTrack);
        mReleaseTrack = null;
        releaseTrack(mAuxTrack);
        mAuxTrack = null;
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
