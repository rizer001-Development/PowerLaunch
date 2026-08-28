package com.powerlaunch.utils;

import com.powerlaunch.launcher.LauncherHomeProvider;
import com.powerlaunch.storage.AppDatabase;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Log manager with two modes:
 * <ul>
 *   <li><b>Launcher log</b> — {@link #enableLauncher()} / {@link #disable()}.
 *       One file per launcher session: {@code logs/launcher-N.log}.</li>
 *   <li><b>Game log</b> — {@link #enableGame()} / {@link #disable()}.
 *       One file per game launch: {@code logs/game-N.log}.
 *       Even a 1-second crash gets its own numbered file.</li>
 * </ul>
 *
 * <p>Log numbering is persisted in SQLite (monotonically increasing, never reused).</p>
 */
public class FileLogManager {

    private static FileLogManager instance;
    private PrintWriter writer;
    private Path logPath;
    private boolean enabled = false;

    private FileLogManager() {}

    public static synchronized FileLogManager getInstance() {
        if (instance == null) instance = new FileLogManager();
        return instance;
    }

    /** Create a new launcher session log: {@code logs/launcher-N.log}. */
    public synchronized void enableLauncher() {
        enable("launcher");
    }

    /** Create a new game session log: {@code logs/game-N.log}. */
    public synchronized void enableGame() {
        enable("game");
    }

    private void enable(String prefix) {
        if (enabled) return;
        try {
            Path logsDir = LauncherHomeProvider.getLauncherHome().resolve("logs");
            Files.createDirectories(logsDir);

            int num = AppDatabase.getInstance().nextLogNumber();
            String ts = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            String fileName = prefix + "-" + num + "_" + ts + ".log";
            logPath = logsDir.resolve(fileName);

            writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(logPath.toFile(), true), "UTF-8"), true);
            enabled = true;
            writer.println("[Log] === " + prefix + " session #" + num
                    + " started at " + LocalDateTime.now() + " ===");
            System.out.println("[PowerLaunch] Logging enabled → " + logPath);
        } catch (Exception e) {
            System.err.println("[FileLogManager] Failed to enable: " + e.getMessage());
            enabled = false;
        }
    }

    /** Close current log file. Safe to call even if not enabled. */
    public synchronized void disable() {
        if (!enabled) return;
        if (writer != null) {
            writer.println("[Log] === Log closed at " + LocalDateTime.now() + " ===");
            writer.close();
            writer = null;
        }
        enabled = false;
    }

    /** Write a plain line. */
    public synchronized void log(String message) {
        if (enabled && writer != null) writer.println(message);
    }

    /** Write a prefixed line. */
    public synchronized void log(String prefix, String message) {
        if (enabled && writer != null) writer.println(prefix + " " + message);
    }

    public synchronized boolean isEnabled() { return enabled; }
    public Path getLogPath() { return logPath; }
    public synchronized void close() { disable(); }
}
