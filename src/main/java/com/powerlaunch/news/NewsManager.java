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
    // TODO: замените URL на свой репозиторий с новостями
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
                "Добро пожаловать в PowerLaunch!",
                "PowerLaunch — это мощный и современный лаунчер для Minecraft. " +
                        "Наслаждайтесь игрой с лучшим лаунчером!",
                "01.01.2025 12:00",
                "info"
        ));
        cachedNews.add(new NewsItem(
                "Как начать играть",
                "1. Введите свой ник в разделе авторизации\n" +
                        "2. Выберите версию Minecraft\n" +
                        "3. Настройте RAM и другие параметры\n" +
                        "4. Нажмите Play!",
                "01.01.2025 12:00",
                "guide"
        ));
        cachedNews.add(new NewsItem(
                "Поддержка скинов",
                "В лаунчере есть поддержка кастомных скинов! Загрузите свой скин в разделе Скины.",
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
                    + e.getMessage() + " — не удаётся найти сервер (проверьте DNS/hosts)");
            } catch (java.net.ConnectException e) {
                System.err.println("[PowerLaunch][News] Connection refused (" + NEWS_URL + "): "
                    + e.getMessage() + " — сервер недоступен или блокируется файрволом");
            } catch (java.net.SocketTimeoutException e) {
                System.err.println("[PowerLaunch][News] Timeout (" + NEWS_URL + "): "
                    + e.getMessage() + " — проверьте интернет-соединение");
            } catch (javax.net.ssl.SSLException e) {
                System.err.println("[PowerLaunch][News] SSL/TLS error (" + NEWS_URL + "): "
                    + e.getMessage() + " — проблемы с сертификатами или TLS версией");
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
