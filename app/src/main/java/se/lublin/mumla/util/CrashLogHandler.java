package se.lublin.mumla.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import se.lublin.mumla.BuildConfig;

/**
 * Persists fatal uncaught exceptions to internal storage so RemoteLogUploader
 * can automatically relay the crash report to the NAS hub on next boot/launch.
 */
public final class CrashLogHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashLogHandler";
    public static final String PENDING_CRASH_FILE = "pending_crash.log";
    private static final int MAX_CRASH_BYTES = 64 * 1024;

    private final Context mAppContext;
    private final Thread.UncaughtExceptionHandler mPreviousHandler;

    private CrashLogHandler(@NonNull Context appContext, Thread.UncaughtExceptionHandler previous) {
        mAppContext = appContext;
        mPreviousHandler = previous;
    }

    public static void install(@NonNull Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashLogHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashLogHandler(context.getApplicationContext(), current));
    }

    public static File getPendingCrashFile(@NonNull Context context) {
        return new File(context.getFilesDir(), PENDING_CRASH_FILE);
    }

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
            pw.println("=== MUMLA FATAL CRASH REPORT ===");
            pw.println("Time: " + sdf.format(new Date()));
            pw.println("App: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.GIT_SHA + ")");
            pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " (Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT + ")");
            pw.println("Thread: " + t.getName());
            pw.println();
            e.printStackTrace(pw);
            pw.flush();

            byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
            int len = Math.min(bytes.length, MAX_CRASH_BYTES);
            File outFile = getPendingCrashFile(mAppContext);
            try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
                fos.write(bytes, 0, len);
                fos.flush();
            }
        } catch (Throwable ignored) {
            // Never throw inside uncaughtException
        } finally {
            if (mPreviousHandler != null) {
                mPreviousHandler.uncaughtException(t, e);
            }
        }
    }
}
