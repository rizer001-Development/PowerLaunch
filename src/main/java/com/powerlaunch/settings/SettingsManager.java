package com.powerlaunch.settings;

import com.powerlaunch.storage.AppDatabase;

import java.util.HashMap;
import java.util.Map;

/**
 * Launcher settings — now backed by SQLite via {@link AppDatabase}.
 * Keeps a fast in-memory cache; each {@link #set()} writes through to DB.
 */
public class SettingsManager {
    private static SettingsManager instance;
    private final AppDatabase db;
    private final Map<String, Object> cache;

    private SettingsManager() {
        db = AppDatabase.getInstance();
        cache = new HashMap<>();
        loadDefaults();
        loadFromDb();
    }

    public static synchronized SettingsManager getInstance() {
        if (instance == null) instance = new SettingsManager();
        return instance;
    }

    // ── defaults ──────────────────────────────────────────────

    void loadDefaults() {
        put("username", "");
        put("ram", 4096);
        put("gameWidth", 854);
        put("gameHeight", 480);
        put("javaArgs", "");
        put("selectedVersion", "latest");
        put("selectedModpack", "vanilla");
        put("gameDirectory", "");
        put("javaPath", "");
        put("javaChoice", "auto");
        put("gpuChoice", "auto");
        put("useCustomResolution", false);
        put("enableSkins", true);
        put("serverIp", "");
        put("autoLogin", false);
        put("autoConnect", false);
        put("connectServerIp", "");
        put("theme", "dark");
        put("showNews", true);
        put("saveConsoleLog", true);
    }

    private void loadFromDb() {
        for (Map.Entry<String, Object> d : new HashMap<>(cache).entrySet()) {
            String key = d.getKey();
            Object def = d.getValue();
            if (def instanceof Boolean) {
                cache.put(key, db.getBoolean(key, (Boolean) def));
            } else if (def instanceof Number) {
                cache.put(key, db.getInt(key, ((Number) def).intValue()));
            } else {
                cache.put(key, db.getString(key, (String) def));
            }
        }
    }

    // ── public API (same signatures as before) ────────────────

    public String getString(String key, String def) {
        Object v = cache.get(key);
        return v instanceof String s ? s : def;
    }

    public int getInt(String key, int def) {
        Object v = cache.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public boolean getBoolean(String key, boolean def) {
        Object v = cache.get(key);
        return v instanceof Boolean b ? b : def;
    }

    public void set(String key, Object value) {
        cache.put(key, value);
        // write-through to DB
        if (value instanceof Boolean b) db.setBoolean(key, b);
        else if (value instanceof Number n) db.setInt(key, n.intValue());
        else db.set(key, value != null ? value.toString() : "");
    }

    /** Bulk-load from a map (used by ProfileManager). Does NOT persist. */
    public void loadFromMap(Map<String, Object> m) {
        if (m != null) { cache.clear(); cache.putAll(m); }
    }

    public Map<String, Object> getAll() { return new HashMap<>(cache); }

    public void save() { /* no-op: set() already writes through */ }

    private void put(String k, Object v) { cache.put(k, v); }
}
