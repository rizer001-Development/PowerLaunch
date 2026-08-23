package com.powerlaunch.utils;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manager for writing launcher console logs to file.
 * Enabled/disabled via SettingsManager: saveConsoleLog = true/false.
 *
 * <p>Log files are saved to {@code <LauncherHome>/logs/console-<timestamp>.log}.
 * Each Minecraft launch creates a new log file.</p>
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
     * Enables file logging. Creates a new file with the current date-time.
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
     * Disables file logging and closes the file.
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
     * Writes a line to the log file (only if enabled).
     */
    public synchronized void log(String message) {
        if (enabled && writer != null) {
            writer.println(message);
        }
    }

    /**
     * Writes prefix + message to the log file (only if enabled).
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
     * Closes the log manager (same as disable).
     */
    public synchronized void close() {
        disable();
    }
}
