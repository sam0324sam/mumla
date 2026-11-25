package se.lublin.mumla.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.Settings;

/**
 * Checks for newer Mumla APK builds via the user's NAS hub (<hubUrl>/latest & <hubUrl>/latest.apk),
 * downloads the APK in the background, verifies ZIP/APK header integrity, and launches the
 * standard Android package installer (1-tap confirm, no root required).
 */
public final class AppUpdaterManager {
    private static final String TAG = "AppUpdaterManager";
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long MIN_VALID_APK_BYTES = 1_000_000L;
    private static final long AUTO_CHECK_DELAY_MS = 20_000L;
    // Allow 5 minutes skew between local build timestamp and GitHub release published_at
    private static final long PUBLISH_TIME_SKEW_MS = 5 * 60 * 1000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sChecking = new AtomicBoolean(false);
    private static boolean sStartupScheduled = false;

    public interface UpdateCallback {
        void onStatus(@NonNull String message);
    }

    private AppUpdaterManager() {
    }

    /**
     * Schedules a single lightweight background check + pending crash upload after app startup.
     */
    public static void scheduleStartupTasks(@NonNull Context context) {
        if (sStartupScheduled) {
            return;
        }
        sStartupScheduled = true;
        final Context appCtx = context.getApplicationContext();
        MAIN_HANDLER.postDelayed(() -> {
            RemoteLogUploader.uploadPendingCrashIfAny(appCtx);
            Settings settings = Settings.getInstance(appCtx);
            if (settings.isAutoCheckUpdateEnabled() && !settings.getHubUrl().isEmpty()) {
                checkAndDownloadUpdate(appCtx, false, null);
            }
        }, AUTO_CHECK_DELAY_MS);
    }

    public static void checkAndDownloadUpdate(@NonNull Context context,
                                              boolean manualTrigger,
                                              @Nullable UpdateCallback callback) {
        final Context appCtx = context.getApplicationContext();
        final Settings settings = Settings.getInstance(appCtx);
        final String hubUrl = settings.getHubUrl();

        if (hubUrl.isEmpty()) {
            notifyUser(appCtx, manualTrigger, callback, "尚未設定更新伺服器 (hubUrl)");
            return;
        }
        if (!RemoteLogUploader.isNetworkAvailable(appCtx)) {
            notifyUser(appCtx, manualTrigger, callback, "目前無網路連線");
            return;
        }
        if (!sChecking.compareAndSet(false, true)) {
            notifyUser(appCtx, manualTrigger, callback, "正在檢查更新中…");
            return;
        }

        if (manualTrigger) {
            notifyUser(appCtx, true, callback, "正在檢查新版本…");
        }

        EXECUTOR.execute(() -> {
            try {
                String base = trimTrailingSlash(hubUrl);
                JSONObject meta = fetchJson(base + "/latest");
                String remoteSha = meta.optString("sha", "").trim();
                long remotePublishedMs = meta.optLong("published_ms", 0L);

                if (remoteSha.isEmpty()) {
                    notifyUser(appCtx, manualTrigger, callback, "無法解析遠端版本資訊");
                    return;
                }

                boolean isSameSha = remoteSha.equalsIgnoreCase(BuildConfig.GIT_SHA);
                boolean isOlderOrSameTime = remotePublishedMs > 0
                        && remotePublishedMs <= (BuildConfig.TIMESTAMP + PUBLISH_TIME_SKEW_MS);

                if (isSameSha || isOlderOrSameTime) {
                    Log.i(TAG, "Already up to date: local=" + BuildConfig.GIT_SHA + ", remote=" + remoteSha);
                    notifyUser(appCtx, manualTrigger, callback, "目前已是最新版本 (" + BuildConfig.GIT_SHA + ")");
                    return;
                }

                Log.i(TAG, "New update found: local=" + BuildConfig.GIT_SHA + " -> remote=" + remoteSha);
                notifyUser(appCtx, true, callback, "發現新版 (" + remoteSha + ")，下載中…");

                File apkFile = downloadApk(appCtx, base + "/latest.apk");
                if (apkFile == null) {
                    notifyUser(appCtx, true, callback, "APK 下載或驗證失敗");
                    return;
                }

                MAIN_HANDLER.post(() -> launchInstaller(appCtx, apkFile, callback));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                notifyUser(appCtx, manualTrigger, callback, "檢查更新失敗: " + e.getMessage());
            } finally {
                sChecking.set(false);
            }
        });
    }

    private static JSONObject fetchJson(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONNECT_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Nullable
    private static File downloadApk(@NonNull Context context, @NonNull String apkUrl) throws Exception {
        File cacheBase = context.getExternalCacheDir();
        if (cacheBase == null) {
            cacheBase = context.getCacheDir();
        }
        File updateDir = new File(cacheBase, "updates");
        if (!updateDir.exists() && !updateDir.mkdirs()) {
            updateDir = new File(context.getCacheDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            updateDir.mkdirs();
        }

        File tmpFile = new File(updateDir, "mumla-update.apk.tmp");
        File finalFile = new File(updateDir, "mumla-update.apk");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apkUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("APK HTTP " + code);
            }

            long total = 0;
            byte[] header = new byte[2];
            int headerRead = 0;
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmpFile, false)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (headerRead < 2) {
                        for (int i = 0; i < n && headerRead < 2; i++) {
                            header[headerRead++] = buf[i];
                        }
                    }
                    fos.write(buf, 0, n);
                    total += n;
                }
                fos.flush();
            }

            // Basic integrity check: APK must be a ZIP ("PK") and >= 1 MB
            if (total < MIN_VALID_APK_BYTES || header[0] != 'P' || header[1] != 'K') {
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
                Log.w(TAG, "Downloaded APK failed integrity check: bytes=" + total);
                return null;
            }

            if (finalFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                finalFile.delete();
            }
            if (!tmpFile.renameTo(finalFile)) {
                return tmpFile;
            }
            return finalFile;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void launchInstaller(@NonNull Context context,
                                        @NonNull File apkFile,
                                        @Nullable UpdateCallback callback) {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".fileprovider",
                    apkFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            if (callback != null) {
                callback.onStatus("已開啟系統安裝介面");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch APK installer", e);
            Toast.makeText(context, "無法開啟安裝器: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String trimTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void notifyUser(@NonNull Context context,
                                   boolean showToast,
                                   @Nullable UpdateCallback callback,
                                   @NonNull String msg) {
        MAIN_HANDLER.post(() -> {
            if (callback != null) {
                callback.onStatus(msg);
            } else if (showToast) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
