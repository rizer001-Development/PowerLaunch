package com.powerlaunch;

import com.powerlaunch.auth.AccountManager;
import com.powerlaunch.auth.AuthManager;
import com.powerlaunch.minecraft.MinecraftLauncher;
import com.powerlaunch.minecraft.VersionManager;
import com.powerlaunch.settings.SettingsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command-line launcher for PowerLaunch.
 * Launches Minecraft directly without opening the GUI window.
 * 
 * Usage:
 *   java -jar PowerLaunch.jar cli --version 1.7.10 --gameDir C:\\.minecraft --ram 4096 --username Rizer001
 * 
 * Or with gradle:
 *   gradlew run --args="cli --version 1.7.10 --gameDir C:\\.minecraft --ram 4096 --username Rizer001"
 */
public class CliLauncher {

    private static boolean fullLogging = false;

    public static void main(String[] args) {
        run(args);
    }

    /**
     * Entry point called from Main.java when --cli is detected.
     */
    public static void run(String[] args) {
        System.out.println("=== PowerLaunch CLI ===");
        System.out.println();

        // Dump ALL received arguments for debugging
        System.out.println("  [DEBUG] Received " + args.length + " arguments:");
        for (int ai = 0; ai < args.length; ai++) {
            System.out.println("    args[" + ai + "] = '" + args[ai] + "'");
        }
        System.out.println();

        // Parse arguments
        String version = null;
        String gameDir = null;
        String username = null;
        int ram = 4096;
        String serverIp = null;
        boolean allLogs = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].toLowerCase();
            switch (arg) {
                case "--version":
                case "-v":
                    if (i + 1 < args.length) version = args[++i];
                    break;
                case "--gamedir":
                case "--game-dir":
                case "-g":
                    if (i + 1 < args.length) gameDir = args[++i];
                    break;
                case "--username":
                case "-u":
                    if (i + 1 < args.length) username = args[++i];
                    break;
                case "--ram":
                case "-r":
                    if (i + 1 < args.length) {
                        try {
                            ram = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("  [ERROR] Invalid RAM value: " + args[i]);
                        }
                    }
                    break;
                case "--server":
                case "-s":
                    if (i + 1 < args.length) serverIp = args[++i];
                    break;
                case "--all-logs":
                case "--alllogs":
                case "-a":
                    allLogs = true;
                    break;
                case "--help":
                case "-h":
                    printHelp();
                    return;
            }
        }

        fullLogging = allLogs;

        // Validate
        if (version == null || version.isEmpty()) {
            System.err.println("  [ERROR] Missing --version argument");
            printHelp();
            System.exit(1);
            return;
        }

        // Default game directory
        if (gameDir == null || gameDir.isEmpty()) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                gameDir = System.getenv("APPDATA") + File.separator + ".minecraft";
            } else if (os.contains("mac")) {
                gameDir = System.getProperty("user.home") + "/Library/Application Support/minecraft";
            } else {
                gameDir = System.getProperty("user.home") + "/.minecraft";
            }
            System.out.println("  [INFO] Using default game directory: " + gameDir);
        }

        System.out.println("  Version:    " + version);
        System.out.println("  Game dir:   " + gameDir);
        System.out.println("  RAM:        " + ram + " MB");
        System.out.println("  Username:   " + (username != null ? username : "auto"));
        System.out.println("  Full logs:  " + (allLogs ? "yes" : "errors only"));
        System.out.println("  Server:     " + (serverIp != null ? serverIp : "none"));
        System.out.println();

        // Initialize managers
        System.out.println("  [1/5] Initializing settings...");
        SettingsManager settings = SettingsManager.getInstance();

        // Set game directory (verify it's being applied)
        if (gameDir != null && !gameDir.isEmpty()) {
            System.out.println("  [DEBUG] Setting gameDirectory = " + gameDir);
            settings.set("gameDirectory", gameDir);
            // Verify it was saved
            String check = settings.getString("gameDirectory", "FAILED");
            System.out.println("  [DEBUG] gameDirectory read back = " + check);
        }

        // Set RAM
        settings.set("ram", ram);

        // Set the version
        settings.set("selectedVersion", version);

        // Create/select account
        System.out.println("  [2/5] Setting up account...");
        AccountManager accountManager = AccountManager.getInstance();
        AuthManager auth = AuthManager.getInstance();

        if (username != null && !username.isEmpty()) {
            String finalUsername = username; // effectively final for lambda
            // Create account if doesn't exist
            boolean exists = accountManager.getAccounts().stream()
                    .anyMatch(a -> a.getUsername().equals(finalUsername));
            if (!exists) {
                accountManager.createAccount(finalUsername);
            }
            accountManager.selectAccount(finalUsername);
            auth.loginOffline(finalUsername);
            System.out.println("  [INFO] Using account: " + finalUsername);
        } else if (accountManager.hasAccounts()) {
            var current = accountManager.getCurrentAccount();
            if (current != null) {
                auth.loginOffline(current.getUsername());
                System.out.println("  [INFO] Using existing account: " + current.getUsername());
            }
        }

        if (!auth.isLoggedIn()) {
            System.err.println("  [ERROR] No account available. Use --username or create one in the launcher.");
            System.exit(1);
            return;
        }

        // Check version
        System.out.println("  [3/5] Checking version...");
        
        // First check if game directory exists
        File gameDirFile = new File(gameDir);
        if (!gameDirFile.exists()) {
            System.err.println("  [ERROR] Game directory does not exist: " + gameDir);
            System.err.println("  [HINT]  Make sure the path is correct.");
            // Show what drives exist
            System.out.println("  [INFO] Available drives:");
            for (File root : File.listRoots()) {
                System.out.println("    " + root.getAbsolutePath() + " (" + (root.canRead() ? "readable" : "?") + ")");
            }
            System.exit(1);
            return;
        }
        System.out.println("  [OK] Game directory exists: " + gameDirFile.getAbsolutePath());
        
        // List available versions
        File versionsFolder = new File(gameDir, "versions");
        System.out.println("  [INFO] Checking: " + versionsFolder.getAbsolutePath());
        if (versionsFolder.exists()) {
            File[] versionDirs = versionsFolder.listFiles(File::isDirectory);
            if (versionDirs != null && versionDirs.length > 0) {
                System.out.println("  [INFO] Available versions (" + versionDirs.length + "):");
                for (File vDir : versionDirs) {
                    System.out.println("    - " + vDir.getName());
                }
            } else {
                System.out.println("  [INFO] No installed versions found.");
            }
        } else {
            System.out.println("  [INFO] Versions folder does not exist: " + versionsFolder);
        }
        
        VersionManager versionManager = VersionManager.getInstance();
        versionManager.selectVersion(version);

        // Validate version exists
        String versionsDir = gameDir + File.separator + "versions" + File.separator + version;
        File versionJar = new File(versionsDir, version + ".jar");
        if (!versionJar.exists()) {
            System.err.println("  [ERROR] Version '" + version + "' not found at: " + versionJar);
            System.exit(1);
            return;
        }
        System.out.println("  [OK] Version found at: " + versionJar);

        // Initialize launcher
        // Set server IP for auto-connect
        if (serverIp != null && !serverIp.isEmpty()) {
            settings.set("connectServerIp", serverIp);
            settings.set("autoConnect", true);
            System.out.println("  [INFO] Auto-connect to server: " + serverIp);
        }

        System.out.println("  [4/5] Preparing launcher...");
        MinecraftLauncher launcher = MinecraftLauncher.getInstance();

        // Set up console logging
        final CountDownLatch launchDone = new CountDownLatch(1);
        final AtomicInteger exitCode = new AtomicInteger(-1);

        launcher.setOnConsoleLine(line -> {
            if (fullLogging || isErrorLine(line)) {
                System.out.println(line);
            }
        });

        // Launch Minecraft
        System.out.println("  [5/5] Launching Minecraft " + version + "...");
        System.out.println();
        System.out.println("========================================");
        System.out.println("  MINECRAFT OUTPUT");
        System.out.println("========================================");
        System.out.println();

        var result = launcher.launchMinecraft(version, "vanilla", code -> {
            exitCode.set(code);
            launchDone.countDown();
        });

        if (!result.isSuccess()) {
            System.err.println();
            System.err.println("========================================");
            System.err.println("  LAUNCH FAILED: " + result.getMessage());
            System.err.println("========================================");
            System.exit(1);
            return;
        }

        System.out.println("  [OK] Minecraft process started (PID: " + launcher.getProcessPid() + ")");
        System.out.println("  Waiting for Minecraft to exit...");
        System.out.println();

        // Wait for Minecraft to finish
        try {
            boolean finished = launchDone.await(5, TimeUnit.MINUTES);
            if (!finished) {
                System.out.println();
                System.out.println("========================================");
                System.out.println("  TIMEOUT: Minecraft did not exit within 5 minutes");
                System.out.println("========================================");
                launcher.stopMinecraft();
                System.exit(2);
            }
        } catch (InterruptedException e) {
            System.out.println();
            System.out.println("  Interrupted, stopping Minecraft...");
            launcher.stopMinecraft();
            System.exit(130);
        }

        int code = exitCode.get();
        System.out.println();
        System.out.println("========================================");

        if (code == 0) {
            System.out.println("  MINECRAFT EXITED SUCCESSFULLY (code: 0)");
            System.out.println("========================================");
            System.exit(0);
        } else {
            System.out.println("  MINECRAFT CRASHED WITH EXIT CODE: " + code);
            System.out.println("========================================");
            System.out.println();

            // Print the last 50 lines of console log for debugging
            List<String> log = launcher.getConsoleLog();
            System.out.println("  --- Last " + Math.min(50, log.size()) + " console lines ---");
            System.out.println();
            int start = Math.max(0, log.size() - 50);
            for (int i = start; i < log.size(); i++) {
                System.out.println(log.get(i));
            }
            System.out.println();
            System.out.println("========================================");
            System.exit(1);
        }
    }

    /**
     * Checks if a log line contains error/crash information.
     */
    private static boolean isErrorLine(String line) {
        if (line == null) return false;
        String upper = line.toUpperCase();
        return upper.contains("EXCEPTION")
                || upper.contains("ERROR")
                || upper.contains("WARN")
                || upper.contains("CRASH")
                || upper.contains("FATAL")
                || upper.contains("FAILED")
                || upper.contains("UNSATISFIEDLINK")
                || upper.contains("NOSUCHMETHOD")
                || upper.contains("CLASS_NOT_FOUND")
                || upper.contains("NULLPOINTER")
                || upper.startsWith("[MINECRAFT] ----")
                || line.contains("Minecraft Crash Report");
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("PowerLaunch CLI — Launch Minecraft from command line");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  PowerLaunch cli --version <version> [options]");
        System.out.println();
        System.out.println("Required:");
        System.out.println("  --version, -v   Minecraft version (e.g. 1.7.10, 1.20.4)");
        System.out.println();
        System.out.println("Optional:");
        System.out.println("  --gameDir, -g   Game directory (default: ~/.minecraft)");
        System.out.println("  --ram, -r       RAM in MB (default: 4096)");
        System.out.println("  --username, -u  Minecraft username (default: auto)");
        System.out.println("  --all-logs, -a  Print ALL logs, not just errors");
        System.out.println("  --help, -h      Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  gradlew run --args=\"cli --version 1.7.10 --gameDir C:\\.minecraft\"");
        System.out.println("  gradlew run --args=\"cli --version 1.20.4 --ram 8192 --username Steve --all-logs\"");
        System.out.println();
    }
}
