package se.lublin.mumla.util;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import se.lublin.humla.model.Server;
import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.Settings;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.service.GpsBeaconManager;

/**
 * Uploads compressed device logs or pending crash reports to the user's NAS hub
 * (POST <hubUrl>/log), which persists a local copy and forwards to the private GitHub log repo.
 */
public final class RemoteLogUploader {
    private static final String TAG = "RemoteLogUploader";
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_LOGCAT_LINES = 800;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public interface UploadCallback {
        void onResult(boolean success, @NonNull String message);
    }

    private RemoteLogUploader() {
    }

    public static void uploadPendingCrashIfAny(@NonNull Context context) {
        final Context appCtx = context.getApplicationContext();
        final File crashFile = CrashLogHandler.getPendingCrashFile(appCtx);
        if (!crashFile.exists() || crashFile.length() == 0) {
            return;
        }
        String hubUrl = Settings.getInstance(appCtx).getHubUrl();
        if (hubUrl.isEmpty() || !isNetworkAvailable(appCtx)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                byte[] content = readAllBytes(crashFile);
                boolean ok = postGzipLog(appCtx, hubUrl, Settings.getInstance(appCtx).getHubToken(), "crash", content);
                if (ok) {
                    //noinspection ResultOfMethodCallIgnored
                    crashFile.delete();
                    Log.i(TAG, "Pending crash log uploaded and cleared.");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to upload pending crash log", e);
            }
        });
    }

    public static void uploadRecentLogsAsync(@NonNull Context context, @Nullable UploadCallback callback) {
        final Context appCtx = context.getApplicationContext();
        final Settings settings = Settings.getInstance(appCtx);
        final String hubUrl = settings.getHubUrl();
        if (hubUrl.isEmpty()) {
            deliver(callback, false, "未設定中繼伺服器 (hubUrl)");
            return;
        }
        if (!isNetworkAvailable(appCtx)) {
            deliver(callback, false, "目前無網路連線");
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                String payload = buildDiagnosticLog(appCtx);
                boolean ok = postGzipLog(
                        appCtx,
                        hubUrl,
                        settings.getHubToken(),
                        "manual",
                        payload.getBytes(StandardCharsets.UTF_8));
                if (ok) {
                    deliver(callback, true, "除錯日誌已上傳");
                } else {
                    deliver(callback, false, "上傳失敗，請檢查 hubUrl / hubToken");
                }
            } catch (Exception e) {
                Log.w(TAG, "Manual log upload failed", e);
                deliver(callback, false, "上傳失敗: " + e.getMessage());
            }
        });
    }

    private static String buildDiagnosticLog(@NonNull Context context) {
        StringBuilder sb = new StringBuilder(16 * 1024);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();

        sb.append("=== MUMLA DIAGNOSTIC LOG ===\n");
        sb.append("Time: ").append(sdf.format(new Date())).append('\n');
        sb.append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" | sha=").append(BuildConfig.GIT_SHA)
                .append(" | ts=").append(BuildConfig.TIMESTAMP).append('\n');
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Display: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                .append(" @").append(dm.densityDpi).append("dpi")
                .append(" | clarity=").append(DisplayClarityHelper.isClarityModeEnabled(context)).append('\n');
        sb.append("Battery: ").append(readBatteryPercent(context)).append("%\n");
        sb.append("User: ").append(resolveUsername(context)).append('\n');
        sb.append("\n--- RECENT LOGCAT ---\n");

        Process proc = null;
        try {
            proc = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", String.valueOf(MAX_LOGCAT_LINES))
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("(logcat read error: ").append(e.getMessage()).append(")\n");
        } finally {
            if (proc != null) {
                proc.destroy();
            }
        }
        return sb.toString();
    }

    /**
     * Resolves the best available device/user identifier for log headers and filenames:
     * 1. Currently connected Mumble session username
     * 2. First configured non-default server username from MumlaSQLiteDatabase
     * 3. Default username from Settings
     */
    @NonNull
    public static String resolveUsername(@NonNull Context context) {
        try {
            String active = GpsBeaconManager.getInstance(context).getConnectedUserName();
            if (active != null && !active.trim().isEmpty()) {
                return active.trim();
            }
        } catch (Exception ignored) {
        }

        String defaultName = "";
        try {
            defaultName = Settings.getInstance(context).getDefaultUsername();
            if (defaultName == null) defaultName = "";
        } catch (Exception ignored) {
        }

        MumlaSQLiteDatabase db = null;
        try {
            db = new MumlaSQLiteDatabase(context.getApplicationContext());
            db.open();
            List<Server> servers = db.getServers();
            String firstNonEmpty = null;
            if (servers != null) {
                for (Server server : servers) {
                    if (server == null || server.getUsername() == null) continue;
                    String u = server.getUsername().trim();
                    if (u.isEmpty()) continue;
                    if (firstNonEmpty == null) {
                        firstNonEmpty = u;
                    }
                    if (!u.equals(defaultName) && !"Mumla_User".equals(u)) {
                        return u;
                    }
                }
            }
            if (firstNonEmpty != null) {
                return firstNonEmpty;
            }
        } catch (Exception ignored) {
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Exception ignored) {
                }
            }
        }

        return !defaultName.trim().isEmpty() ? defaultName.trim() : "Mumla_User";
    }

    private static boolean postGzipLog(@NonNull Context context,
                                       @NonNull String hubBaseUrl,
                                       @NonNull String hubToken,
                                       @NonNull String reason,
                                       @NonNull byte[] rawBytes) throws Exception {
        String endpoint = trimTrailingSlash(hubBaseUrl) + "/log";
        byte[] gzBytes = gzip(rawBytes);

        String deviceTag = Build.MODEL + "_" + resolveUsername(context);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("X-Device-Name", sanitizeHeader(deviceTag));
            conn.setRequestProperty("X-App-Version", sanitizeHeader(BuildConfig.GIT_SHA));
            conn.setRequestProperty("X-Log-Reason", sanitizeHeader(reason));
            if (!hubToken.isEmpty()) {
                conn.setRequestProperty("X-Mumla-Token", hubToken);
            }
            conn.setFixedLengthStreamingMode(gzBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(gzBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            Log.w(TAG, "Hub log upload returned HTTP " + code);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(input.length, 32 * 1024));
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    private static byte[] readAllBytes(File file) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(file.length(), 65536));
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
        }
        return baos.toByteArray();
    }

    private static int readBatteryPercent(@NonNull Context context) {
        try {
            Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    return (level * 100) / scale;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static boolean isNetworkAvailable(@NonNull Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo active = cm.getActiveNetworkInfo();
            return active != null && active.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static String trimTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String sanitizeHeader(String val) {
        if (val == null) return "unknown";
        return val.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void deliver(@Nullable UploadCallback callback, boolean success, @NonNull String message) {
        if (callback == null) return;
        MAIN_HANDLER.post(() -> callback.onResult(success, message));
    }
}
