package com.powerlaunch.tabs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages launcher tabs (profiles).
 * Each tab is stored as a row in the SQLite database via TabDatabase.
 *
 * Features:
 * - Create, rename, delete, duplicate, reorder tabs
 * - Each tab is fully independent (version, game dir, RAM, Java args, console settings, etc.)
 * - Active tab persistence across restarts
 * - UI state per tab (console mode, visibility, etc.)
 */
public class TabManager {

    private static TabManager instance;

    private final TabDatabase db;
    private final List<TabData> tabs;
    private int activeTabIndex;

    private TabManager() {
        this.tabs = new ArrayList<>();
        this.activeTabIndex = 0;

        // Initialize database
        db = TabDatabase.getInstance();
        db.open();

        // Migrate old JSON files if present (first run after migration)
        migrateIfNeeded();

        // Load tabs from database
        loadAll();
    }

    public static synchronized TabManager getInstance() {
        if (instance == null) {
            instance = new TabManager();
        }
        return instance;
    }

    // ==================== Public API ====================

    public List<TabData> getTabs() {
        return new ArrayList<>(tabs);
    }

    public int getTabCount() {
        return tabs.size();
    }

    public TabData getActiveTab() {
        if (tabs.isEmpty()) return null;
        if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
            activeTabIndex = 0;
        }
        return tabs.get(activeTabIndex);
    }

    public int getActiveTabIndex() {
        return activeTabIndex;
    }

    public TabData getTab(int index) {
        if (index < 0 || index >= tabs.size()) return null;
        return tabs.get(index);
    }

    public void setActiveTab(int index) {
        if (index >= 0 && index < tabs.size()) {
            activeTabIndex = index;
            saveState();
        }
    }

    /**
     * Creates a new tab with the given name and default settings.
     */
    public TabData createTab(String name) {
        TabData tab = new TabData(name);
        tab = db.insertTab(tab);
        tabs.add(tab);
        activeTabIndex = tabs.size() - 1;
        saveState();
        return tab;
    }

    /**
     * Creates a new tab copied from an existing tab.
     */
    public TabData duplicateTab(int index) {
        if (index < 0 || index >= tabs.size()) return null;
        TabData original = tabs.get(index);
        TabData copy = original.copy();
        copy.setName(original.getName() + " (копия)");
        copy = db.insertTab(copy);
        tabs.add(copy);
        activeTabIndex = tabs.size() - 1;
        saveState();
        return copy;
    }

    /**
     * Renames a tab.
     */
    public void renameTab(int index, String newName) {
        if (index < 0 || index >= tabs.size()) return;
        if (newName == null || newName.trim().isEmpty()) return;
        tabs.get(index).setName(newName.trim());
        db.updateTab(tabs.get(index));
    }

    /**
     * Deletes a tab by index. If it's the last tab, creates a default one.
     */
    public void deleteTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        TabData removed = tabs.remove(index);
        db.deleteTab(removed.getDbId());

        // Always keep at least 1 tab
        if (tabs.isEmpty()) {
            TabData defaultTab = new TabData("Основная");
            defaultTab = db.insertTab(defaultTab);
            tabs.add(defaultTab);
        }

        // Adjust active index
        if (activeTabIndex >= tabs.size()) {
            activeTabIndex = tabs.size() - 1;
        }
        if (index < activeTabIndex) {
            activeTabIndex--;
        }

        saveState();
    }

    /**
     * Moves a tab to a new position (drag reorder).
     */
    public void moveTab(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= tabs.size()) return;
        if (toIndex < 0 || toIndex >= tabs.size()) return;
        if (fromIndex == toIndex) return;

        TabData tab = tabs.remove(fromIndex);
        tabs.add(toIndex, tab);

        // Adjust active index
        if (activeTabIndex == fromIndex) {
            activeTabIndex = toIndex;
        } else if (fromIndex < activeTabIndex && toIndex >= activeTabIndex) {
            activeTabIndex--;
        } else if (fromIndex > activeTabIndex && toIndex <= activeTabIndex) {
            activeTabIndex++;
        }

        // Persist new order
        saveTabOrder();
        saveState();
    }

    /**
     * Imports a tab from a .json file.
     */
    public TabData importTab(File jsonFile) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            try (java.io.FileReader reader = new java.io.FileReader(jsonFile)) {
                TabData tab = gson.fromJson(reader, TabData.class);
                if (tab == null) return null;
                if (tab.getName() == null || tab.getName().trim().isEmpty()) {
                    String fileName = jsonFile.getName();
                    if (fileName.endsWith(".json")) {
                        fileName = fileName.substring(0, fileName.length() - 5);
                    }
                    tab.setName(fileName);
                }
                tab.setDbId(-1); // Force new ID
                tab = db.insertTab(tab);
                tabs.add(tab);
                activeTabIndex = tabs.size() - 1;
                saveState();
                return tab;
            }
        } catch (Exception e) {
            System.err.println("[TabManager] Failed to import tab from " + jsonFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Exports a tab to a .json file.
     */
    public boolean exportTab(int index, File destination) {
        if (index < 0 || index >= tabs.size()) return false;
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            try (java.io.FileWriter writer = new java.io.FileWriter(destination)) {
                gson.toJson(tabs.get(index), writer);
            }
            return true;
        } catch (Exception e) {
            System.err.println("[TabManager] Failed to export tab: " + e.getMessage());
            return false;
        }
    }

    // ==================== Settings Sync ====================

    /**
     * Applies the active tab's settings to the global SettingsManager.
     */
    public void applyActiveTabSettings() {
        TabData active = getActiveTab();
        if (active == null) return;

        com.powerlaunch.settings.SettingsManager settings =
                com.powerlaunch.settings.SettingsManager.getInstance();

        if (active.getVersion() != null && !active.getVersion().isEmpty()) {
            settings.set("selectedVersion", active.getVersion());
        }
        settings.set("gameDirectory", active.getGameDirectory());
        settings.set("javaPath", active.getJavaPath());
        settings.set("javaArgs", active.getJavaArgs());
        settings.set("ram", active.getRam());
        settings.set("serverIp", active.getServerIp());
        settings.set("autoConnect", active.isAutoConnect());
        settings.set("useCustomResolution", active.isUseCustomResolution());
        settings.set("gameWidth", active.getGameWidth());
        settings.set("gameHeight", active.getGameHeight());
        settings.set("gpuChoice", active.getGpuChoice());
    }

    /**
     * Updates the active tab from the current global SettingsManager state.
     */
    public void updateActiveTabFromSettings() {
        TabData active = getActiveTab();
        if (active == null) return;

        com.powerlaunch.settings.SettingsManager settings =
                com.powerlaunch.settings.SettingsManager.getInstance();

        active.setVersion(settings.getString("selectedVersion", ""));
        active.setGameDirectory(settings.getString("gameDirectory", ""));
        active.setJavaPath(settings.getString("javaPath", ""));
        active.setJavaArgs(settings.getString("javaArgs", ""));
        active.setRam(settings.getInt("ram", 4096));
        active.setServerIp(settings.getString("serverIp", ""));
        active.setAutoConnect(settings.getBoolean("autoConnect", false));
        active.setUseCustomResolution(settings.getBoolean("useCustomResolution", false));
        active.setGameWidth(settings.getInt("gameWidth", 854));
        active.setGameHeight(settings.getInt("gameHeight", 480));
        active.setGpuChoice(settings.getString("gpuChoice", "auto"));

        db.updateTab(active);
    }

    /**
     * Saves console UI state to the active tab in DB.
     */
    public void saveConsoleState(String mode, boolean visible) {
        TabData active = getActiveTab();
        if (active == null) return;
        active.setConsoleMode(mode);
        active.setConsoleVisible(visible);
        db.saveTabSettings(active);
    }

    /**
     * Loads console state from the active tab and returns it as an array [mode, visible].
     */
    public String[] loadConsoleState() {
        TabData active = getActiveTab();
        if (active == null) return new String[]{"off", "false"};
        return new String[]{active.getConsoleMode(), String.valueOf(active.isConsoleVisible())};
    }

    /**
     * Saves all tabs to DB (batch update).
     */
    public void saveAll() {
        for (TabData tab : tabs) {
            db.updateTab(tab);
        }
        saveState();
    }

    // ==================== Persistence ====================

    private void loadAll() {
        tabs.clear();
        activeTabIndex = 0;

        List<TabData> loaded = db.loadAllTabs();
        if (loaded != null && !loaded.isEmpty()) {
            tabs.addAll(loaded);
        }

        // If no tabs in DB, create default
        if (tabs.isEmpty()) {
            TabData defaultTab = new TabData("Основная");
            defaultTab = db.insertTab(defaultTab);
            tabs.add(defaultTab);
        }

        // Restore active tab index
        String savedIndex = db.getState("activeTabIndex", "0");
        try {
            activeTabIndex = Integer.parseInt(savedIndex);
        } catch (NumberFormatException e) {
            activeTabIndex = 0;
        }

        // Validate
        if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
            activeTabIndex = 0;
        }
    }

    private void saveState() {
        db.setState("activeTabIndex", String.valueOf(activeTabIndex));
    }

    private void saveTabOrder() {
        List<Integer> orderedIds = tabs.stream()
                .map(TabData::getDbId)
                .collect(Collectors.toList());
        db.reorderTabs(orderedIds);
    }

    /**
     * Checks if old JSON tab files exist and migrates them to SQLite.
     * Runs only once on first startup with SQLite.
     */
    private void migrateIfNeeded() {
        // If we already have tabs in the database, no migration needed
        List<TabData> existing = db.loadAllTabs();
        if (existing != null && !existing.isEmpty()) return;

        // Check for old JSON tabs
        String os = System.getProperty("os.name").toLowerCase();
        java.nio.file.Path configDir;
        if (os.contains("win")) {
            configDir = java.nio.file.Paths.get(System.getenv("APPDATA"), "PowerLaunch");
        } else {
            configDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".config", "PowerLaunch");
        }

        File tabsDir = configDir.resolve("tabs").toFile();
        if (!tabsDir.exists() || !tabsDir.isDirectory()) return;

        File[] jsonFiles = tabsDir.listFiles((dir, name) ->
                name.startsWith("tab_") && name.endsWith(".json"));
        if (jsonFiles == null || jsonFiles.length == 0) return;

        System.out.println("[TabManager] Migrating " + jsonFiles.length + " tabs from JSON to SQLite...");

        com.google.gson.Gson gson = new com.google.gson.Gson();
        int migrated = 0;

        for (File jsonFile : jsonFiles) {
            try (java.io.FileReader reader = new java.io.FileReader(jsonFile)) {
                TabData tab = gson.fromJson(reader, TabData.class);
                if (tab != null) {
                    // Clear ID so it gets a new one from SQLite
                    tab.setDbId(-1);
                    db.insertTab(tab);
                    migrated++;
                }
            } catch (Exception e) {
                System.err.println("[TabManager] Failed to migrate " + jsonFile.getName() + ": " + e.getMessage());
            }
        }

        System.out.println("[TabManager] Migrated " + migrated + " tabs to SQLite.");

        // Clean up old JSON files
        db.migrateFromJsonFiles();
    }
}
