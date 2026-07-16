package com.powerlaunch.utils;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Менеджер для записи логов консоли лаунчера в файл.
 * Включается/выключается через SettingsManager: saveConsoleLog = true/false.
 *
 * <p>Файлы логов сохраняются в {@code <LauncherHome>/logs/console-<timestamp>.log}.
 * Каждый запуск Minecraft создаёт новый файл лога.</p>
 */
public class FileLogManager {

    private static FileLogManager instance;
    private PrintWriter writer;
    private Path logPath;
    private boolean enabled = false;

    private FileLogManager() {}

    public static synchronized FileLogManager getInstance() {
        if (instance == null) {
            instance = new FileLogManager();
        }
        return instance;
    }

    /**
     * Включает запись логов в файл. Создаёт новый файл с текущей датой-временем.
     */
    public synchronized void enable() {
        if (enabled) return;
        try {
            Path logsDir = com.powerlaunch.launcher.LauncherHomeProvider.getLauncherHome().resolve("logs");
            Files.createDirectories(logsDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            logPath = logsDir.resolve("console-" + timestamp + ".log");
            writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(logPath.toFile(), true), "UTF-8"), true);
            enabled = true;
            writer.println("[Log] === Console log started at " + LocalDateTime.now() + " ===");
            System.out.println("[PowerLaunch] Console logging enabled \u2192 " + logPath);
        } catch (IOException e) {
            System.err.println("[FileLogManager] Failed to enable: " + e.getMessage());
            enabled = false;
        }
    }

    /**
     * Выключает запись логов и закрывает файл.
     */
    public synchronized void disable() {
        if (!enabled) return;
        if (writer != null) {
            writer.println("[Log] === Console log closed at " + LocalDateTime.now() + " ===");
            writer.close();
            writer = null;
        }
        enabled = false;
        System.out.println("[PowerLaunch] Console logging disabled");
    }

    /**
     * Записывает строку в лог-файл (только если включено).
     */
    public synchronized void log(String message) {
        if (enabled && writer != null) {
            writer.println(message);
        }
    }

    /**
     * Записывает префикс + сообщение в лог-файл (только если включено).
     */
    public synchronized void log(String prefix, String message) {
        if (enabled && writer != null) {
            writer.println(prefix + " " + message);
        }
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public Path getLogPath() {
        return logPath;
    }

    /**
     * Закрывает лог-менеджер (то же что disable).
     */
    public synchronized void close() {
        disable();
    }
}
