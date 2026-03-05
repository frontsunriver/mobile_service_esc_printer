package com.example.caposbackground;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.json.JSONObject;

/**
 * Printer IP configuration (thermal + kitchen1..5). Loads from external file first
 * ({@link #getConfigFilePath(Context)}); if a value is missing or file is not available, uses constants
 * ({@link #CONST_THERMAL_IP}, etc.) as fallback.
 */
public class PrinterConfig {

    private static final String TAG = "PrinterConfig";
    /** Subfolder inside the public Downloads directory (e.g. Download/CaposBackground/). */
    public static final String CONFIG_DIR = "CaposBackground";
    /** Filename for the IP list (JSON format). */
    public static final String CONFIG_FILENAME = "printer_ips.json";
    /** Legacy key=value format filename for fallback. */
    public static final String CONFIG_FILENAME_LEGACY = "printer_ips.txt";

    private static final String PREFS_NAME = "capos_printer_config";
    /** Optional: user-selected config file URI (e.g. from Download/CaposBackground) via Storage Access Framework. */
    private static final String KEY_CONFIG_FILE_URI = "printer_config_file_uri";
    private static final String KEY_THERMAL_IP = "printer_thermal_ip";
    private static final String KEY_KITCHEN_IP = "printer_kitchen_ip";
    private static final String KEY_KITCHEN1_IP = "printer_kitchen1_ip";
    private static final String KEY_KITCHEN2_IP = "printer_kitchen2_ip";
    private static final String KEY_KITCHEN3_IP = "printer_kitchen3_ip";
    private static final String KEY_KITCHEN4_IP = "printer_kitchen4_ip";
    private static final String KEY_KITCHEN5_IP = "printer_kitchen5_ip";

    private static final int DEFAULT_PORT = 9100;

    /** Fallback printer IPs when external file is missing or has no value. Edit to match your network. */
    public static final String CONST_THERMAL_IP = "10.88.111.110";
    public static final String CONST_KITCHEN_IP = "10.88.111.110";
    public static final String CONST_KITCHEN1_IP = "192.168.2.102";
    public static final String CONST_KITCHEN2_IP = "192.168.2.103";
    public static final String CONST_KITCHEN3_IP = "192.168.2.104";
    public static final String CONST_KITCHEN4_IP = "";  // set if you use kitchen4
    public static final String CONST_KITCHEN5_IP = "";  // set if you use kitchen5

    private final Context appContext;
    private final SharedPreferences prefs;
    /** IPs loaded from file (key e.g. "thermal_ip"). File overrides prefs/defaults. */
    private volatile Map<String, String> fileIps = new HashMap<>();

    public PrinterConfig(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = this.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromFile();
    }

