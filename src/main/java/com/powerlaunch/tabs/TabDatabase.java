package com.powerlaunch.tabs;

import java.io.File;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite database layer for PowerLaunch.
 * Stores all tabs and their settings in a single database file.
 * Replaces the old JSON-file-per-tab approach with a proper relational DB.
 *
 * Tables:
 * - tabs: one row per tab, all tab-specific settings as columns
 * - tab_settings: key-value store per tab for UI state (console mode, etc.)
 * - global_settings: key-value store for app-wide settings
 */
public class TabDatabase {

    private static TabDatabase instance;
    private final Path dbPath;
    private Connection connection;

    private static final String DB_NAME = "powerlaunch.db";

    private TabDatabase() {
        dbPath = com.powerlaunch.launcher.LauncherHomeProvider.getTabsDb();
    }

    public static synchronized TabDatabase getInstance() {
        if (instance == null) {
            instance = new TabDatabase();
        }
        return instance;
    }

    /**
     * Opens (or creates) the database and initializes tables.
     */
    public synchronized void open() {
        try {
            // Ensure parent directory exists
            dbPath.getParent().toFile().mkdirs();

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());

            // Enable WAL mode for better concurrent access
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }

            createTables();

        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to open database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Closes the database connection.
     */
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to close database: " + e.getMessage());
        }
    }

    /**
     * Checks if the database is open and ready.
     */
    public synchronized boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== Schema ====================

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {

            // --- tabs table: one row per tab ---
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS tabs (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  sort_order INTEGER NOT NULL DEFAULT 0," +
                "  name TEXT NOT NULL DEFAULT 'Новая вкладка'," +
                "  version TEXT NOT NULL DEFAULT ''," +
                "  game_directory TEXT NOT NULL DEFAULT ''," +
                "  java_path TEXT NOT NULL DEFAULT ''," +
                "  java_args TEXT NOT NULL DEFAULT ''," +
                "  ram INTEGER NOT NULL DEFAULT 4096," +
                "  server_ip TEXT NOT NULL DEFAULT ''," +
                "  auto_connect INTEGER NOT NULL DEFAULT 0," +
                "  use_custom_resolution INTEGER NOT NULL DEFAULT 0," +
                "  game_width INTEGER NOT NULL DEFAULT 854," +
                "  game_height INTEGER NOT NULL DEFAULT 480," +
                "  gpu_choice TEXT NOT NULL DEFAULT 'auto'," +
                "  created_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "  updated_at TEXT NOT NULL DEFAULT (datetime('now'))" +
                ")"
            );

            // --- tab_settings table: key-value per tab ---
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS tab_settings (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  tab_id INTEGER NOT NULL," +
                "  key TEXT NOT NULL," +
                "  value TEXT NOT NULL DEFAULT ''," +
                "  UNIQUE(tab_id, key)," +
                "  FOREIGN KEY (tab_id) REFERENCES tabs(id) ON DELETE CASCADE" +
                ")"
            );

            // --- global_settings table: key-value for app-wide settings ---
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS global_settings (" +
                "  key TEXT PRIMARY KEY," +
                "  value TEXT NOT NULL DEFAULT ''" +
                ")"
            );

            // --- launcher_state: active tab index and other runtime state ---
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS launcher_state (" +
                "  key TEXT PRIMARY KEY," +
                "  value TEXT NOT NULL DEFAULT ''" +
                ")"
            );
        }
    }

    // ==================== Tab CRUD ====================

    /**
     * Loads all tabs from the database, ordered by sort_order.
     */
    public List<TabData> loadAllTabs() {
        List<TabData> tabs = new ArrayList<>();
        if (!isOpen()) return tabs;

        String sql = "SELECT * FROM tabs ORDER BY sort_order ASC, id ASC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                TabData tab = new TabData();
                tab.setDbId(rs.getInt("id"));
                tab.setSortOrder(rs.getInt("sort_order"));
                tab.setName(rs.getString("name"));
                tab.setVersion(rs.getString("version"));
                tab.setGameDirectory(rs.getString("game_directory"));
                tab.setJavaPath(rs.getString("java_path"));
                tab.setJavaArgs(rs.getString("java_args"));
                tab.setRam(rs.getInt("ram"));
                tab.setServerIp(rs.getString("server_ip"));
                tab.setAutoConnect(rs.getInt("auto_connect") == 1);
                tab.setUseCustomResolution(rs.getInt("use_custom_resolution") == 1);
                tab.setGameWidth(rs.getInt("game_width"));
                tab.setGameHeight(rs.getInt("game_height"));
                tab.setGpuChoice(rs.getString("gpu_choice"));

                // Load per-tab settings
                loadTabSettings(tab);

                tabs.add(tab);
            }
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to load tabs: " + e.getMessage());
        }
        return tabs;
    }

    /**
     * Loads key-value settings for a specific tab.
     */
    private void loadTabSettings(TabData tab) {
        String sql = "SELECT key, value FROM tab_settings WHERE tab_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, tab.getDbId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("key");
                    String value = rs.getString("value");
                    switch (key) {
                        case "consoleMode" -> tab.setConsoleMode(value);
                        case "consoleVisible" -> tab.setConsoleVisible("1".equals(value));
                        // Future: add more tab-specific settings here
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to load tab settings: " + e.getMessage());
        }
    }

    /**
     * Inserts a new tab into the database and returns it with the generated ID.
     */
    public TabData insertTab(TabData tab) {
        if (!isOpen()) return tab;

        // Determine next sort_order
        int nextOrder = 0;
        String maxSql = "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM tabs";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(maxSql)) {
            if (rs.next()) nextOrder = rs.getInt(1);
        } catch (SQLException ignored) {}

        String sql = "INSERT INTO tabs (sort_order, name, version, game_directory, java_path, java_args, " +
                "ram, server_ip, auto_connect, use_custom_resolution, game_width, game_height, gpu_choice) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, nextOrder);
            ps.setString(2, tab.getName());
            ps.setString(3, tab.getVersion());
            ps.setString(4, tab.getGameDirectory());
            ps.setString(5, tab.getJavaPath());
            ps.setString(6, tab.getJavaArgs());
            ps.setInt(7, tab.getRam());
            ps.setString(8, tab.getServerIp());
            ps.setInt(9, tab.isAutoConnect() ? 1 : 0);
            ps.setInt(10, tab.isUseCustomResolution() ? 1 : 0);
            ps.setInt(11, tab.getGameWidth());
            ps.setInt(12, tab.getGameHeight());
            ps.setString(13, tab.getGpuChoice());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    tab.setDbId(keys.getInt(1));
                    tab.setSortOrder(nextOrder);
                }
            }

            // Save tab settings
            saveTabSettings(tab);

        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to insert tab: " + e.getMessage());
        }
        return tab;
    }

    /**
     * Updates an existing tab in the database.
     */
    public void updateTab(TabData tab) {
        if (!isOpen() || tab.getDbId() <= 0) return;

        String sql = "UPDATE tabs SET sort_order=?, name=?, version=?, game_directory=?, java_path=?, java_args=?," +
                "ram=?, server_ip=?, auto_connect=?, use_custom_resolution=?, game_width=?, game_height=?, gpu_choice=?," +
                "updated_at=datetime('now') WHERE id=?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, tab.getSortOrder());
            ps.setString(2, tab.getName());
            ps.setString(3, tab.getVersion());
            ps.setString(4, tab.getGameDirectory());
            ps.setString(5, tab.getJavaPath());
            ps.setString(6, tab.getJavaArgs());
            ps.setInt(7, tab.getRam());
            ps.setString(8, tab.getServerIp());
            ps.setInt(9, tab.isAutoConnect() ? 1 : 0);
            ps.setInt(10, tab.isUseCustomResolution() ? 1 : 0);
            ps.setInt(11, tab.getGameWidth());
            ps.setInt(12, tab.getGameHeight());
            ps.setString(13, tab.getGpuChoice());
            ps.setInt(14, tab.getDbId());
            ps.executeUpdate();

            saveTabSettings(tab);

        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to update tab: " + e.getMessage());
        }
    }

    /**
     * Deletes a tab by ID.
     */
    public void deleteTab(int tabId) {
        if (!isOpen() || tabId <= 0) return;

        // tab_settings are deleted via CASCADE
        String sql = "DELETE FROM tabs WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, tabId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to delete tab: " + e.getMessage());
        }
    }

    /**
     * Saves tab-specific settings (console mode, etc.) to tab_settings table.
     */
    public void saveTabSettings(TabData tab) {
        if (!isOpen() || tab.getDbId() <= 0) return;

        // Use INSERT OR REPLACE for each setting
        String sql = "INSERT OR REPLACE INTO tab_settings (tab_id, key, value) VALUES (?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            // Console mode
            ps.setInt(1, tab.getDbId());
            ps.setString(2, "consoleMode");
            ps.setString(3, tab.getConsoleMode());
            ps.executeUpdate();

            // Console visible
            ps.setInt(1, tab.getDbId());
            ps.setString(2, "consoleVisible");
            ps.setString(3, tab.isConsoleVisible() ? "1" : "0");
            ps.executeUpdate();

        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to save tab settings: " + e.getMessage());
        }
    }

    /**
     * Updates the sort_order for all tabs (after reordering).
     */
    public void reorderTabs(List<Integer> tabIdsInOrder) {
        if (!isOpen()) return;

        String sql = "UPDATE tabs SET sort_order=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < tabIdsInOrder.size(); i++) {
                ps.setInt(1, i);
                ps.setInt(2, tabIdsInOrder.get(i));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to reorder tabs: " + e.getMessage());
        }
    }

    // ==================== Global / State ====================

    /**
     * Saves a global setting.
     */
    public void setGlobalSetting(String key, String value) {
        if (!isOpen()) return;

        String sql = "INSERT OR REPLACE INTO global_settings (key, value) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to save global setting: " + e.getMessage());
        }
    }

    /**
     * Loads a global setting.
     */
    public String getGlobalSetting(String key, String defaultValue) {
        if (!isOpen()) return defaultValue;

        String sql = "SELECT value FROM global_settings WHERE key=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to load global setting: " + e.getMessage());
        }
        return defaultValue;
    }

    /**
     * Saves a launcher state value (active tab, etc.).
     */
    public void setState(String key, String value) {
        if (!isOpen()) return;

        String sql = "INSERT OR REPLACE INTO launcher_state (key, value) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to save state: " + e.getMessage());
        }
    }

    /**
     * Loads a launcher state value.
     */
    public String getState(String key, String defaultValue) {
        if (!isOpen()) return defaultValue;

        String sql = "SELECT value FROM launcher_state WHERE key=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            System.err.println("[TabDatabase] Failed to load state: " + e.getMessage());
        }
        return defaultValue;
    }

    /**
     * Cleans up old JSON tab files from the old storage system.
     * Called once after migration to SQLite.
     */
    public void migrateFromJsonFiles() {
        Path configDir = com.powerlaunch.launcher.LauncherHomeProvider.getLauncherHome();

        File tabsDir = configDir.resolve("tabs").toFile();
        if (tabsDir.exists() && tabsDir.isDirectory()) {
            File[] files = tabsDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            // Try to delete the tabs directory
            tabsDir.delete();
            System.out.println("[TabDatabase] Migrated from JSON files — old tabs directory cleaned up.");
        }
    }
}
