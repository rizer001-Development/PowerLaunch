package com.powerlaunch.launcher;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Single source of truth for the "launcher folder path" — the place where
 * settings, accounts, profiles, servers and skins.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>env {@code POWERLAUNCH_HOME} — absolute path. Convenient for portable distribution.</li>
 *   <li>system property {@code powerlaunch.home} — for Gradle {@code ./gradlew run} or
 *       jpackage with flag {@code -Dpowerlaunch.home=...}.</li>
 *   <li>Code-source relative: if running from {@code .jar} → path next to jar.
 *       If running from class directory (Gradle dev-mode) → separate
 *       {@code build/dev-launcher-home}, to avoid conflicting with the reference folder.</li>
 *   <li>Legacy fallback: {@code %APPDATA%/PowerLaunch} (desktop installation via Wix).</li>
 * </ol>
 *
 * <p>The directory is created automatically on first call {@link #getLauncherHome()}.
 * All managers (Settings/Account/Profile/Server/Skin) should use the methods
 * of this class instead of hardcoded {@code System.getenv("APPDATA")}.
 */
public final class LauncherHomeProvider {

    private static final String LAUNCHER_DIR_NAME = "PowerLaunch";
    private static final String APP_SUBDIR = "app";
    private static final String DEV_LAUNCHER_HOME_NAME = "dev-launcher-home";

    private static volatile Path cachedHome;

    private LauncherHomeProvider() {}

    /** Returns the absolute path to LauncherHome. Creates directory if missing. */
    public static Path getLauncherHome() {
        Path home = cachedHome;
        if (home != null) return home;
        synchronized (LauncherHomeProvider.class) {
            if (cachedHome != null) return cachedHome;
            cachedHome = resolveHome().toAbsolutePath();
            try {
                Files.createDirectories(cachedHome);
                migrateLegacyData(cachedHome);
            } catch (Exception e) {
                System.err.println("[PowerLaunch] LauncherHome migration failed: " + e.getMessage() + " (will try to continue)"); }
            System.out.println("[PowerLaunch] LauncherHome = " + cachedHome);
            return cachedHome;
        }
    }

    /** Resets cache (for tests and when the path needs recalculation). */
    public static void resetForTests() {
        synchronized (LauncherHomeProvider.class) {
            cachedHome = null;
        }
    }

    private static Path resolveHome() {
        // 1. POWERLAUNCH_HOME environment variable.
        String env = System.getenv("POWERLAUNCH_HOME");
        if (env != null && !env.isEmpty()) {
            return Paths.get(env);
        }

        // 2. Java system property powerlaunch.home (Gradle run, jpackage -D).
        String sysProp = System.getProperty("powerlaunch.home");
        if (sysProp != null && !sysProp.isEmpty()) {
            return Paths.get(sysProp);
        }

        // 3. Code-source relative — jar mode or class directory.
        try {
            Path code = Paths.get(LauncherHomeProvider.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(code)) {
                // Running from .jar. Expected structure: <root>/app/PowerLaunch-1.0.0.jar.
                // LauncherHome = <root> (contains lib, custom-jre, scripts folders).
                Path jarDir = code.getParent(); // <root>/app
                if (jarDir != null && APP_SUBDIR.equals(jarDir.getFileName().toString())) {
                    Path root = jarDir.getParent();
                    if (root != null) return root;
                }
                // Generic fallback — one level above the jar file.
                Path parent = (jarDir != null) ? jarDir.getParent() : null;
                if (parent != null) return parent.resolve(LAUNCHER_DIR_NAME);
            } else if (Files.isDirectory(code)) {
                // Running from "build/classes/java/main" — this is Gradle dev-mode.
                // Using SEPARATE build/dev-launcher-home to isolate from
                // the reference build/distributions/PowerLaunch/.
                Path ancestor = code.toAbsolutePath();
                for (int i = 0; i < 3 && ancestor.getParent() != null; i++) {
                    ancestor = ancestor.getParent();
                }
                return ancestor.resolve("build").resolve(DEV_LAUNCHER_HOME_NAME);
            }
        } catch (URISyntaxException e) {
            System.err.println("[PowerLaunch] LauncherHome URI syntax error: " + e.getMessage() + " (falling back to user home)");
        }

        // 4. Legacy fallback (Wix installer installation on Windows / .config / Application Support).
        return legacyHome();
    }

    private static Path legacyHome() {
        String os = System.getProperty("os.name").toLowerCase();
        Path base;
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            base = (appdata != null) ? Paths.get(appdata) : Paths.get(System.getProperty("user.home"));
        } else if (os.contains("mac")) {
            base = Paths.get(System.getProperty("user.home"), "Library", "Application Support");
        } else {
            base = Paths.get(System.getProperty("user.home"), ".config");
        }
        return base.resolve(LAUNCHER_DIR_NAME);
    }

    public static Path getProfilesDir()      { return getLauncherHome().resolve("profiles"); }
    public static Path getSkinsDir()         { return getLauncherHome().resolve("skins"); }
    public static Path getConfigFile()       { return getLauncherHome().resolve("config.json"); }
    public static Path getAccountsFile()     { return getLauncherHome().resolve("accounts.json"); }
    public static Path getServersFile()      { return getLauncherHome().resolve("servers.json"); }
    public static Path getTabsDb()           { return getLauncherHome().resolve("powerlaunch.db"); }
    public static Path getLauncherCacheDir() { return getLauncherHome().resolve("cache"); }

    private static final String MIGRATION_MARKER = ".migrated.marker";

    /**
     * One-shot data lift: copies config/accounts/servers/profiles/skins/sqlite
     * from legacy %APPDATA%/PowerLaunch to {@code newHome}, if newHome is empty.
     * Called from getLauncherHome() on first run. Marker file
     * .migrated.marker prevents repeated copying.
     */
    private static void migrateLegacyData(Path newHome) {
        try {
            Path marker = newHome.resolve(MIGRATION_MARKER);
            if (Files.exists(marker)) return;
            Path legacy = legacyHome();
            if (legacy.equals(newHome) || !Files.exists(legacy)) {
                try { Files.createFile(marker); } catch (Exception e) {
                    System.err.println("[PowerLaunch] Failed to create migration marker: " + e.getMessage()); }
                return;
            }
            System.out.println("[PowerLaunch] Migrating legacy data \u2192 " + newHome);
            copyIfExists(legacy.resolve("config.json"),    newHome.resolve("config.json"));
            copyIfExists(legacy.resolve("accounts.json"),  newHome.resolve("accounts.json"));
            copyIfExists(legacy.resolve("servers.json"),   newHome.resolve("servers.json"));
            copyIfExists(legacy.resolve("powerlaunch.db"), newHome.resolve("powerlaunch.db"));
            copyDirIfExists(legacy.resolve("profiles"),   newHome.resolve("profiles"));
            copyDirIfExists(legacy.resolve("skins"),      newHome.resolve("skins"));
            try { Files.createFile(marker); } catch (Exception e) {
                System.err.println("[PowerLaunch] Failed to create migration marker (2): " + e.getMessage()); }
        } catch (Exception e) {
            System.err.println("[PowerLaunch] Migration error: " + e.getMessage());
        }
    }

    private static void copyIfExists(Path src, Path dest) {
        try {
            if (Files.exists(src) && !Files.exists(dest)) Files.copy(src, dest);
        } catch (Exception ex) {
            System.err.println("[PowerLaunch] copy " + src + " \u2192 " + dest + ": " + ex.getMessage());
        }
    }

    private static void copyDirIfExists(Path src, Path dest) {
        try {
            if (!Files.exists(src) || !Files.isDirectory(src)) return;
            Files.walk(src).forEach(s -> {
                try {
                    Path t = dest.resolve(src.relativize(s).toString());
                    if (Files.exists(t)) return;
                    if (Files.isDirectory(s)) Files.createDirectories(t);
                    else Files.copy(s, t);
                } catch (Exception ignored) { /* skip individual file errors */ }
            });
        } catch (Exception ex) {
            System.err.println("[PowerLaunch] dir copy " + src + " \u2192 " + dest + ": " + ex.getMessage());
        }
    }
}
