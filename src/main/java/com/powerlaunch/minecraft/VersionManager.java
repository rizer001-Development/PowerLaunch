package com.powerlaunch.minecraft;

import com.powerlaunch.storage.AppDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Version list — stored in SQLite via {@link AppDatabase}.
 * On each load also scans the game directory for newly installed versions.
 */
public class VersionManager {
    private static VersionManager instance;
    private final AppDatabase db;
    private List<String> installedVersions;
    private String currentVersion;

    private VersionManager() {
        db = AppDatabase.getInstance();
        installedVersions = new ArrayList<>();
        currentVersion = "";
        load();
    }

    public static synchronized VersionManager getInstance() {
        if (instance == null) instance = new VersionManager();
        return instance;
    }

    private Path getGameDir() {
        String customDir = com.powerlaunch.settings.SettingsManager.getInstance()
                .getString("gameDirectory", "");
        if (!customDir.isEmpty()) return Paths.get(customDir, "versions");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win"))
            return Paths.get(System.getenv("APPDATA"), ".powerlaunch", "versions");
        if (os.contains("mac"))
            return Paths.get(System.getProperty("user.home"),
                    "Library", "Application Support", ".powerlaunch", "versions");
        return Paths.get(System.getProperty("user.home"), ".powerlaunch", "versions");
    }

    private void load() {
        // Load from DB
        installedVersions = db.getAllVersions();

        // Scan disk for new versions
        Path gameVersionsDir = getGameDir();
        if (Files.exists(gameVersionsDir)) {
            try (var stream = Files.list(gameVersionsDir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(v -> !installedVersions.contains(v))
                        .forEach(installedVersions::add);
            } catch (IOException ignored) {}
        }

        // Restore current version
        String saved = com.powerlaunch.settings.SettingsManager.getInstance()
                .getString("selectedVersion", "");
        if (!saved.isEmpty() && installedVersions.contains(saved)) {
            currentVersion = saved;
        } else if (currentVersion.isEmpty() && !installedVersions.isEmpty()) {
            currentVersion = installedVersions.get(0);
        }
        syncToDb();
    }

    private void syncToDb() {
        db.replaceAllVersions(installedVersions);
    }

    public void addVersion(String v) {
        if (!installedVersions.contains(v)) { installedVersions.add(v); syncToDb(); }
    }

    public boolean removeVersion(String v) {
        boolean removed = installedVersions.remove(v);
        if (removed) {
            if (currentVersion.equals(v))
                currentVersion = installedVersions.isEmpty() ? "" : installedVersions.get(0);
            syncToDb();
        }
        return removed;
    }

    public boolean selectVersion(String v) {
        if (installedVersions.contains(v)) {
            currentVersion = v;
            com.powerlaunch.settings.SettingsManager.getInstance()
                    .set("selectedVersion", v);
            return true;
        }
        return false;
    }

    public void reload() {
        installedVersions.clear();
        currentVersion = "";
        load();
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
        syncToDb();
    }

    public List<String> getInstalledVersions() { return new ArrayList<>(installedVersions); }
    public String       getCurrentVersion()    { return currentVersion; }
    public boolean      hasVersions()          { return !installedVersions.isEmpty(); }

    public Path getGameDirectory() {
        String custom = com.powerlaunch.settings.SettingsManager.getInstance()
                .getString("gameDirectory", "");
        return custom.isEmpty() ? getGameDir().getParent() : Paths.get(custom);
    }
}
