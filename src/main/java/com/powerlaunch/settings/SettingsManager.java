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

    public static SettingsManager getInstance() {
        if (instance == null) {
            synchronized (SettingsManager.class) {
                if (instance == null) instance = new SettingsManager();
            }
        }
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
        put("theme", "Dark");
        put("showNews", true);
        put("saveConsoleLog", true);
        // ── Design & animation settings (new settings tabs) ──
        put("animationsEnabled", true);
        put("animationSpeed", 1.0);
        put("animationType", "Плавные");
        put("backgroundColor", "#0f172a");
        put("accentColor", "#3b82f6");
        put("textColor", "#f8fafc");
        put("gradientEnabled", false);
        put("gradientType", "Линейный");
        put("gradientDirection", "Сверху вниз");
        put("settingsLastTab", "Основные");
    }

    private void loadFromDb() {
        for (Map.Entry<String, Object> d : new HashMap<>(cache).entrySet()) {
            String key = d.getKey();
            Object def = d.getValue();
            if (def instanceof Boolean) {
                cache.put(key, db.getBoolean(key, (Boolean) def));
            } else if (def instanceof Double || def instanceof Float) {
                // Double settings are stored as strings (e.g. "1.5") — parse them back.
                String v = db.getString(key, null);
                if (v != null) {
                    try {
                        cache.put(key, Double.parseDouble(v));
                    } catch (NumberFormatException e) {
                        System.err.println("[PowerLaunch] Invalid double value for key " + key + ": '" + v + "'");
                    }
                }
            } else if (def instanceof Number) {
                cache.put(key, db.getInt(key, ((Number) def).intValue()));
            } else {
                cache.put(key, db.getString(key, (String) def));
            }
        }
    }

    // ── public API (same signatures as before) ────────────────

    public synchronized String getString(String key, String def) {
        Object v = cache.get(key);
        return v instanceof String s ? s : def;
    }

    public synchronized int getInt(String key, int def) {
        Object v = cache.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public synchronized double getDouble(String key, double def) {
        Object v = cache.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public synchronized boolean getBoolean(String key, boolean def) {
        Object v = cache.get(key);
        return v instanceof Boolean b ? b : def;
    }

    public synchronized void set(String key, Object value) {
        cache.put(key, value);
        // write-through to DB
        if (value instanceof Boolean b) db.setBoolean(key, b);
        else if (value instanceof Number n) {
            if (value instanceof Double || value instanceof Float) {
                db.set(key, value.toString());
            } else {
                db.setInt(key, n.intValue());
            }
        } else {
            db.set(key, value != null ? value.toString() : "");
        }
    }

    /** Bulk-load from a map (used by ProfileManager). Does NOT persist. */
    public synchronized void loadFromMap(Map<String, Object> m) {
        if (m != null) { cache.clear(); cache.putAll(m); }
    }

    public synchronized Map<String, Object> getAll() { return new HashMap<>(cache); }

    public synchronized void save() { /* no-op: set() already writes through */ }

    private synchronized void put(String k, Object v) { cache.put(k, v); }
}
