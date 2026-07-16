package com.powerlaunch.skins;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public class SkinManager {
    private static SkinManager instance;
    private Path skinsPath;
    private BufferedImage currentSkin;
    private String currentSkinName;

    private SkinManager() {
        initSkinDirectory();
    }

    public static synchronized SkinManager getInstance() {
        if (instance == null) {
            instance = new SkinManager();
        }
        return instance;
    }

    private void initSkinDirectory() {
        skinsPath = com.powerlaunch.launcher.LauncherHomeProvider.getSkinsDir();
        try {
            Files.createDirectories(skinsPath);
        } catch (IOException e) {
            System.err.println("Failed to create skins directory: " + e.getMessage());
        }
    }

    public Path getSkinsPath() {
        return skinsPath;
    }

    /**
     * Выполняет HTTP GET запрос и возвращает тело ответа в виде строки.
     */
    private String httpGetString(String urlStr, int timeoutSec) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " for " + urlStr);
            }

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Выполняет HTTP GET запрос и возвращает тело ответа в виде байтового массива.
     */
    private byte[] httpGetBytes(String urlStr, int timeoutSec) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " for " + urlStr);
            }

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) >= 0) {
                    baos.write(buf, 0, read);
                }
                return baos.toByteArray();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public CompletableFuture<Boolean> downloadSkin(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Step 1: Get UUID from Mojang API
                String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + username;
                String uuidResponse = httpGetString(uuidUrl, 5);
                String uuid = uuidResponse.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

                // Step 2: Download skin from crafatar
                String skinUrl = "https://crafatar.com/skins/" + uuid;
                byte[] skinData = httpGetBytes(skinUrl, 10);

                // Step 3: Save skin file
                Path skinFile = skinsPath.resolve(username + ".png");
                Files.write(skinFile, skinData);
                currentSkin = ImageIO.read(new ByteArrayInputStream(skinData));
                currentSkinName = username;
                return true;

            } catch (java.net.UnknownHostException e) {
                System.err.println("[PowerLaunch][Skin] DNS resolution failed for " + username + ": "
                    + e.getMessage() + " — не удаётся найти сервер");
            } catch (java.net.ConnectException e) {
                System.err.println("[PowerLaunch][Skin] Connection refused for " + username + ": "
                    + e.getMessage() + " — сервер недоступен");
            } catch (java.net.SocketTimeoutException e) {
                System.err.println("[PowerLaunch][Skin] Timeout for " + username + ": "
                    + e.getMessage() + " — проверьте интернет-соединение");
            } catch (javax.net.ssl.SSLException e) {
                System.err.println("[PowerLaunch][Skin] SSL/TLS error for " + username + ": "
                    + e.getMessage() + " — проблемы с сертификатами");
            } catch (Exception e) {
                System.err.println("[PowerLaunch][Skin] Failed to download skin for " + username + ": ["
                    + e.getClass().getSimpleName() + "] " + e.getMessage());
            }
            return false;
        });
    }

    public String loadSkinFromFile(Path filePath) {
        try {
            BufferedImage skin = ImageIO.read(filePath.toFile());
            if (skin != null && (skin.getWidth() == 64 && (skin.getHeight() == 32 || skin.getHeight() == 64))) {
                currentSkin = skin;
                currentSkinName = filePath.getFileName().toString().replace(".png", "");
                // Copy to skins directory
                Files.copy(filePath, skinsPath.resolve(filePath.getFileName()));
                return currentSkinName;
            }
        } catch (IOException e) {
            System.err.println("Failed to load skin: " + e.getMessage());
        }
        return null;
    }

    public String encodeSkinToBase64() {
        if (currentSkin == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(currentSkin, "PNG", baos);
            byte[] bytes = baos.toByteArray();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            return "";
        }
    }

    public BufferedImage getCurrentSkin() {
        return currentSkin;
    }

    public String getCurrentSkinName() {
        return currentSkinName;
    }

    public boolean hasSkin() {
        return currentSkin != null;
    }

    public void clearSkin() {
        currentSkin = null;
        currentSkinName = null;
    }
}
