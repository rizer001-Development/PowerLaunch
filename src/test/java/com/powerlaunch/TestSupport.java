package com.powerlaunch;

import com.powerlaunch.launcher.LauncherHomeProvider;
import com.powerlaunch.storage.AppDatabase;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test helpers: redirect the launcher home to a temp directory and reset
 * the static singletons so each test class starts from a clean state.
 */
public final class TestSupport {

    private TestSupport() {}

    /** Creates a fresh temp launcher home and points PowerLaunch at it. */
    public static Path setUpTempHome() throws Exception {
        Path home = Files.createTempDirectory("powerlaunch-test-home");
        // Point the launcher at the temp dir BEFORE any manager is created.
        System.setProperty("powerlaunch.home", home.toString());
        LauncherHomeProvider.resetForTests();
        resetAllSingletons();
        return home;
    }

    /**
     * Simulates a restart: closes the DB and resets all singletons, keeping
     * the current powerlaunch.home so the next getInstance() reloads from
     * the same database file.
     */
    public static void reload() throws Exception {
        closeDatabase();
        resetAllSingletons();
        LauncherHomeProvider.resetForTests();
    }

    /** Closes the DB and resets all singletons; clears the temp-home property. */
    public static void tearDown() throws Exception {
        closeDatabase();
        resetAllSingletons();
        LauncherHomeProvider.resetForTests();
        System.clearProperty("powerlaunch.home");
    }

    private static void closeDatabase() {
        try {
            Field f = AppDatabase.class.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            AppDatabase inst = (AppDatabase) f.get(null);
            if (inst != null) {
                try {
                    inst.close();
                } catch (Exception ignored) {
                    // close is best-effort
                }
            }
        } catch (Exception ignored) {
            // reflect access failed — nothing to close
        }
    }

    private static void resetAllSingletons() throws Exception {
        resetSingleton(AppDatabase.class, "INSTANCE");
        resetSingleton(com.powerlaunch.settings.SettingsManager.class, "instance");
        resetSingleton(com.powerlaunch.auth.AccountManager.class, "instance");
    }

    private static void resetSingleton(Class<?> cls, String fieldName) throws Exception {
        Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(null, null);
    }
}