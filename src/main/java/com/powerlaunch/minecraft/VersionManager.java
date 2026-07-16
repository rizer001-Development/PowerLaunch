package com.powerlaunch.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class VersionManager {
    private static VersionManager instance;
    private static final String VERSIONS_FILE = "versions.json";
    private final Path versionsPath;
    private final Gson gson;
    private List<String> installedVersions;
    private String currentVersion;

    private VersionManager() {
        gson = new GsonBuilder().setPrettyPrinting().create();
        versionsPath = getConfigDir().resolve(VERSIONS_FILE);
        installedVersions = new ArrayList<>();
        currentVersion = "";
        load();
    }

    public static synchronized VersionManager getInstance() {
        if (instance == null) {
            instance = new VersionManager();
        }
        return instance;
    }

    private Path getConfigDir() {
        String os = System.getProperty("os.name").toLowerCase();
        Path basePath;
        if (os.contains("win")) {
            basePath = Paths.get(System.getenv("APPDATA"));
        } else if (os.contains("mac")) {
            basePath = Paths.get(System.getProperty("user.home"), "Library", "Application Support");
        } else {
            basePath = Paths.get(System.getProperty("user.home"), ".config");
        }
        return basePath.resolve("PowerLaunch");
    }

    private Path getGameDir() {
        String customDir = com.powerlaunch.settings.SettingsManager.getInstance().getString("gameDirectory", "");
        if (!customDir.isEmpty()) {
            return Paths.get(customDir, "versions");
        }
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return Paths.get(System.getenv("APPDATA"), ".powerlaunch", "versions");
        } else if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", ".powerlaunch", "versions");
        }
        return Paths.get(System.getProperty("user.home"), ".powerlaunch", "versions");
    }

    public void load() {
        try {
            // Load saved versions list
            if (Files.exists(versionsPath)) {
                String content = Files.readString(versionsPath);
                Type type = new TypeToken<List<String>>() {}.getType();
                List<String> loaded = gson.fromJson(content, type);
                if (loaded != null) {
                    installedVersions = loaded;
                }
            }

            // Scan game directory for installed versions
            Path gameVersionsDir = getGameDir();
            if (Files.exists(gameVersionsDir)) {
                try (var stream = Files.list(gameVersionsDir)) {
                    stream.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .filter(v -> !installedVersions.contains(v))
                            .forEach(installedVersions::add);
                }
            }

            // Set current version from settings
            String savedVersion = com.powerlaunch.settings.SettingsManager.getInstance().getString("selectedVersion", "");
            if (!savedVersion.isEmpty() && installedVersions.contains(savedVersion)) {
                currentVersion = savedVersion;
            } else if (currentVersion.isEmpty() && !installedVersions.isEmpty()) {
                currentVersion = installedVersions.get(0);
            }

            save();
        } catch (IOException e) {
            System.err.println("Failed to load versions: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(versionsPath.getParent());
            Files.writeString(versionsPath, gson.toJson(installedVersions));
        } catch (IOException e) {
            System.err.println("Failed to save versions: " + e.getMessage());
        }
    }

    public void addVersion(String versionId) {
        if (!installedVersions.contains(versionId)) {
            installedVersions.add(versionId);
            save();
        }
    }

    public boolean removeVersion(String versionId) {
        boolean removed = installedVersions.remove(versionId);
        if (removed) {
            if (currentVersion.equals(versionId)) {
                currentVersion = installedVersions.isEmpty() ? "" : installedVersions.get(0);
            }
            save();
        }
        return removed;
    }

    public boolean selectVersion(String versionId) {
        if (installedVersions.contains(versionId)) {
            currentVersion = versionId;
            com.powerlaunch.settings.SettingsManager.getInstance().set("selectedVersion", versionId);
            return true;
        }
        return false;
    }

    public void reload() {
        installedVersions.clear();
        currentVersion = "";
        Path gameVersionsDir = getGameDir();
        if (Files.exists(gameVersionsDir)) {
            try (var stream = Files.list(gameVersionsDir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .forEach(installedVersions::add);
            } catch (IOException ignored) {}
        }
        String savedVersion = com.powerlaunch.settings.SettingsManager.getInstance().getString("selectedVersion", "");
        if (!savedVersion.isEmpty() && installedVersions.contains(savedVersion)) {
            currentVersion = savedVersion;
        } else if (!installedVersions.isEmpty()) {
            currentVersion = installedVersions.get(0);
        }
        save();
    }

    public void scanForVersions() {
        Path gameVersionsDir = getGameDir();
        if (Files.exists(gameVersionsDir)) {
            try (var stream = Files.list(gameVersionsDir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(v -> !installedVersions.contains(v))
                        .forEach(installedVersions::add);
            } catch (IOException ignored) {}
        }
        save();
    }

    public List<String> getInstalledVersions() {
        return new ArrayList<>(installedVersions);
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public boolean hasVersions() {
        return !installedVersions.isEmpty();
    }

    public Path getGameDirectory() {
        String customDir = com.powerlaunch.settings.SettingsManager.getInstance().getString("gameDirectory", "");
        if (!customDir.isEmpty()) {
            return Paths.get(customDir);
        }
        return getGameDir().getParent();
    }
}
