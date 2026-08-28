package com.powerlaunch.minecraft;

import com.powerlaunch.storage.AppDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Version list — stored in SQLite via {@link AppDatabase}.
 * On each load scans the game directory for installed versions.
 *
 * <p>Works both when the configured game directory directly contains
 * version folders (each has {@code <ver>.json} + {@code <ver>.jar})
 * and when it uses the standard {@code versions/} subfolder layout.</p>
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

    /**
     * Default game root when no custom directory is set:
     * {@code %APPDATA%\.powerlaunch} (Windows), Application Support / .config on others.
     */
    private Path getDefaultGameRoot() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win"))
            return Paths.get(System.getenv("APPDATA"), ".powerlaunch");
        if (os.contains("mac"))
            return Paths.get(System.getProperty("user.home"),
                    "Library", "Application Support", ".powerlaunch");
        return Paths.get(System.getProperty("user.home"), ".powerlaunch");
    }

    /**
     * Returns the directories that may contain version folders.
     * Candidates (in order):
     * <ol>
     *   <li>custom dir itself, if it directly contains version folders</li>
     *   <li>custom dir + "versions"</li>
     *   <li>custom dir + ".minecraft" + "versions" (if user picked .minecraft root)</li>
     *   <li>default game root + "versions"</li>
     * </ol>
     */
    private List<Path> getVersionDirCandidates() {
        List<Path> res = new ArrayList<>();
        String custom = com.powerlaunch.settings.SettingsManager.getInstance()
                .getString("gameDirectory", "").trim();

        if (!custom.isEmpty()) {
            Path root = Paths.get(custom);
            res.add(root);                          // versions directly in folder
            res.add(root.resolve("versions"));      // <root>/versions
            res.add(root.resolve(".minecraft").resolve("versions")); // <root>/.minecraft/versions
            // If user picked <root>/versions itself, that is root and root/versions is wrong:
            if (root.getFileName() != null && "versions".equalsIgnoreCase(root.getFileName().toString())) {
                res.add(root.getParent());
            }
        }

        res.add(getDefaultGameRoot().resolve("versions"));
        // dedupe
        return res.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Scans all candidate directories and merges the version folder names.
     * Only real version dirs (containing &lt;name&gt;.json) are counted.
     */
    private void scanForVersionDirs() {
        for (Path dir : getVersionDirCandidates()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                        .filter(this::looksLikeVersionDir)
                        .map(p -> p.getFileName().toString())
                        .forEach(v -> { if (!installedVersions.contains(v)) installedVersions.add(v); });
            } catch (IOException ignored) {}
        }
    }

    /**
     * A folder is a version dir if it contains {@code <name>.json} (and optionally .jar).
     * Falls back to accepting any folder if we can't tell (flexible).
     */
    private boolean looksLikeVersionDir(Path dir) {
        String name = dir.getFileName().toString();
        return Files.exists(dir.resolve(name + ".json")) || Files.exists(dir.resolve(name + ".jar"));
    }

    private void load() {
        installedVersions = AppDatabase.getInstance().getAllVersions();
        scanForVersionDirs();

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
            com.powerlaunch.settings.SettingsManager.getInstance().set("selectedVersion", v);
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
        Path gameVersionsDir = getVersionDirCandidates().stream()
                .filter(Files::isDirectory).findFirst().orElse(null);
        if (gameVersionsDir != null) scanForVersionDirs();
        syncToDb();
    }

    public List<String> getInstalledVersions() { return new ArrayList<>(installedVersions); }
    public String       getCurrentVersion()    { return currentVersion; }
    public boolean      hasVersions()          { return !installedVersions.isEmpty(); }

    public Path getGameDirectory() {
        String custom = com.powerlaunch.settings.SettingsManager.getInstance()
                .getString("gameDirectory", "");
        return custom.isEmpty() ? getDefaultGameRoot() : Paths.get(custom);
    }
}