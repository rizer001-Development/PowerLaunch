package com.powerlaunch.storage;

import com.powerlaunch.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AppDatabase CRUD — covers the shared SQLite schema used by
 * the new settings tabs (tabs table, tab_settings, launcher_state).
 */
class AppDatabaseTest {

    @BeforeEach
    void setUp() throws Exception {
        TestSupport.setUpTempHome();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestSupport.tearDown();
    }

    @Test
    void settings_roundTrip() {
        AppDatabase db = AppDatabase.getInstance();
        db.set("greeting", "hello");
        assertEquals("hello", db.getString("greeting", null));
        assertEquals("fallback", db.getString("missing", "fallback"));
    }

    @Test
    void settings_intAndBoolean() {
        AppDatabase db = AppDatabase.getInstance();
        db.setInt("ram", 8192);
        assertEquals(8192, db.getInt("ram", 0));
        // corrupted value falls back to default
        db.set("ram", "not-a-number");
        assertEquals(0, db.getInt("ram", 0));

        db.setBoolean("flag", true);
        assertTrue(db.getBoolean("flag", false));
        assertFalse(db.getBoolean("nope", false));
    }

    @Test
    void settings_doubleStoredAsString() {
        AppDatabase db = AppDatabase.getInstance();
        db.set("animationSpeed", "1.5");
        assertEquals("1.5", db.getString("animationSpeed", null));
    }

    @Test
    void accounts_crud() {
        AppDatabase db = AppDatabase.getInstance();
        db.insertAccount("Alice", "uuid-1");
        db.insertAccount("Bob", "uuid-2");
        db.insertAccount("Alice", "uuid-1"); // ignored (UNIQUE)

        List<AppDatabase.Account> all = db.getAllAccounts();
        assertEquals(2, all.size());

        db.deleteAccount("Alice");
        assertEquals(1, db.getAllAccounts().size());
    }

    @Test
    void servers_replaceAll() {
        AppDatabase db = AppDatabase.getInstance();
        db.replaceAllServers(List.of(
                new AppDatabase.Server("Hub", "play.example.com", "25565"),
                new AppDatabase.Server("Survival", "surv.example.com", "25565")
        ));
        List<AppDatabase.Server> servers = db.getAllServers();
        assertEquals(2, servers.size());
        assertEquals("Hub", servers.get(0).name());

        // Replacing clears the old list
        db.replaceAllServers(List.of(new AppDatabase.Server("Only", "only.example.com", "19132")));
        assertEquals(1, db.getAllServers().size());
    }

    @Test
    void versions_replaceAll() {
        AppDatabase db = AppDatabase.getInstance();
        db.replaceAllVersions(List.of("1.20.4", "1.21", "snapshot-24w05a"));
        assertEquals(3, db.getAllVersions().size());

        db.replaceAllVersions(List.of("1.21"));
        assertEquals(1, db.getAllVersions().size());
    }

    @Test
    void logNumbering_increments() {
        AppDatabase db = AppDatabase.getInstance();
        int first = db.nextLogNumber();
        int second = db.nextLogNumber();
        int third = db.nextLogNumber();
        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(3, third);
    }

    @Test
    void tabsTables_exist() throws Exception {
        AppDatabase db = AppDatabase.getInstance();
        // The new tabs/tab_settings/launcher_state tables must be present
        try (var rs = db.getConnection().createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('tabs','tab_settings','launcher_state')")) {
            java.util.Set<String> found = new java.util.HashSet<>();
            while (rs.next()) found.add(rs.getString(1));
            assertTrue(found.contains("tabs"), "tabs table missing");
            assertTrue(found.contains("tab_settings"), "tab_settings table missing");
            assertTrue(found.contains("launcher_state"), "launcher_state table missing");
        }
    }
}