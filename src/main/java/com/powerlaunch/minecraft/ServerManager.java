package com.powerlaunch.minecraft;

import com.powerlaunch.storage.AppDatabase;
import com.powerlaunch.storage.AppDatabase.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Server list — stored in SQLite via {@link AppDatabase}.
 * On first load imports from Minecraft's servers.dat (if DB is empty).
 * Every change is written back to both DB and servers.dat.
 */
public class ServerManager {
    private static ServerManager instance;
    private final AppDatabase db;
    private final ServersDatManager serversDatManager;
    private List<ServerEntry> servers;

    /** Lightweight mutable wrapper used by UI — maps to/from immutable DB record. */
    public static class ServerEntry {
        private String name, ip, port;
        public ServerEntry() {}
        public ServerEntry(String n, String i, String p) { name=n; ip=i; port=p; }
        public String getName()  { return name; }
        public void   setName(String v)  { name=v; }
        public String getIp()    { return ip; }
        public void   setIp(String v)    { ip=v; }
        public String getPort()  { return port; }
        public void   setPort(String v)  { port=v; }
        public String getDisplayIp() {
            return ip + (port != null && !port.isEmpty() && !"25565".equals(port) ? ":" + port : "");
        }
    }

    private ServerManager() {
        db = AppDatabase.getInstance();
        serversDatManager = ServersDatManager.getInstance();
        servers = new ArrayList<>();
        load();
    }

    public static synchronized ServerManager getInstance() {
        if (instance == null) instance = new ServerManager();
        return instance;
    }

    private void load() {
        // Import from servers.dat on first run (DB empty)
        List<Server> dbServers = db.getAllServers();
        if (dbServers.isEmpty()) {
            List<ServerEntry> fromDat = serversDatManager.importFromServersDat();
            if (!fromDat.isEmpty()) {
                servers = fromDat;
                syncToDb();
                return;
            }
        }
        // Normal load from DB
        servers = dbServers.stream()
                .map(s -> new ServerEntry(s.name(), s.ip(), s.port()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void syncToDb() {
        db.replaceAllServers(servers.stream()
                .map(s -> new Server(s.getName(), s.getIp(), s.getPort()))
                .collect(Collectors.toList()));
    }

    private void save() {
        syncToDb();
        serversDatManager.exportToServersDat(servers);
    }

    public void addServer(String name, String ip, String port) {
        servers.add(new ServerEntry(name, ip, port));
        save();
    }

    public void removeServer(int index) {
        if (index >= 0 && index < servers.size()) { servers.remove(index); save(); }
    }

    public void updateServer(int index, String name, String ip, String port) {
        if (index >= 0 && index < servers.size()) {
            ServerEntry e = servers.get(index);
            e.setName(name); e.setIp(ip); e.setPort(port);
            save();
        }
    }

    public List<ServerEntry> getServers() { return new ArrayList<>(servers); }
    public boolean isEmpty() { return servers.isEmpty(); }
    public int    size()     { return servers.size(); }
}
