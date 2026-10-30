package se.lublin.mumla.util;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

/**
 * High-performance, zero-latency PCM sound synthesizer for PTT operations.
 * Generates DMR/Motorola-style Talk Permit, Busy/Rejection, and Release tones.
 */
public class PttSoundManager {
    private static final String TAG = "PttSoundManager";
    private static final int SAMPLE_RATE = 16000;

    private AudioTrack mPermitTrack;
    private AudioTrack mBusyTrack;
    private AudioTrack mReleaseTrack;

    public PttSoundManager() {
        initTracks();
    }

    // Unified reference amplitude: 1/3 of previous 8000 permit tone (~8.2% of full-scale 32767)
    private static final int PERMIT_TONE_AMPLITUDE = 2700;
    private static final int BUSY_TONE_AMPLITUDE = 2700;
    private static final int RELEASE_TONE_AMPLITUDE = 2700;

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

    public synchronized void playPermitTone() {
        playTrack(mPermitTrack);
    }

    public synchronized void playBusyTone() {
        playTrack(mBusyTrack);
    }

    public synchronized void playReleaseTone() {
        playTrack(mReleaseTrack);
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
        if (mPermitTrack != null) {
            try { mPermitTrack.release(); } catch (Exception ignored) {}
            mPermitTrack = null;
        }
        if (mBusyTrack != null) {
            try { mBusyTrack.release(); } catch (Exception ignored) {}
            mBusyTrack = null;
        }
        if (mReleaseTrack != null) {
            try { mReleaseTrack.release(); } catch (Exception ignored) {}
            mReleaseTrack = null;
        }
    }
}
