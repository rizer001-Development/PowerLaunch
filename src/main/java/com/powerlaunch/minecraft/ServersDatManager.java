package com.powerlaunch.minecraft;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages synchronization between the launcher's server list and Minecraft's servers.dat file.
 * servers.dat is an uncompressed NBT file containing a list of multiplayer servers.
 * Uses a minimal built-in NBT reader/writer to avoid external dependencies.
 */
public class ServersDatManager {
    private static ServersDatManager instance;

    private ServersDatManager() {}

    public static synchronized ServersDatManager getInstance() {
        if (instance == null) {
            instance = new ServersDatManager();
        }
        return instance;
    }

    private Path getServersDatPath() {
        return getGameDir().resolve("servers.dat");
    }

    private Path getGameDir() {
        String os = System.getProperty("os.name").toLowerCase();
        String customDir = com.powerlaunch.settings.SettingsManager.getInstance().getString("gameDirectory", "");
        if (!customDir.isEmpty()) {
            return Paths.get(customDir);
        }
        if (os.contains("win")) {
            return Paths.get(System.getenv("APPDATA"), ".powerlaunch");
        } else if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", ".powerlaunch");
        }
        return Paths.get(System.getProperty("user.home"), ".powerlaunch");
    }

    // NBT type constants
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    /**
     * Read servers from Minecraft's servers.dat file.
     */
    public List<ServerManager.ServerEntry> readServersDat() {
        List<ServerManager.ServerEntry> result = new ArrayList<>();
        File file = getServersDatPath().toFile();

        if (!file.exists()) {
            return result;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            // Read root compound tag
            byte rootType = in.readByte();
            if (rootType != TAG_COMPOUND) {
                return result;
            }
            // Read root name (empty string)
            readString(in);

            // Read all tags in the root compound
            while (true) {
                byte tagType = in.readByte();
                if (tagType == TAG_END) break;

                String tagName = readString(in);

                if (tagType == TAG_LIST && tagName.equals("servers")) {
                    byte listType = in.readByte();
                    int listLength = in.readInt();

                    for (int i = 0; i < listLength; i++) {
                        readCompoundTag(in, result);
                    }
                    break; // We found our list, done parsing
                } else {
                    skipTag(in, tagType);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to read servers.dat: " + e.getMessage());
        }

        return result;
    }

    private void readCompoundTag(DataInputStream in, List<ServerManager.ServerEntry> result) throws IOException {
        String name = "";
        String ip = "";

        while (true) {
            byte tagType = in.readByte();
            if (tagType == TAG_END) break;

            String tagName = readString(in);

            if (tagType == TAG_STRING && tagName.equals("name")) {
                name = readString(in);
            } else if (tagType == TAG_STRING && tagName.equals("ip")) {
                ip = readString(in);
            } else {
                skipTag(in, tagType);
            }
        }

        if (!ip.isEmpty()) {
            String address = ip;
            String port = "25565";
            if (address.contains(":")) {
                String[] parts = address.split(":", 2);
                address = parts[0];
                port = parts[1];
            }
            result.add(new ServerManager.ServerEntry(name, address, port));
        }
    }

    /**
     * Write servers to Minecraft's servers.dat file.
     */
    public void writeServersDat(List<ServerManager.ServerEntry> servers) {
        try {
            getServersDatPath().getParent().toFile().mkdirs();
            File file = getServersDatPath().toFile();

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(baos))) {

                // Root compound tag
                out.writeByte(TAG_COMPOUND);
                writeString(out, ""); // empty name for root

                // Servers list
                out.writeByte(TAG_LIST);
                writeString(out, "servers");
                out.writeByte(TAG_COMPOUND); // list type
                out.writeInt(servers.size());

                for (ServerManager.ServerEntry entry : servers) {
                    out.writeByte(TAG_STRING);
                    writeString(out, "name");
                    writeString(out, entry.getName() != null ? entry.getName() : "");

                    out.writeByte(TAG_STRING);
                    writeString(out, "ip");
                    writeString(out, entry.getDisplayIp());

                    out.writeByte(TAG_END); // end of server compound
                }

                out.writeByte(TAG_END); // end of root compound
                out.flush();

                // Write to file
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(baos.toByteArray());
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to write servers.dat: " + e.getMessage());
        }
    }

    private String readString(DataInputStream in) throws IOException {
        short length = in.readShort();
        if (length < 0) return "";
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeString(DataOutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private void skipTag(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case TAG_BYTE -> in.readByte();
            case TAG_SHORT -> in.readShort();
            case TAG_INT -> in.readInt();
            case TAG_LONG -> in.readLong();
            case TAG_FLOAT -> in.readFloat();
            case TAG_DOUBLE -> in.readDouble();
            case TAG_BYTE_ARRAY -> {
                int len = in.readInt();
                in.skipBytes(len);
            }
            case TAG_STRING -> readString(in);
            case TAG_LIST -> {
                byte listType = in.readByte();
                int listLen = in.readInt();
                for (int i = 0; i < listLen; i++) {
                    if (listType == TAG_COMPOUND) {
                        while (true) {
                            byte t = in.readByte();
                            if (t == TAG_END) break;
                            String name = readString(in);
                            skipTag(in, t);
                        }
                    } else {
                        skipTag(in, listType);
                    }
                }
            }
            case TAG_COMPOUND -> {
                while (true) {
                    byte t = in.readByte();
                    if (t == TAG_END) break;
                    String name = readString(in);
                    skipTag(in, t);
                }
            }
            case TAG_INT_ARRAY -> {
                int len = in.readInt();
                for (int i = 0; i < len; i++) in.readInt();
            }
            case TAG_LONG_ARRAY -> {
                int len = in.readInt();
                for (int i = 0; i < len; i++) in.readLong();
            }
        }
    }

    /**
     * Import servers from servers.dat into the launcher's server list.
     */
    public List<ServerManager.ServerEntry> importFromServersDat() {
        return readServersDat();
    }

    /**
     * Export launcher servers to servers.dat.
     */
    public void exportToServersDat(List<ServerManager.ServerEntry> servers) {
        writeServersDat(servers);
    }
}
