package se.lublin.mumla.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.Constants;
import se.lublin.humla.model.Server;
import se.lublin.mumla.Settings;
import se.lublin.mumla.db.MumlaDatabase;

/**
 * Manages file-based server configuration (/sdcard/mumla_config.json).
 * On startup, automatically reads the config file, replaces the internal server list
 * to match the file, sets the designated default server and default channel, and
 * exports a template file if none exists yet.
 */
public final class ServerConfigFileManager {
    private static final String TAG = "ServerConfigFileMgr";
    public static final String CONFIG_FILE_NAME = "mumla_config.json";
    private static final String PREFS_SYNC = "mumla_config_file_sync";
    private static final String KEY_LAST_JSON_SNAPSHOT = "last_synced_json";

    private ServerConfigFileManager() {}

    /**
     * Candidate paths checked in priority order:
     * 1. /sdcard/mumla_config.json
     * 2. /sdcard/Download/mumla_config.json
     * 3. /sdcard/Android/data/se.lublin.mumla/files/mumla_config.json
     */
    public static List<File> getCandidateConfigFiles(Context context) {
        List<File> files = new ArrayList<>();
        try {
            File extRoot = Environment.getExternalStorageDirectory();
            if (extRoot != null) {
                files.add(new File(extRoot, CONFIG_FILE_NAME));
            }
        } catch (Exception ignored) {}

        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null) {
                files.add(new File(downloads, CONFIG_FILE_NAME));
            }
        } catch (Exception ignored) {}

        try {
            File appExt = context.getExternalFilesDir(null);
            if (appExt != null) {
                files.add(new File(appExt, CONFIG_FILE_NAME));
            }
        } catch (Exception ignored) {}

        return files;
    }

    /**
     * Synchronizes the internal SQLite server database from mumla_config.json.
     * If no config file exists yet, exports the current database as a template file.
     *
     * @return true if the active configuration was updated from the file.
     */
    public static boolean syncFromConfigFile(Context context, MumlaDatabase database, Settings settings) {
        if (context == null || database == null || settings == null) {
            return false;
        }

        File activeFile = null;
        for (File candidate : getCandidateConfigFiles(context)) {
            if (candidate != null && candidate.exists() && candidate.isFile() && candidate.canRead()) {
                activeFile = candidate;
                break;
            }
        }

        if (activeFile == null) {
            exportCurrentDatabaseAsTemplate(context, database, settings);
            return false;
        }

        try {
            String rawJson = readFileText(activeFile);
            if (rawJson == null || rawJson.trim().isEmpty()) {
                return false;
            }

            JSONObject root = new JSONObject(rawJson);

            // Sync optional NAS Hub OTA/Log settings (independent of server list)
            if (root.has("hubUrl")) {
                settings.setHubUrl(root.optString("hubUrl", ""));
            }
            if (root.has("hubToken")) {
                settings.setHubToken(root.optString("hubToken", ""));
            }

            JSONArray serversArray = root.optJSONArray("servers");
            if (serversArray == null || serversArray.length() == 0) {
                Log.w(TAG, "Config file has empty 'servers' array: " + activeFile.getAbsolutePath());
                return false;
            }

            String defaultServerKey = root.optString("defaultServer", "").trim();
            int defaultChannelId = root.optInt("defaultChannelId", -1);

            List<Server> desiredServers = new ArrayList<>();
            for (int i = 0; i < serversArray.length(); i++) {
                JSONObject item = serversArray.optJSONObject(i);
                if (item == null) continue;
                String host = item.optString("host", "").trim();
                if (host.isEmpty()) continue;

                String name = item.optString("name", host).trim();
                int port = item.optInt("port", Constants.DEFAULT_PORT);
                if (port <= 0) port = Constants.DEFAULT_PORT;
                String username = item.optString("username", settings.getDefaultUsername()).trim();
                String password = item.optString("password", "");

                desiredServers.add(new Server(-1, name, host, port, username, password));
            }

            if (desiredServers.isEmpty()) {
                return false;
            }

            SharedPreferences syncPrefs = context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE);
            String normalizedSnapshot = root.toString();
            String lastSnapshot = syncPrefs.getString(KEY_LAST_JSON_SNAPSHOT, "");

            List<Server> existingServers = database.getServers();
            boolean dbMatches = existingServers != null && serversMatch(existingServers, desiredServers);
            boolean configChanged = !normalizedSnapshot.equals(lastSnapshot) || !dbMatches;

            if (configChanged) {
                // Replace all servers in database with config file servers
                if (existingServers != null) {
                    for (Server old : existingServers) {
                        database.removeServer(old);
                    }
                }
                for (Server s : desiredServers) {
                    database.addServer(s);
                }
                Log.i(TAG, "Synced " + desiredServers.size() + " servers from " + activeFile.getAbsolutePath());
            }

            // Resolve defaultServer and store its ID in Settings.setLastServer
            List<Server> currentServers = database.getServers();
            if (currentServers != null && !currentServers.isEmpty()) {
                Server target = currentServers.get(0);
                if (!defaultServerKey.isEmpty()) {
                    for (Server s : currentServers) {
                        if (defaultServerKey.equalsIgnoreCase(s.getName())
                                || defaultServerKey.equalsIgnoreCase(s.getHost())) {
                            target = s;
                            break;
                        }
                    }
                }

                if (configChanged || settings.getLastServer() == -1) {
                    settings.setLastServer(target.getId());
                }

                // If defaultChannelId is configured in JSON, pre-populate humla_channels preference
                if (defaultChannelId > 0) {
                    SharedPreferences chanPrefs = context.getSharedPreferences("humla_channels", Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = chanPrefs.edit();
                    for (Server s : currentServers) {
                        editor.putInt("last_chan_" + s.getHost() + "_" + s.getPort(), defaultChannelId);
                        editor.putInt("last_chan_" + s.getHost() + "_" + Constants.DEFAULT_PORT, defaultChannelId);
                        editor.putInt("last_chan_" + s.getHost() + "_0", defaultChannelId);
                    }
                    editor.apply();
                }
            }

            if (configChanged) {
                settings.setFirstRun(false);
                settings.setAutoConnectOnStartEnabled(true);
                syncPrefs.edit().putString(KEY_LAST_JSON_SNAPSHOT, normalizedSnapshot).apply();
            }

            return configChanged;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse config file " + activeFile.getAbsolutePath(), e);
            return false;
        }
    }

    private static boolean serversMatch(List<Server> a, List<Server> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Server s1 = a.get(i);
            Server s2 = b.get(i);
            int p1 = s1.getPort() > 0 ? s1.getPort() : Constants.DEFAULT_PORT;
            int p2 = s2.getPort() > 0 ? s2.getPort() : Constants.DEFAULT_PORT;
            if (!safeEq(s1.getName(), s2.getName())
                    || !safeEq(s1.getHost(), s2.getHost())
                    || p1 != p2
                    || !safeEq(s1.getUsername(), s2.getUsername())
                    || !safeEq(s1.getPassword(), s2.getPassword())) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeEq(String s1, String s2) {
        String v1 = s1 != null ? s1 : "";
        String v2 = s2 != null ? s2 : "";
        return v1.equals(v2);
    }

    /**
     * Exports existing servers in the database to /sdcard/mumla_config.json when no file exists yet.
     */
    public static void exportCurrentDatabaseAsTemplate(Context context, MumlaDatabase database, Settings settings) {
        try {
            List<Server> existing = database.getServers();
            if (existing == null || existing.isEmpty()) {
                return;
            }

            String defaultName = existing.get(0).getName();
            long lastId = settings.getLastServer();
            for (Server s : existing) {
                if (s.getId() == lastId) {
                    defaultName = s.getName();
                    break;
                }
            }

            JSONObject root = new JSONObject();
            root.put("defaultServer", defaultName != null && !defaultName.isEmpty() ? defaultName : existing.get(0).getHost());
            root.put("defaultChannelId", 3);

            JSONArray arr = new JSONArray();
            for (Server s : existing) {
                JSONObject obj = new JSONObject();
                obj.put("name", s.getName() != null ? s.getName() : s.getHost());
                obj.put("host", s.getHost());
                obj.put("port", s.getPort() > 0 ? s.getPort() : Constants.DEFAULT_PORT);
                obj.put("username", s.getUsername() != null ? s.getUsername() : settings.getDefaultUsername());
                obj.put("password", s.getPassword() != null ? s.getPassword() : "");
                arr.put(obj);
            }
            root.put("servers", arr);

            String prettyJson = root.toString(2);
            for (File target : getCandidateConfigFiles(context)) {
                if (target == null) continue;
                try {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(target, false)) {
                        fos.write(prettyJson.getBytes(StandardCharsets.UTF_8));
                        fos.flush();
                    }
                    Log.i(TAG, "Exported template config file to: " + target.getAbsolutePath());
                    break;
                } catch (Exception ignored) {
                    // Try next writable candidate
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not export template config file", e);
        }
    }

    private static String readFileText(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        String text = sb.toString();
        // Strip UTF-8 BOM if saved by Windows Notepad
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text;
    }
}
