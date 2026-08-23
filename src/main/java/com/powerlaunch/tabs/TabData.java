package com.powerlaunch.tabs;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a single tab/profile in the PowerLaunch launcher.
 * Each tab is fully independent: different version, game directory, RAM, Java args, etc.
 * Stored in SQLite database via TabDatabase.
 */
public class TabData {

    // Database fields
    private int dbId;
    private int sortOrder;

    // User-facing fields
    private String name;
    private String version;
    private String gameDirectory;
    private String javaPath;
    private String javaArgs;
    private int ram;
    private String serverIp;
    private boolean autoConnect;
    private boolean useCustomResolution;
    private int gameWidth;
    private int gameHeight;
    private String gpuChoice;

    // Tab-specific UI state (saved in tab_settings table)
    private String consoleMode = "off"; // off, errors, all
    private boolean consoleVisible = false;

    // Default constructor
    public TabData() {
        this.dbId = -1;
        this.sortOrder = 0;
        this.name = "New Tab";
        this.version = "";
        this.gameDirectory = "";
        this.javaPath = "";
        this.javaArgs = "";
        this.ram = 4096;
        this.serverIp = "";
        this.autoConnect = false;
        this.useCustomResolution = false;
        this.gameWidth = 854;
        this.gameHeight = 480;
        this.gpuChoice = "auto";
    }

    public TabData(String name) {
        this();
        this.name = name;
    }

    /**
     * Creates a deep copy of this tab.
     */
    public TabData copy() {
        TabData copy = new TabData(this.name);
        copy.dbId = -1; // Copy gets a new ID when inserted
        copy.sortOrder = this.sortOrder;
        copy.version = this.version;
        copy.gameDirectory = this.gameDirectory;
        copy.javaPath = this.javaPath;
        copy.javaArgs = this.javaArgs;
        copy.ram = this.ram;
        copy.serverIp = this.serverIp;
        copy.autoConnect = this.autoConnect;
        copy.useCustomResolution = this.useCustomResolution;
        copy.gameWidth = this.gameWidth;
        copy.gameHeight = this.gameHeight;
        copy.gpuChoice = this.gpuChoice;
        copy.consoleMode = this.consoleMode;
        copy.consoleVisible = this.consoleVisible;
        return copy;
    }

    /**
     * Creates a new tab with default settings for a specific Minecraft version.
     */
    public static TabData createForVersion(String version, String name) {
        TabData tab = new TabData(name);
        tab.version = version;
        return tab;
    }

    /**
     * Converts this tab's settings into a flat key-value map suitable
     * for applying to SettingsManager.
     */
    public Map<String, Object> toSettingsMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("selectedVersion", version);
        map.put("gameDirectory", gameDirectory);
        map.put("javaPath", javaPath);
        map.put("javaArgs", javaArgs);
        map.put("ram", ram);
        map.put("serverIp", serverIp);
        map.put("autoConnect", autoConnect);
        map.put("useCustomResolution", useCustomResolution);
        map.put("gameWidth", gameWidth);
        map.put("gameHeight", gameHeight);
        map.put("gpuChoice", gpuChoice);
        // Also store as tab-specific override key so we can restore
        map.put("tabGameDir", gameDirectory);
        return map;
    }

    /**
     * Populates this tab's fields from a settings map.
     */
    public void fromSettingsMap(Map<String, Object> map) {
        if (map == null) return;
        if (map.containsKey("selectedVersion") && map.get("selectedVersion") instanceof String)
            this.version = (String) map.get("selectedVersion");
        if (map.containsKey("gameDirectory") && map.get("gameDirectory") instanceof String)
            this.gameDirectory = (String) map.get("gameDirectory");
        if (map.containsKey("javaPath") && map.get("javaPath") instanceof String)
            this.javaPath = (String) map.get("javaPath");
        if (map.containsKey("javaArgs") && map.get("javaArgs") instanceof String)
            this.javaArgs = (String) map.get("javaArgs");
        if (map.containsKey("ram") && map.get("ram") instanceof Number)
            this.ram = ((Number) map.get("ram")).intValue();
        if (map.containsKey("serverIp") && map.get("serverIp") instanceof String)
            this.serverIp = (String) map.get("serverIp");
        if (map.containsKey("autoConnect") && map.get("autoConnect") instanceof Boolean)
            this.autoConnect = (Boolean) map.get("autoConnect");
        if (map.containsKey("useCustomResolution") && map.get("useCustomResolution") instanceof Boolean)
            this.useCustomResolution = (Boolean) map.get("useCustomResolution");
        if (map.containsKey("gameWidth") && map.get("gameWidth") instanceof Number)
            this.gameWidth = ((Number) map.get("gameWidth")).intValue();
        if (map.containsKey("gameHeight") && map.get("gameHeight") instanceof Number)
            this.gameHeight = ((Number) map.get("gameHeight")).intValue();
        if (map.containsKey("gpuChoice") && map.get("gpuChoice") instanceof String)
            this.gpuChoice = (String) map.get("gpuChoice");
    }

    // ==================== Getters / Setters ====================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getGameDirectory() { return gameDirectory; }
    public void setGameDirectory(String gameDirectory) { this.gameDirectory = gameDirectory; }

    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String javaPath) { this.javaPath = javaPath; }

    public String getJavaArgs() { return javaArgs; }
    public void setJavaArgs(String javaArgs) { this.javaArgs = javaArgs; }

    public int getRam() { return ram; }
    public void setRam(int ram) { this.ram = ram; }

    public String getServerIp() { return serverIp; }
    public void setServerIp(String serverIp) { this.serverIp = serverIp; }

    public boolean isAutoConnect() { return autoConnect; }
    public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }

    public boolean isUseCustomResolution() { return useCustomResolution; }
    public void setUseCustomResolution(boolean useCustomResolution) { this.useCustomResolution = useCustomResolution; }

    public int getGameWidth() { return gameWidth; }
    public void setGameWidth(int gameWidth) { this.gameWidth = gameWidth; }

    public int getGameHeight() { return gameHeight; }
    public void setGameHeight(int gameHeight) { this.gameHeight = gameHeight; }

    public String getGpuChoice() { return gpuChoice; }
    public void setGpuChoice(String gpuChoice) { this.gpuChoice = gpuChoice; }

    // ==================== Database Fields ====================

    public int getDbId() { return dbId; }
    public void setDbId(int dbId) { this.dbId = dbId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    // ==================== Tab-specific UI State ====================

    public String getConsoleMode() { return consoleMode; }
    public void setConsoleMode(String consoleMode) {
        this.consoleMode = (consoleMode != null) ? consoleMode : "off";
    }

    public boolean isConsoleVisible() { return consoleVisible; }
    public void setConsoleVisible(boolean consoleVisible) { this.consoleVisible = consoleVisible; }

    // ==================== Settings Map (for SettingsManager sync) ====================

    @Override
    public String toString() {
        return "TabData{id=" + dbId + ", name='" + name + "', version='" + version + "'}";
    }
}
