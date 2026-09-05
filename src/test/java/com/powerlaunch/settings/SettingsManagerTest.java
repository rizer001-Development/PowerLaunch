package com.powerlaunch.settings;

import com.powerlaunch.TestSupport;
import com.powerlaunch.storage.AppDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the settings added by the new UI work:
 * double values (animation speed), color/design keys and persistence.
 */
class SettingsManagerTest {

    @BeforeEach
    void setUp() throws Exception {
        TestSupport.setUpTempHome();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestSupport.tearDown();
    }

    @Test
    void getDouble_returnsDefault_whenNotSet() {
        SettingsManager sm = SettingsManager.getInstance();
        assertEquals(1.0, sm.getDouble("animationSpeed", 1.0), 0.0001);
    }

    @Test
    void setDouble_roundTripsThroughCache() {
        SettingsManager sm = SettingsManager.getInstance();
        sm.set("animationSpeed", 1.5);
        assertEquals(1.5, sm.getDouble("animationSpeed", 1.0), 0.0001);
    }

    @Test
    void setDouble_persistsToDatabaseAsString() {
        SettingsManager sm = SettingsManager.getInstance();
        sm.set("animationSpeed", 1.25);
        assertEquals("1.25", AppDatabase.getInstance().getString("animationSpeed", null));
    }

    @Test
    void setDouble_survivesReload() throws Exception {
        SettingsManager sm = SettingsManager.getInstance();
        sm.set("animationSpeed", 1.75);
        sm.set("gameWidth", 1280);
        sm.set("autoLogin", true);
        sm.set("theme", "Dark");

        // Simulate a restart: rebuild the manager from the same DB.
        TestSupport.reload();

        SettingsManager reloaded = SettingsManager.getInstance();
        assertEquals(1.75, reloaded.getDouble("animationSpeed", 1.0), 0.0001);
        assertEquals(1280, reloaded.getInt("gameWidth", 854));
        assertTrue(reloaded.getBoolean("autoLogin", false));
        assertEquals("Dark", reloaded.getString("theme", "dark"));
    }

    @Test
    void designSettings_arePersistedAcrossReload() throws Exception {
        SettingsManager sm = SettingsManager.getInstance();
        sm.set("backgroundColor", "#112233");
        sm.set("accentColor", "#aabbcc");
        sm.set("textColor", "#ffffff");
        sm.set("gradientEnabled", true);
        sm.set("gradientType", "Радиальный");
        sm.set("gradientDirection", "Диагональ");
        sm.set("animationType", "Пружинные");
        sm.set("settingsLastTab", "Дизайн");
        sm.set("connectServerIp", "play.example.com:25565");

        TestSupport.reload();

        SettingsManager reloaded = SettingsManager.getInstance();
        assertEquals("#112233", reloaded.getString("backgroundColor", "#0f172a"));
        assertEquals("#aabbcc", reloaded.getString("accentColor", "#3b82f6"));
        assertEquals("#ffffff", reloaded.getString("textColor", "#f8fafc"));
        assertTrue(reloaded.getBoolean("gradientEnabled", false));
        assertEquals("Радиальный", reloaded.getString("gradientType", "Линейный"));
        assertEquals("Диагональ", reloaded.getString("gradientDirection", "Сверху вниз"));
        assertEquals("Пружинные", reloaded.getString("animationType", "Плавные"));
        assertEquals("Дизайн", reloaded.getString("settingsLastTab", "Основные"));
        assertEquals("play.example.com:25565", reloaded.getString("connectServerIp", ""));
    }

    @Test
    void theme_defaultsToDarkButAllowsOtherThemes() {
        SettingsManager sm = SettingsManager.getInstance();
        assertEquals("dark", sm.getString("theme", "dark").toLowerCase());

        sm.set("theme", "Cyberpunk");
        assertEquals("cyberpunk", sm.getString("theme", "dark").toLowerCase());
        assertEquals("Cyberpunk", AppDatabase.getInstance().getString("theme", null));
    }

    @Test
    void loadFromMap_replacesCacheWithoutPersisting() {
        SettingsManager sm = SettingsManager.getInstance();
        sm.set("gameWidth", 854);
        sm.loadFromMap(Map.of("gameWidth", 1920, "gameHeight", 1080));
        assertEquals(1920, sm.getInt("gameWidth", 854));
        assertEquals(1080, sm.getInt("gameHeight", 480));
        // Not persisted
        assertEquals("854", AppDatabase.getInstance().getString("gameWidth", null));
    }

    @Test
    void integerAndFloatSettings_doNotLosePrecision() {
        SettingsManager sm = SettingsManager.getInstance();
        sm.set("animationSpeed", 0.5);
        assertEquals(0.5, sm.getDouble("animationSpeed", 1.0), 0.0001);
        // Must NOT be truncated to 0
        assertEquals("0.5", AppDatabase.getInstance().getString("animationSpeed", null));
    }
}