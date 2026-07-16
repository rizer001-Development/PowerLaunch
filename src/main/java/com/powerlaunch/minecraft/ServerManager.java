package com.powerlaunch.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ServerManager {
    private static ServerManager instance;
    private final Path serversPath;
    private final Gson gson;
    private final ServersDatManager serversDatManager;
    private List<ServerEntry> servers;

    public static class ServerEntry {
        private String name;
        private String ip;
        private String port;

        public ServerEntry() {}

        public ServerEntry(String name, String ip, String port) {
            this.name = name;
            this.ip = ip;
            this.port = port;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }

        public String getDisplayIp() {
            return ip + (port != null && !port.isEmpty() && !port.equals("25565") ? ":" + port : "");
        }
    }

    private ServerManager() {
        gson = new GsonBuilder().setPrettyPrinting().create();
        serversPath = com.powerlaunch.launcher.LauncherHomeProvider.getServersFile();
        serversDatManager = ServersDatManager.getInstance();
        servers = new ArrayList<>();
        load();
    }

    public static synchronized ServerManager getInstance() {
        if (instance == null) {
            instance = new ServerManager();
        }
        return instance;
    }

    public void load() {
        // First try to import from servers.dat (мультиплеер Minecraft)
        List<ServerEntry> fromServersDat = serversDatManager.importFromServersDat();
        if (!fromServersDat.isEmpty()) {
            servers = fromServersDat;
            // Also save to JSON for backup
            saveJson();
            return;
        }

        // Fallback: load from JSON backup
        try {
            if (Files.exists(serversPath)) {
                String content = Files.readString(serversPath);
                Type type = new TypeToken<List<ServerEntry>>() {}.getType();
                List<ServerEntry> loaded = gson.fromJson(content, type);
                if (loaded != null && !loaded.isEmpty()) {
                    servers = loaded;
                    // Restore to servers.dat
                    serversDatManager.exportToServersDat(servers);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load servers: " + e.getMessage());
        }
    }

    private void saveJson() {
        try {
            Files.createDirectories(serversPath.getParent());
            Files.writeString(serversPath, gson.toJson(servers));
        } catch (IOException e) {
            System.err.println("Failed to save servers JSON: " + e.getMessage());
        }
    }

    public void save() {
        // Save to both JSON backup AND servers.dat (Minecraft мультиплеер)
        saveJson();
        serversDatManager.exportToServersDat(servers);
    }

    public void addServer(String name, String ip, String port) {
        servers.add(new ServerEntry(name, ip, port));
        save();
    }

    public void removeServer(int index) {
        if (index >= 0 && index < servers.size()) {
            servers.remove(index);
            save();
        }
    }

    public void updateServer(int index, String name, String ip, String port) {
        if (index >= 0 && index < servers.size()) {
            ServerEntry entry = servers.get(index);
            entry.setName(name);
            entry.setIp(ip);
            entry.setPort(port);
            save();
        }
    }

    public List<ServerEntry> getServers() {
        return new ArrayList<>(servers);
    }

    public boolean isEmpty() {
        return servers.isEmpty();
    }

    public int size() {
        return servers.size();
    }
}
