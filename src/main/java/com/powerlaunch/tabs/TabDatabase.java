package com.powerlaunch.tabs;

import com.powerlaunch.storage.AppDatabase;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab storage layer — uses the shared connection from {@link AppDatabase}.
 * Tables (tabs, tab_settings, launcher_state) are created by AppDatabase.
 */
public class TabDatabase {

    private static TabDatabase instance;
    private Connection connection;

    private TabDatabase() {
        connection = AppDatabase.getInstance().getConnection();
    }

    public static synchronized TabDatabase getInstance() {
        if (instance == null) instance = new TabDatabase();
        return instance;
    }

    /** Tables already created by AppDatabase. */
    public void open() { /* no-op: tables exist */ }

    public synchronized void close() { /* no-op: AppDatabase owns connection */ }

    public synchronized boolean isOpen() {
        try { return connection != null && !connection.isClosed(); }
        catch (SQLException e) { return false; }
    }

    // ── Tab CRUD ──────────────────────────────────────────────

    public List<TabData> loadAllTabs() {
        List<TabData> tabs = new ArrayList<>();
        if (!isOpen()) return tabs;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM tabs ORDER BY sort_order ASC, id ASC")) {
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
                loadTabSettings(tab);
                tabs.add(tab);
            }
        } catch (SQLException e) { err("loadAllTabs", e); }
        return tabs;
    }

    private void loadTabSettings(TabData tab) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT key,value FROM tab_settings WHERE tab_id=?")) {
            ps.setInt(1, tab.getDbId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String k = rs.getString("key"), v = rs.getString("value");
                    switch (k) {
                        case "consoleMode"    -> tab.setConsoleMode(v);
                        case "consoleVisible" -> tab.setConsoleVisible("1".equals(v));
                    }
                }
            }
        } catch (SQLException e) { err("loadTabSettings", e); }
    }

    public TabData insertTab(TabData tab) {
        if (!isOpen()) return tab;
        int nextOrder = 0;
        try (ResultSet rs = connection.createStatement().executeQuery(
                "SELECT COALESCE(MAX(sort_order),-1)+1 FROM tabs")) {
            if (rs.next()) nextOrder = rs.getInt(1);
        } catch (SQLException ignored) {}

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tabs(sort_order,name,version,game_directory,java_path,java_args,"
                + "ram,server_ip,auto_connect,use_custom_resolution,game_width,game_height,gpu_choice)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1,nextOrder); ps.setString(2,tab.getName());
            ps.setString(3,tab.getVersion()); ps.setString(4,tab.getGameDirectory());
            ps.setString(5,tab.getJavaPath()); ps.setString(6,tab.getJavaArgs());
            ps.setInt(7,tab.getRam()); ps.setString(8,tab.getServerIp());
            ps.setInt(9,tab.isAutoConnect()?1:0); ps.setInt(10,tab.isUseCustomResolution()?1:0);
            ps.setInt(11,tab.getGameWidth()); ps.setInt(12,tab.getGameHeight());
            ps.setString(13,tab.getGpuChoice());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) { tab.setDbId(keys.getInt(1)); tab.setSortOrder(nextOrder); }
            }
            saveTabSettings(tab);
        } catch (SQLException e) { err("insertTab", e); }
        return tab;
    }

    public void updateTab(TabData tab) {
        if (!isOpen() || tab.getDbId() <= 0) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE tabs SET sort_order=?,name=?,version=?,game_directory=?,java_path=?,java_args=?,"
                + "ram=?,server_ip=?,auto_connect=?,use_custom_resolution=?,game_width=?,game_height=?,"
                + "gpu_choice=?,updated_at=datetime('now') WHERE id=?")) {
            ps.setInt(1,tab.getSortOrder()); ps.setString(2,tab.getName());
            ps.setString(3,tab.getVersion()); ps.setString(4,tab.getGameDirectory());
            ps.setString(5,tab.getJavaPath()); ps.setString(6,tab.getJavaArgs());
            ps.setInt(7,tab.getRam()); ps.setString(8,tab.getServerIp());
            ps.setInt(9,tab.isAutoConnect()?1:0); ps.setInt(10,tab.isUseCustomResolution()?1:0);
            ps.setInt(11,tab.getGameWidth()); ps.setInt(12,tab.getGameHeight());
            ps.setString(13,tab.getGpuChoice()); ps.setInt(14,tab.getDbId());
            ps.executeUpdate();
            saveTabSettings(tab);
        } catch (SQLException e) { err("updateTab", e); }
    }

    public void deleteTab(int tabId) {
        if (!isOpen() || tabId <= 0) return;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM tabs WHERE id=?")) {
            ps.setInt(1, tabId); ps.executeUpdate();
        } catch (SQLException e) { err("deleteTab", e); }
    }

    public void saveTabSettings(TabData tab) {
        if (!isOpen() || tab.getDbId() <= 0) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO tab_settings(tab_id,key,value) VALUES(?,?,?)")) {
            ps.setInt(1,tab.getDbId()); ps.setString(2,"consoleMode"); ps.setString(3,tab.getConsoleMode()); ps.executeUpdate();
            ps.setInt(1,tab.getDbId()); ps.setString(2,"consoleVisible"); ps.setString(3,tab.isConsoleVisible()?"1":"0"); ps.executeUpdate();
        } catch (SQLException e) { err("saveTabSettings", e); }
    }

    public void reorderTabs(List<Integer> ids) {
        if (!isOpen()) return;
        try (PreparedStatement ps = connection.prepareStatement("UPDATE tabs SET sort_order=? WHERE id=?")) {
            for (int i = 0; i < ids.size(); i++) { ps.setInt(1,i); ps.setInt(2,ids.get(i)); ps.addBatch(); }
            ps.executeBatch();
        } catch (SQLException e) { err("reorderTabs", e); }
    }

    // ── Launcher State ────────────────────────────────────────

    public void setState(String key, String value) {
        if (!isOpen()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO launcher_state(key,value) VALUES(?,?)")) {
            ps.setString(1,key); ps.setString(2,value); ps.executeUpdate();
        } catch (SQLException e) { err("setState", e); }
    }

    public String getState(String key, String def) {
        if (!isOpen()) return def;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM launcher_state WHERE key=?")) {
            ps.setString(1,key);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString("value"); }
        } catch (SQLException e) { err("getState", e); }
        return def;
    }

    public void migrateFromJsonFiles() {
        File tabsDir = com.powerlaunch.launcher.LauncherHomeProvider.getLauncherHome()
                .resolve("tabs").toFile();
        if (tabsDir.exists() && tabsDir.isDirectory()) {
            File[] files = tabsDir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) for (File f : files) f.delete();
            tabsDir.delete();
            System.out.println("[TabDatabase] Old JSON tabs cleaned up.");
        }
    }

    private static void err(String ctx, SQLException e) {
        System.err.println("[TabDatabase] " + ctx + ": " + e.getMessage());
    }
}
