package com.powerlaunch.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class SettingsManager {
    private static SettingsManager instance;
    private final Path configPath;
    private final Gson gson;
    private final Map<String, Object> settings;

    private SettingsManager() {
        gson = new GsonBuilder().setPrettyPrinting().create();
        configPath = com.powerlaunch.launcher.LauncherHomeProvider.getConfigFile();
        settings = new HashMap<>();
        loadDefaults();
        load();
    }

    public static synchronized SettingsManager getInstance() {
        if (instance == null) {
            instance = new SettingsManager();
        }
        return instance;
    }

    public void loadDefaults() {
        settings.clear();
        settings.put("username", "");
        settings.put("ram", 4096);
        settings.put("gameWidth", 854);
        settings.put("gameHeight", 480);
        settings.put("javaArgs", "");
        settings.put("selectedVersion", "latest");
        settings.put("selectedModpack", "vanilla");
        settings.put("gameDirectory", "");
        settings.put("javaPath", "");
        settings.put("javaChoice", "auto");
        settings.put("gpuChoice", "auto");
        settings.put("useCustomResolution", false);
        settings.put("enableSkins", true);
        settings.put("serverIp", "");
        settings.put("autoLogin", false);
        settings.put("autoConnect", false);
        settings.put("connectServerIp", "");
        settings.put("theme", "dark");
        settings.put("showNews", true);
        settings.put("saveConsoleLog", true);
    }

    public void load() {
        try {
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> loaded = gson.fromJson(content, type);
                if (loaded != null) {
                    settings.putAll(loaded);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load config: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, gson.toJson(settings));
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    public String getString(String key, String defaultValue) {
        Object val = settings.get(key);
        return val instanceof String ? (String) val : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object val = settings.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = settings.get(key);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return defaultValue;
    }

    public void set(String key, Object value) {
        settings.put(key, value);
        save();
    }

    public void loadFromMap(Map<String, Object> newSettings) {
        if (newSettings != null) {
            settings.clear();
            settings.putAll(newSettings);
        }
    }

    public Map<String, Object> getAll() {
        return new HashMap<>(settings);
    }
}
