package com.powerlaunch.launcher;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Единый источник правды для пути к "папке лаунчера" — месту где хранятся
 * настройки, аккаунты, профили, серверы и скины.
 *
 * <p>Порядок разрешения:
 * <ol>
 *   <li>env {@code POWERLAUNCH_HOME} — абсолютный путь. Удобно для portable дистрибутива.</li>
 *   <li>system property {@code powerlaunch.home} — для Gradle {@code ./gradlew run} или
 *       jpackage с флагом {@code -Dpowerlaunch.home=...}.</li>
 *   <li>Code-source relative: если запущен из {@code .jar} → путь рядом с jar.
 *       Если запущен из директории классов (Gradle dev-mode) → отдельная
 *       {@code build/dev-launcher-home}, чтобы не конфликтовать с эталонной папкой.</li>
 *   <li>Legacy fallback: {@code %APPDATA%/PowerLaunch} (desktop инсталляция через Wix).</li>
 * </ol>
 *
 * <p>Папка создаётся автоматически при первом вызове {@link #getLauncherHome()}.
 * Все managers (Settings/Account/Profile/Server/Skin) должны использовать методы
 * этого класса вместо захардкоженного {@code System.getenv("APPDATA")}.
 */
public final class LauncherHomeProvider {

    private static final String LAUNCHER_DIR_NAME = "PowerLaunch";
    private static final String APP_SUBDIR = "app";
    private static final String DEV_LAUNCHER_HOME_NAME = "dev-launcher-home";

    private static volatile Path cachedHome;

    private LauncherHomeProvider() {}

    /** Возвращает абсолютный путь к LauncherHome. Создаёт директорию при отсутствии. */
    public static Path getLauncherHome() {
        Path home = cachedHome;
        if (home != null) return home;
        synchronized (LauncherHomeProvider.class) {
            if (cachedHome != null) return cachedHome;
            cachedHome = resolveHome().toAbsolutePath();
            try {
                Files.createDirectories(cachedHome);
                migrateLegacyData(cachedHome);
            } catch (Exception ignored) {/* permission errors surface later when managers try to write */}
            System.out.println("[PowerLaunch] LauncherHome = " + cachedHome);
            return cachedHome;
        }
    }

    /** Сбрасывает кеш (для тестов и для ситуаций когда путь нужно пересчитать). */
    public static void resetForTests() {
        synchronized (LauncherHomeProvider.class) {
            cachedHome = null;
        }
    }

    private static Path resolveHome() {
        // 1. Переменная среды POWERLAUNCH_HOME.
        String env = System.getenv("POWERLAUNCH_HOME");
        if (env != null && !env.isEmpty()) {
            return Paths.get(env);
        }

        // 2. Java system property powerlaunch.home (Gradle run, jpackage -D).
        String sysProp = System.getProperty("powerlaunch.home");
        if (sysProp != null && !sysProp.isEmpty()) {
            return Paths.get(sysProp);
        }

        // 3. Code-source relative — jar-режим или директория классов.
        try {
            Path code = Paths.get(LauncherHomeProvider.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(code)) {
                // Запуск из .jar. Ожидаемая структура: <root>/app/PowerLaunch-1.0.0.jar.
                // LauncherHome = <root> (содержит папку lib, custom-jre, scripts).
                Path jarDir = code.getParent(); // <root>/app
                if (jarDir != null && APP_SUBDIR.equals(jarDir.getFileName().toString())) {
                    Path root = jarDir.getParent();
                    if (root != null) return root;
                }
                // Общий fallback — на уровень выше jar-файла.
                Path parent = (jarDir != null) ? jarDir.getParent() : null;
                if (parent != null) return parent.resolve(LAUNCHER_DIR_NAME);
            } else if (Files.isDirectory(code)) {
                // Запуск из "build/classes/java/main" — это Gradle dev-mode.
                // Используем ОТДЕЛЬНУЮ build/dev-launcher-home чтобы изолировать от
                // эталонной build/distributions/PowerLaunch/.
                Path ancestor = code.toAbsolutePath();
                for (int i = 0; i < 3 && ancestor.getParent() != null; i++) {
                    ancestor = ancestor.getParent();
                }
                return ancestor.resolve("build").resolve(DEV_LAUNCHER_HOME_NAME);
            }
        } catch (URISyntaxException ignored) { /* безопасный fallback ниже */ }

        // 4. Legacy fallback (Wix installer инсталляция на Windows / .config / Application Support).
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
     * One-shot data lift: копирует config/accounts/servers/profiles/skins/sqlite
     * из legacy %APPDATA%/PowerLaunch в {@code newHome}, если newHome пуст.
     * Вызывается из getLauncherHome() при первом запуске. Marker-файл
     * .migrated.marker предотвращает повторные копирования.
     */
    private static void migrateLegacyData(Path newHome) {
        try {
            Path marker = newHome.resolve(MIGRATION_MARKER);
            if (Files.exists(marker)) return;
            Path legacy = legacyHome();
            if (legacy.equals(newHome) || !Files.exists(legacy)) {
                try { Files.createFile(marker); } catch (Exception ignored) {}
                return;
            }
            System.out.println("[PowerLaunch] Migrating legacy data \u2192 " + newHome);
            copyIfExists(legacy.resolve("config.json"),    newHome.resolve("config.json"));
            copyIfExists(legacy.resolve("accounts.json"),  newHome.resolve("accounts.json"));
            copyIfExists(legacy.resolve("servers.json"),   newHome.resolve("servers.json"));
            copyIfExists(legacy.resolve("powerlaunch.db"), newHome.resolve("powerlaunch.db"));
            copyDirIfExists(legacy.resolve("profiles"),   newHome.resolve("profiles"));
            copyDirIfExists(legacy.resolve("skins"),      newHome.resolve("skins"));
            try { Files.createFile(marker); } catch (Exception ignored) {}
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
