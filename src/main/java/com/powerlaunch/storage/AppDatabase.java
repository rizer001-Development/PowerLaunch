package com.powerlaunch.storage;

import com.powerlaunch.launcher.LauncherHomeProvider;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Single SQLite database for ALL launcher data:
 * settings, accounts, servers, versions, tabs, tab_settings,
 * launcher_state, log numbering.
 */
public final class AppDatabase {

    private static volatile AppDatabase INSTANCE;
    private final Connection conn;

    private AppDatabase() {
        try {
            Path dbDir = LauncherHomeProvider.getLauncherHome();
            dbDir.toFile().mkdirs();
            Path dbPath = dbDir.resolve("powerlaunch.db");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            createTables();
            System.out.println("[PowerLaunch] AppDatabase opened: " + dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open AppDatabase", e);
        }
    }

    public static AppDatabase getInstance() {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) INSTANCE = new AppDatabase();
            }
        }
        return INSTANCE;
    }

    /** Return raw connection for TabDatabase (shared DB). */
    public Connection getConnection() { return conn; }

    public void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException e) { System.err.println("[AppDatabase] close: " + e.getMessage()); }
    }

    // ── Schema ────────────────────────────────────────────────

    private void createTables() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    username   TEXT NOT NULL UNIQUE,
                    uuid       TEXT NOT NULL,
                    created_at INTEGER NOT NULL DEFAULT 0
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS servers (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL DEFAULT '',
                    ip   TEXT NOT NULL,
                    port TEXT NOT NULL DEFAULT '25565'
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS versions (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    version TEXT NOT NULL UNIQUE
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS log_numbering (
                    id    INTEGER PRIMARY KEY CHECK (id = 1),
                    value INTEGER NOT NULL DEFAULT 0
                )""");
            // ── Tabs ──
            s.execute("""
                CREATE TABLE IF NOT EXISTS tabs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    name TEXT NOT NULL DEFAULT 'New Tab',
                    version TEXT NOT NULL DEFAULT '',
                    game_directory TEXT NOT NULL DEFAULT '',
                    java_path TEXT NOT NULL DEFAULT '',
                    java_args TEXT NOT NULL DEFAULT '',
                    ram INTEGER NOT NULL DEFAULT 4096,
                    server_ip TEXT NOT NULL DEFAULT '',
                    auto_connect INTEGER NOT NULL DEFAULT 0,
                    use_custom_resolution INTEGER NOT NULL DEFAULT 0,
                    game_width INTEGER NOT NULL DEFAULT 854,
                    game_height INTEGER NOT NULL DEFAULT 480,
                    gpu_choice TEXT NOT NULL DEFAULT 'auto',
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS tab_settings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tab_id INTEGER NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL DEFAULT '',
                    UNIQUE(tab_id, key),
                    FOREIGN KEY (tab_id) REFERENCES tabs(id) ON DELETE CASCADE
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS launcher_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL DEFAULT ''
                )""");
        }
    }

    // ── Settings ──────────────────────────────────────────────

    public synchronized String getString(String key, String def) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) { dbErr("getString", e); }
        return def;
    }

    public synchronized int getInt(String key, int def) {
        String v = getString(key, null);
        if (v != null) { try { return Integer.parseInt(v); } catch (NumberFormatException e) {
            System.err.println("[AppDatabase] Invalid integer value for key " + key + ": '" + v + "'");
        } }
        return def;
    }

    public synchronized boolean getBoolean(String key, boolean def) {
        String v = getString(key, null);
        if (v != null) return "true".equalsIgnoreCase(v);
        return def;
    }

    public synchronized void set(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)")) {
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
        } catch (SQLException e) { dbErr("set", e); }
    }

    public synchronized void setInt(String key, int value)     { set(key, String.valueOf(value)); }
    public synchronized void setBoolean(String key, boolean v) { set(key, String.valueOf(v)); }

    // ── Accounts ──────────────────────────────────────────────

    public static record Account(String username, String uuid, long createdAt) {
        /** Compatibility getters for code that used the old POJO style. */
        public String getUsername()    { return username; }
        public String getUuid()        { return uuid; }
        public long   getCreatedAt()   { return createdAt; }
    }

    public synchronized List<Account> getAllAccounts() {
        List<Account> list = new ArrayList<>();
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT username,uuid,created_at FROM accounts ORDER BY id")) {
            while (rs.next()) list.add(new Account(
                    rs.getString("username"), rs.getString("uuid"), rs.getLong("created_at")));
        } catch (SQLException e) { dbErr("getAllAccounts", e); }
        return list;
    }

    public synchronized void insertAccount(String username, String uuid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO accounts(username,uuid,created_at) VALUES(?,?,?)")) {
            ps.setString(1, username); ps.setString(2, uuid);
            ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate();
        } catch (SQLException e) { dbErr("insertAccount", e); }
    }

    public synchronized void deleteAccount(String username) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM accounts WHERE username=?")) {
            ps.setString(1, username); ps.executeUpdate();
        } catch (SQLException e) { dbErr("deleteAccount", e); }
    }

    // ── Servers ───────────────────────────────────────────────

    public static record Server(String name, String ip, String port) {}

    public synchronized List<Server> getAllServers() {
        List<Server> list = new ArrayList<>();
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT name,ip,port FROM servers ORDER BY id")) {
            while (rs.next()) list.add(new Server(
                    rs.getString("name"), rs.getString("ip"), rs.getString("port")));
        } catch (SQLException e) { dbErr("getAllServers", e); }
        return list;
    }

    public synchronized void replaceAllServers(List<Server> servers) {
        try {
            conn.setAutoCommit(false);
            conn.createStatement().executeUpdate("DELETE FROM servers");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO servers(name,ip,port) VALUES(?,?,?)")) {
                for (Server s : servers) { ps.setString(1,s.name()); ps.setString(2,s.ip()); ps.setString(3,s.port()); ps.addBatch(); }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) { dbErr("replaceAllServers", e); }
        finally { try { conn.setAutoCommit(true); } catch (SQLException e) { System.err.println("[AppDatabase] Failed to set auto-commit (replaceAllServers): " + e.getMessage()); } }
    }

    // ── Versions ──────────────────────────────────────────────

    public synchronized List<String> getAllVersions() {
        List<String> list = new ArrayList<>();
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT version FROM versions ORDER BY id")) {
            while (rs.next()) list.add(rs.getString("version"));
        } catch (SQLException e) { dbErr("getAllVersions", e); }
        return list;
    }

    public synchronized void replaceAllVersions(List<String> versions) {
        try {
            conn.setAutoCommit(false);
            conn.createStatement().executeUpdate("DELETE FROM versions");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO versions(version) VALUES(?)")) {
                for (String v : versions) { ps.setString(1, v); ps.addBatch(); }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) { dbErr("replaceAllVersions", e); }
        finally { try { conn.setAutoCommit(true); } catch (SQLException e) { System.err.println("[AppDatabase] Failed to set auto-commit (replaceAllVersions): " + e.getMessage()); } }
    }

    // ── Log Numbering ─────────────────────────────────────────

    public synchronized int nextLogNumber() {
        try {
            conn.createStatement().executeUpdate(
                    "INSERT INTO log_numbering(id,value) VALUES(1,1) ON CONFLICT(id) DO UPDATE SET value=value+1");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT value FROM log_numbering WHERE id=1")) {
                if (rs.next()) return rs.getInt("value");
            }
        } catch (SQLException e) { dbErr("nextLogNumber", e); }
        return 1;
    }

    // ── Utility ───────────────────────────────────────────────

    private static void dbErr(String ctx, SQLException e) {
        System.err.println("[AppDatabase] " + ctx + ": " + e.getMessage());
    }
}
