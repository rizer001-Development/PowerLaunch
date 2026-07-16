package com.powerlaunch.updater;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Updater {
    private static Updater instance;
    private static final String VERSIONS_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private final Gson gson;

    public static class VersionInfo {
        public final String id;
        public final String type;
        public final String url;
        public final String releaseTime;

        public VersionInfo(String id, String type, String url, String releaseTime) {
            this.id = id;
            this.type = type;
            this.url = url;
            this.releaseTime = releaseTime;
        }
    }

    private Updater() {
        this.gson = new Gson();
    }

    public static synchronized Updater getInstance() {
        if (instance == null) {
            instance = new Updater();
        }
        return instance;
    }

    public CompletableFuture<List<VersionInfo>> fetchVersions() {
        return CompletableFuture.supplyAsync(() -> {
            List<VersionInfo> versions = new ArrayList<>();
            HttpURLConnection conn = null;
            try {
                URL url = new URL(VERSIONS_MANIFEST_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);

                int status = conn.getResponseCode();
                if (status == 200) {
                    String body;
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                        body = sb.toString();
                    }

                    JsonObject manifest = gson.fromJson(body, JsonObject.class);
                    JsonArray versionsArray = manifest.getAsJsonArray("versions");

                    if (versionsArray != null) {
                        for (int i = 0; i < versionsArray.size(); i++) {
                            JsonObject ver = versionsArray.get(i).getAsJsonObject();
                            String id = getJsonString(ver, "id");
                            String type = getJsonString(ver, "type");
                            String url2 = getJsonString(ver, "url");
                            String time = getJsonString(ver, "releaseTime");
                            if (id != null && type != null) {
                                versions.add(new VersionInfo(id, type, url2, time));
                            }
                        }
                    }
                } else {
                    System.err.println("[PowerLaunch][Updater] HTTP " + status + " for " + VERSIONS_MANIFEST_URL);
                }
            } catch (java.net.UnknownHostException e) {
                System.err.println("[PowerLaunch][Updater] DNS resolution failed (" + VERSIONS_MANIFEST_URL + "): "
                    + e.getMessage() + " — не удаётся найти сервер (проверьте DNS/hosts)");
            } catch (java.net.ConnectException e) {
                System.err.println("[PowerLaunch][Updater] Connection refused (" + VERSIONS_MANIFEST_URL + "): "
                    + e.getMessage() + " — сервер недоступен или блокируется файрволом");
            } catch (java.net.SocketTimeoutException e) {
                System.err.println("[PowerLaunch][Updater] Timeout (" + VERSIONS_MANIFEST_URL + "): "
                    + e.getMessage() + " — проверьте интернет-соединение");
            } catch (javax.net.ssl.SSLException e) {
                System.err.println("[PowerLaunch][Updater] SSL/TLS error (" + VERSIONS_MANIFEST_URL + "): "
                    + e.getMessage() + " — проблемы с сертификатами или TLS версией");
            } catch (Exception e) {
                System.err.println("[PowerLaunch][Updater] Failed to fetch versions (" + VERSIONS_MANIFEST_URL + "): ["
                    + e.getClass().getSimpleName() + "] " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            return versions;
        });
    }

    private String getJsonString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    public static String calculateHash(InputStream inputStream) throws IOException {
        try (InputStream is = inputStream) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 not available", e);
            }
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        }
    }
}
