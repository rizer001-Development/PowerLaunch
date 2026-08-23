package com.powerlaunch.news;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.annotations.SerializedName;

import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NewsManager {
    private static NewsManager instance;
    // TODO: replace URL with your own news repository
    private static final String NEWS_URL = "https://raw.githubusercontent.com/your-repo/launcher-news/main/news.json";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private final Gson gson;
    private List<NewsItem> cachedNews;
    private boolean loaded;

    public static class NewsItem {
        @SerializedName("title")
        private String title;
        @SerializedName("content")
        private String content;
        @SerializedName("date")
        private String date;
        @SerializedName("imageUrl")
        private String imageUrl;
        @SerializedName("type")
        private String type;

        public NewsItem() {}

        public NewsItem(String title, String content, String date, String type) {
            this.title = title;
            this.content = content;
            this.date = date;
            this.type = type;
            this.imageUrl = "";
        }

        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getDate() { return date; }
        public String getImageUrl() { return imageUrl; }
        public String getType() { return type; }
    }

    private NewsManager() {
        this.gson = new Gson();
        this.cachedNews = new ArrayList<>();
        generateDefaultNews();
    }

    public static synchronized NewsManager getInstance() {
        if (instance == null) {
            instance = new NewsManager();
        }
        return instance;
    }

    private void generateDefaultNews() {
        cachedNews.add(new NewsItem(
                "Welcome to PowerLaunch!",
                "PowerLaunch is a powerful and modern launcher for Minecraft. " +
                        "Enjoy the game with the best launcher!",
                "01.01.2025 12:00",
                "info"
        ));
        cachedNews.add(new NewsItem(
                "How to get started",
                "1. Enter your nickname in the login section\n" +
                        "2. Choose a Minecraft version\n" +
                        "3. Configure RAM and other settings\n" +
                        "4. Press Play!",
                "01.01.2025 12:00",
                "guide"
        ));
        cachedNews.add(new NewsItem(
                "Skin Support",
                "The launcher supports custom skins! Upload your skin in the Skins section.",
                "01.01.2025 12:00",
                "update"
        ));
    }

    public CompletableFuture<List<NewsItem>> loadNews() {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(NEWS_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
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

                    Type listType = new TypeToken<List<NewsItem>>() {}.getType();
                    List<NewsItem> news = gson.fromJson(body, listType);
                    if (news != null && !news.isEmpty()) {
                        cachedNews = news;
                        loaded = true;
                        return cachedNews;
                    }
                } else {
                    System.err.println("[PowerLaunch][News] HTTP " + status + " for " + NEWS_URL);
                }
            } catch (java.net.UnknownHostException e) {
                System.err.println("[PowerLaunch][News] DNS resolution failed (" + NEWS_URL + "): "
                    + e.getMessage() + " — cannot resolve server (check DNS/hosts)");
            } catch (java.net.ConnectException e) {
                System.err.println("[PowerLaunch][News] Connection refused (" + NEWS_URL + "): "
                    + e.getMessage() + " — server is unreachable or blocked by firewall");
            } catch (java.net.SocketTimeoutException e) {
                System.err.println("[PowerLaunch][News] Timeout (" + NEWS_URL + "): "
                    + e.getMessage() + " — check your internet connection");
            } catch (javax.net.ssl.SSLException e) {
                System.err.println("[PowerLaunch][News] SSL/TLS error (" + NEWS_URL + "): "
                    + e.getMessage() + " — certificate problems or TLS version issues");
            } catch (Exception e) {
                System.err.println("[PowerLaunch][News] Failed to load news (" + NEWS_URL + "): ["
                    + e.getClass().getSimpleName() + "] " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            loaded = true;
            return cachedNews;
        });
    }

    public List<NewsItem> getCachedNews() {
        return cachedNews;
    }

    public boolean isLoaded() {
        return loaded;
    }
}