    /**
     * Directory for printer config. Placed in the public Download folder (e.g. Download/CaposBackground/).
     * Created if it does not exist. Falls back to app-specific files dir if Downloads is not available.
     */
    public static File getConfigDir(Context context) {
        Context app = context.getApplicationContext();
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloadsDir, CONFIG_DIR);
        if (downloadsDir.exists() && (dir.exists() || dir.mkdirs())) {
            return dir;
        }
        return getConfigDirFallback(app);
    }

    /** App-specific config dir (always writable). Used when Download path is not available or not readable. */
    public static File getConfigDirFallback(Context context) {
        Context app = context.getApplicationContext();
        File base = app.getExternalFilesDir(null);
        if (base == null) base = app.getFilesDir();
        File dir = new File(base, CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Download/CaposBackground folder path for config (user places printer_ips.json here). */
    public static File getConfigFileInDownload(Context context) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(new File(downloadsDir, CONFIG_DIR), CONFIG_FILENAME);
    }

    /**
     * Path to the config file. Prefers Download/CaposBackground/; falls back to app dir if Download is unavailable.
     */
    public static File getConfigFile(Context context) {
        return new File(getConfigDir(context), CONFIG_FILENAME);
    }

    /** Default content for printer_ips.json when the file does not exist. */
    private static String getDefaultConfigContent() {
        return "{\n"
                + "  \"thermal_ip\": \"192.168.2.100\",\n"
                + "  \"kitchen_ip\": \"192.168.2.101\",\n"
                + "  \"kitchen1_ip\": \"192.168.2.102\",\n"
                + "  \"kitchen2_ip\": \"192.168.2.103\",\n"
                + "  \"kitchen3_ip\": \"192.168.2.104\",\n"
                + "  \"kitchen4_ip\": \"\",\n"
                + "  \"kitchen5_ip\": \"\"\n"
                + "}\n";
    }

    /** Parse JSON config content into key-value map (e.g. "thermal_ip" -> "192.168.2.100"). Returns null on error. */
    private Map<String, String> parseConfigFromJson(String content) {
        if (content == null || content.trim().isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(content.trim());
            Map<String, String> map = new HashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.opt(key);
                if (val != null) map.put(key, String.valueOf(val).trim());
            }
            return map;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse JSON config", e);
            return null;
        }
    }

    /** Read full file content as UTF-8 string. Returns null on error. */
    private String readFileContent(File file) {
        if (file == null || !file.exists() || !file.canRead()) return null;
        try (FileInputStream in = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    /** Path where user should place printer_ips.json: Download/CaposBackground/printer_ips.json */
    public static String getConfigFilePath(Context context) {
        if (context == null) return "(no context)";
        return getConfigFileInDownload(context).getAbsolutePath();
    }

    /** Set the config file URI (e.g. from "Select file" in app). Use this to load from Download/CaposBackground when the user picks the file. */
    public static void setConfigFileUri(Context context, String uriString) {
        if (context == null) return;
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CONFIG_FILE_URI, uriString != null ? uriString : "").apply();
    }

    /** Clear the user-selected config file URI. */
    public static void clearConfigFileUri(Context context) {
        if (context == null) return;
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_CONFIG_FILE_URI).apply();
    }

    /** (Re)load IP list: prefer user-selected file (Download path), then MediaStore/direct path, then app storage. */
    public void loadFromFile() {
        Map<String, String> map = null;

        // 0) User previously selected a file (e.g. Download/CaposBackground/printer_ips.json) via SAF
        String uriString = prefs.getString(KEY_CONFIG_FILE_URI, null);
        if (uriString != null && !uriString.isEmpty()) {
            try {
                Uri uri = Uri.parse(uriString);
                map = readAndParseConfigFromUri(appContext.getContentResolver(), uri);
                if (map != null && !map.isEmpty()) {
                    Log.d(TAG, "Loaded " + map.size() + " IP(s) from Download path (user-selected file)");
                } else {
                    map = null;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read user-selected config URI, clearing: " + e.getMessage());
                prefs.edit().remove(KEY_CONFIG_FILE_URI).apply();
            }
        }

        // 1) On Android 10+, try MediaStore so we load from Download/CaposBackground/ (not app storage)
        if (map == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            map = loadFromDownloadViaMediaStore(appContext);
            if (map == null) {
                ensureDefaultConfigFileInDownloadViaMediaStore(appContext);
                map = loadFromDownloadViaMediaStore(appContext);
            }
        }

        // 2) Try direct file path (works on Android 9 and below with storage permission, or legacy storage)
        if (map == null) {
            File downloadFile = getConfigFileInDownload(appContext);
            if (!downloadFile.exists() || !downloadFile.canRead()) {
                File legacyFile = new File(getConfigDir(appContext), CONFIG_FILENAME_LEGACY);
                if (legacyFile.exists() && legacyFile.canRead()) downloadFile = legacyFile;
            }
            if (downloadFile.exists() && downloadFile.canRead()) {
                String content = readFileContent(downloadFile);
                map = content != null ? parseConfigFromJson(content) : null;
                if (map == null && content != null && content.contains("=") && !content.trim().startsWith("{")) {
                    map = parseConfigStream(null, downloadFile);
                }
                if (map != null && !map.isEmpty()) {
                    Log.d(TAG, "Loaded " + map.size() + " IP(s) from Download/CaposBackground/" + downloadFile.getName());
                }
            }
        }

        // 3) Fallback: app storage (only if Download path not available)
        if (map == null) {
            File appDir = getConfigDirFallback(appContext);
            File appFile = new File(appDir, CONFIG_FILENAME);
            if (!appFile.exists() || !appFile.canRead()) appFile = new File(appDir, CONFIG_FILENAME_LEGACY);
            if (!appFile.exists()) ensureDefaultConfigFile(new File(appDir, CONFIG_FILENAME));
            appFile = new File(appDir, CONFIG_FILENAME);
            if (!appFile.exists() || !appFile.canRead()) appFile = new File(appDir, CONFIG_FILENAME_LEGACY);
            if (appFile.exists() && appFile.canRead()) {
                String content = readFileContent(appFile);
                map = content != null ? parseConfigFromJson(content) : null;
                if (map == null && content != null) map = parseConfigStream(null, appFile);
                if (map != null) Log.d(TAG, "Using config from app storage: " + appFile.getAbsolutePath());
            }
        }

        if (map != null && !map.isEmpty()) {
            fileIps = map;
            Log.d(TAG, "Loaded " + map.size() + " IP(s) from config file");
        } else {
            fileIps = new HashMap<>();
            Log.w(TAG, "No config file readable. Place " + CONFIG_FILENAME + " at: " + getConfigFilePath(appContext));
        }
    }

    /** Parse key=value lines from a file. Returns null on error. */
    private Map<String, String> parseConfigStream(InputStream in, File file) {
        try {
            if (in == null && file != null) in = new FileInputStream(file);
            if (in == null) return null;
            Map<String, String> map = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim();
                        if (!key.isEmpty()) map.put(key, value);
                    }
                }
            }
            return map;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse config" + (file != null ? ": " + file.getAbsolutePath() : ""), e);
            return null;
        }
    }

    /** Load config from Download/CaposBackground/ via MediaStore (Android 10+). Returns null if not found or error. */
    private Map<String, String> loadFromDownloadViaMediaStore(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        android.content.ContentResolver cr = context.getContentResolver();
        android.net.Uri uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = { MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH };
        // Try exact paths used by the system for Download/CaposBackground/
        String[] relativePathsToTry = {
                CONFIG_DIR + "/",                                    // CaposBackground/
                Environment.DIRECTORY_DOWNLOADS + "/" + CONFIG_DIR + "/"  // Download/CaposBackground/
        };
        for (String relativePath : relativePathsToTry) {
            Map<String, String> map = loadFromMediaStoreWithPath(cr, uri, projection, relativePath);
            if (map != null) return map;
        }
        // Fallback: query by display name only and prefer row where RELATIVE_PATH contains CaposBackground
        try {
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
            String[] selectionArgs = new String[]{ CONFIG_FILENAME };
            try (android.database.Cursor c = cr.query(uri, projection, selection, selectionArgs, null)) {
                if (c != null) {
                    int idCol = c.getColumnIndex(MediaStore.Downloads._ID);
                    int pathCol = c.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH);
                    long preferredId = -1;
                    String preferredPath = null;
                    while (c.moveToNext()) {
                        String relPath = pathCol >= 0 ? c.getString(pathCol) : "";
                        if (relPath != null && relPath.contains(CONFIG_DIR)) {
                            preferredId = c.getLong(idCol);
                            preferredPath = relPath;
                            break;
                        }
                        if (preferredId < 0) {
                            preferredId = c.getLong(idCol);
                            preferredPath = relPath;
                        }
                    }
                    if (preferredId >= 0) {
                        android.net.Uri contentUri = android.content.ContentUris.withAppendedId(uri, preferredId);
                        Map<String, String> map = readAndParseConfigFromUri(cr, contentUri);
                        if (map != null && !map.isEmpty()) {
                            Log.d(TAG, "Loaded " + map.size() + " IP(s) from Download/CaposBackground via MediaStore (path: " + preferredPath + ")");
                            return map;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore read failed for " + CONFIG_FILENAME, e);
        }
        return null;
    }

    private Map<String, String> loadFromMediaStoreWithPath(android.content.ContentResolver cr, android.net.Uri uri,
                                                           String[] projection, String relativePath) {
        try {
            String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + "=?";
            String[] selectionArgs = new String[]{ relativePath, CONFIG_FILENAME };
            try (android.database.Cursor c = cr.query(uri, projection, selection, selectionArgs, null)) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                    android.net.Uri contentUri = android.content.ContentUris.withAppendedId(uri, id);
                    Map<String, String> map = readAndParseConfigFromUri(cr, contentUri);
                    if (map != null && !map.isEmpty()) {
                        Log.d(TAG, "Loaded " + map.size() + " IP(s) from Download/CaposBackground via MediaStore");
                        return map;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "MediaStore query with path " + relativePath + " failed: " + e.getMessage());
        }
        return null;
    }

    private Map<String, String> readAndParseConfigFromUri(android.content.ContentResolver cr, android.net.Uri contentUri) {
        try (InputStream in = cr.openInputStream(contentUri)) {
            String content = readStreamContent(in);
            Map<String, String> map = content != null ? parseConfigFromJson(content) : null;
            if (map == null && content != null && content.contains("=") && !content.trim().startsWith("{")) {
                try (InputStream in2 = cr.openInputStream(contentUri)) {
                    map = parseConfigStream(in2, null);
                }
            }
            return map;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read from content URI", e);
            return null;
        }
    }

    /** Read InputStream to UTF-8 string. Caller closes the stream. */
    private String readStreamContent(InputStream in) {
        if (in == null) return null;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read stream", e);
            return null;
        }
    }

    /** Create default printer_ips.json in Download/CaposBackground/ via MediaStore (Android 10+). App can then read it. */
    private void ensureDefaultConfigFileInDownloadViaMediaStore(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            android.net.Uri uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, CONFIG_FILENAME);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + CONFIG_DIR + "/");
//            android.net.Uri inserted = cr.insert(uri, cv);
//            if (inserted != null) {
//                try (java.io.OutputStream out = cr.openOutputStream(inserted)) {
//                    if (out != null) {
//                        out.write(getDefaultConfigContent().getBytes(StandardCharsets.UTF_8));
//                        out.flush();
//                        Log.d(TAG, "Created default config in Download/CaposBackground via MediaStore");
//                    }
//                }
//            }
        } catch (Exception e) {
            Log.e(TAG, "Could not create config in Download via MediaStore", e);
        }
    }

    /** Create printer_ips.json with default content if it does not exist. */
    private void ensureDefaultConfigFile(File file) {
        if (file.exists()) return;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(getDefaultConfigContent());
            }
            Log.d(TAG, "Created default config at " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to create default printer config file", e);
        }
    }

    /** Returns IP from file if present, otherwise the const default (no prefs). */
    private String getIp(String fileKey, String constDefault) {
        Map<String, String> ips = fileIps;
        if (ips != null) {
            String fromFile = ips.get(fileKey);
            if (fromFile != null && !fromFile.trim().isEmpty()) return fromFile.trim();
        }
        return emptyToNull(constDefault);
    }

    public int getPort() {
        return DEFAULT_PORT;
    }

    @Nullable
    public String getThermalIp() {
        return getIp("thermal_ip", CONST_THERMAL_IP);
    }

    @Nullable
    public String getKitchenIp() {
        return getIp("kitchen_ip", CONST_KITCHEN_IP);
    }

    @Nullable
    public String getKitchen1Ip() {
        return getIp("kitchen1_ip", CONST_KITCHEN1_IP);
    }

    @Nullable
    public String getKitchen2Ip() {
        return getIp("kitchen2_ip", CONST_KITCHEN2_IP);
    }

    @Nullable
    public String getKitchen3Ip() {
        return getIp("kitchen3_ip", CONST_KITCHEN3_IP);
    }

    @Nullable
    public String getKitchen4Ip() {
        return getIp("kitchen4_ip", CONST_KITCHEN4_IP);
    }

    @Nullable
    public String getKitchen5Ip() {
        return getIp("kitchen5_ip", CONST_KITCHEN5_IP);
    }

    private static String emptyToNull(String s) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : null;
    }

    /** Get IP for queue type (0=thermal, 1=kitchen, 2=kitchen1, ... 6=kitchen5). */
    @Nullable
    public String getIpForType(int type) {
        switch (type) {
            case 0: return getThermalIp();
            case 1: return getKitchenIp();
            case 2: return getKitchen1Ip();
            case 3: return getKitchen2Ip();
            case 4: return getKitchen3Ip();
            case 5: return getKitchen4Ip();
            case 6: return getKitchen5Ip();
            default: return null;
        }
    }

    public void setThermalIp(String ip) {
        prefs.edit().putString(KEY_THERMAL_IP, ip != null ? ip : "").apply();
    }

    public void setKitchenIp(String ip) {
        prefs.edit().putString(KEY_KITCHEN_IP, ip != null ? ip : "").apply();
    }

    public void setKitchen1Ip(String ip) {
        prefs.edit().putString(KEY_KITCHEN1_IP, ip != null ? ip : "").apply();
    }

    public void setKitchen2Ip(String ip) {
        prefs.edit().putString(KEY_KITCHEN2_IP, ip != null ? ip : "").apply();
    }

    public void setKitchen3Ip(String ip) {
        prefs.edit().putString(KEY_KITCHEN3_IP, ip != null ? ip : "").apply();
    }

    public void setKitchen4Ip(String ip) {
        prefs.edit().putString(KEY_KITCHEN4_IP, ip != null ? ip : "").apply();
    }

    public void setKitchen5Ip(String ip) {
        prefs.edit().putString(KEY_KITCHEN5_IP, ip != null ? ip : "").apply();
    }
}
