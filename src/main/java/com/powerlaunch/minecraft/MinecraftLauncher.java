package com.powerlaunch.minecraft;

import com.powerlaunch.auth.AuthManager;
import com.powerlaunch.settings.SettingsManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class MinecraftLauncher {

    @FunctionalInterface
    public interface ProcessExitCallback {
        void onExit(int exitCode);
    }
    private static MinecraftLauncher instance;
    private Process minecraftProcess;
    private boolean running;
    private final List<String> consoleLog;
    private Consumer<String> onConsoleLine;

    private MinecraftLauncher() {
        this.running = false;
        this.consoleLog = new ArrayList<>();
    }

    public static synchronized MinecraftLauncher getInstance() {
        if (instance == null) {
            instance = new MinecraftLauncher();
        }
        return instance;
    }

    public static class LaunchResult {
        private final boolean success;
        private final String message;

        public LaunchResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }

    public LaunchResult launchMinecraft(String version, String modpack) {
        return launchMinecraft(version, modpack, null);
    }

    public LaunchResult launchMinecraft(String version, String modpack, ProcessExitCallback callback) {
        if (minecraftProcess != null && minecraftProcess.isAlive()) {
            return new LaunchResult(false, "Minecraft is already running!");
        }

        AuthManager auth = AuthManager.getInstance();
        if (!auth.isLoggedIn()) {
            return new LaunchResult(false, "Please log in first!");
        }

        SettingsManager settings = SettingsManager.getInstance();

        try {
            // Validate Java path — check compatibility with the game version
            String gameDir = getGameDirectory();
            String versionsDir = gameDir + File.separator + "versions";
            String versionJsonPath = versionsDir + File.separator + version + File.separator + version + ".json";
            String javaPath = getJavaPath();
            if (javaPath == null || !new File(javaPath).exists()) {
                javaPath = findJavaInPath();
            }
            if (javaPath == null || !new File(javaPath).exists()) {
                return new LaunchResult(false, "Java not found. Install Java or set the path in settings.");
            }

            // Read required Java version from version.json (e.g., Java 26 for MC 26.x)
            int requiredJava = getRequiredJavaVersion(gameDir, version);
            if (requiredJava <= 0) requiredJava = 21; // fallback
            System.out.println("[PowerLaunch] Minecraft requires Java " + requiredJava + "+");
            
            // Try to find a compatible Java version
            String compatibleJava = findCompatibleJava(requiredJava);
            if (compatibleJava != null && !compatibleJava.equals(javaPath)) {
                System.out.println("[PowerLaunch] Using Java " + getJavaMajorVersion(compatibleJava) + " for Minecraft (found at: " + compatibleJava + ")");
                javaPath = compatibleJava;
            } else {
                int currentMajor = getJavaMajorVersion(javaPath);
                if (currentMajor < requiredJava) {
                    return new LaunchResult(false, "Java " + requiredJava + "+ is required for this Minecraft version, but only Java " + currentMajor + " was found.\nInstall Java " + requiredJava + " or set the path in settings.");
                }
                System.out.println("[PowerLaunch] Using system Java for Minecraft: " + javaPath);
            }

            // LWJGL 2.x compatibility with Java 22+ is handled via --enable-native-access below.
            int javaMajor = getJavaMajorVersion(javaPath);

            String librariesDir = gameDir + File.separator + "libraries";
            String assetsDir = gameDir + File.separator + "assets";
            String nativesDir = gameDir + File.separator + "natives";

            // Validate version exists
            File versionJar = new File(versionsDir + File.separator + version + File.separator + version + ".jar");
            if (!versionJar.exists()) {
                return new LaunchResult(false, "Minecraft version '" + version + "' not found.\nInstall it via the official launcher to: " + versionsDir);
            }

            // Validate libraries exist
            if (!new File(librariesDir).exists()) {
                return new LaunchResult(false, "Minecraft libraries not found in " + librariesDir);
            }

            // Create necessary directories
            new File(nativesDir).mkdirs();
            new File(assetsDir).mkdirs();
            new File(versionsDir).mkdirs();

            // Extract native libraries (LWJGL .dll/.so/.dylib) into natives directory
            try {
                extractNatives(librariesDir, nativesDir);
            } catch (IOException e) {
                System.err.println("[PowerLaunch] Warning: Failed to extract native libraries: " + e.getMessage());
            }

            List<String> command = new ArrayList<>();
            command.add(javaPath);

            // RAM settings
            int ramMB = settings.getInt("ram", 4096);
            if (ramMB < 512) ramMB = 512;
            command.add("-Xms" + Math.min(ramMB / 2, 1024) + "M");
            command.add("-Xmx" + ramMB + "M");

            // We do NOT force IPv4! Java 9+ uses IPv6/IPv4 dual-stack.
            // On some networks IPv6 works but IPv4 is blocked/unavailable.
            // preferIPv4Stack=true kills IPv6 connections → timeout when connecting
            // to multiplayer servers.
            // Instead, we enable short DNS TTL so Java doesn't cache
            // stale DNS records (especially relevant when switching networks/VPN).
            command.add("-Dsun.net.inetaddr.ttl=0");

            // Custom Java args (filter out conflicts AND drop potentially-unsafe proxy/DNS args
            // that could be left over from alternative launchers and silently redirect MC traffic).
            String customArgs = settings.getString("javaArgs", "");
            if (!customArgs.isEmpty()) {
                for (String arg : customArgs.split(" ")) {
                    String trimmed = arg.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.startsWith("-Xmx") || trimmed.startsWith("-Xms")) continue;
                    // SECURITY: drop proxy / DNS-injection / TLS-pinning-bypass / bootclasspath /
                    // native-agent args. Without this, leaked flags from prior configs can:
                    //   - silently proxy/blackhole MC traffic (-DproxyHost, -DsocksProxyHost)
                    //   - hijack DNS resolution (-Dsun.net.spi.nameservice.nameservers)
                    //   - load captive truststore → MITM server TLS cert (-Djavax.net.ssl.trustStore)
                    //   - downgrade TLS to SSLv3 → trivial MITM (-Dhttps.protocols, -Djdk.tls.client.protocols)
                    //   - inject arbitrary classes BEFORE MC loads (-Xbootclasspath, -Xbootclasspath/a)
                    //   - load native agent libraries for RCE (-agentlib:, -javaagent:)
                    //   - enable JDWP remote debug attach (-Xrunjdwp:)
                    String lower = trimmed.toLowerCase();
                    if (lower.startsWith("-dproxyhost") || lower.startsWith("-dproxyport") ||
                        lower.startsWith("-dhttp.proxyhost") || lower.startsWith("-dhttp.proxyport") ||
                        lower.startsWith("-dhttps.proxyhost") || lower.startsWith("-dhttps.proxyport") ||
                        lower.startsWith("-dhttp.nonproxyhosts") ||
                        lower.startsWith("-dsocksproxyhost") || lower.startsWith("-dsocksproxyport") ||
                        lower.startsWith("-dsocksproxypasswd") ||
                        lower.startsWith("-djava.net.preferipv4stack") ||  // don't let custom override our setting
                        lower.startsWith("-djava.security.manager") ||  // blocked by JPMS in Java 17+ anyway
                        lower.startsWith("-djava.security.policy") ||
                        lower.startsWith("-dsun.net.spi.nameservice.nameservers") ||  // DNS hijack vector
                        lower.startsWith("-dcom.sun.net.ssl.checkrevocation") ||
                        lower.startsWith("-djavax.net.ssl.truststore") ||        // captive truststore → MITM
                        lower.startsWith("-djavax.net.ssl.truststorepassword") ||
                        lower.startsWith("-djavax.net.ssl.keystore") ||          // client-key spoofing
                        lower.startsWith("-dhttps.protocols") ||                // TLS downgrade
                        lower.startsWith("-djdk.tls.client.protocols") ||        // TLS downgrade (JDK 8+)
                        lower.startsWith("-xbootclasspath") ||                  // bootclasspath injection
                        lower.startsWith("-agentlib:") ||                        // native agent (RCE)
                        lower.startsWith("-javaagent:") ||                       // java agent (untrusted)
                        lower.startsWith("-xrunjdwp:")) {                        // JDWP remote debug
                        System.err.println("[PowerLaunch] Dropped potentially unsafe arg from javaArgs: " + trimmed);
                        continue;
                    }
                    command.add(trimmed);
                }
            }

            // JVM args — add --enable-native-access for Java 22+ (required by LWJGL 2.x)
            javaMajor = getJavaMajorVersion(javaPath); // Re-check version (may have changed via findCompatibleJava)
            if (javaMajor >= 22) {
                command.add("--enable-native-access=ALL-UNNAMED");
                // Java 26 module system may block access to java.net sockets
                // for older Netty versions (Minecraft uses Netty for networking).
                // --add-opens opens modules for reflective access needed by
                // Fabric, Mixin, SpongePowered ASM, and Minecraft itself.
                command.add("--add-opens");
                command.add("java.base/java.net=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.lang=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.lang.reflect=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.util=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.io=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.nio=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/sun.nio.ch=ALL-UNNAMED");
                command.add("--add-opens");
                command.add("java.base/java.security=ALL-UNNAMED");
                // Gson uses reflection to set final fields — allow it on Java 26+
                if (javaMajor >= 26) {
                    command.add("--enable-final-field-mutation=ALL-UNNAMED");
                }
                // Do NOT use -noverify — it's deprecated since JDK 13 and may cause
                // undefined JVM behavior on Java 26, including socket issues.
            }

            command.add("-Djava.library.path=" + nativesDir);
            command.add("-cp");
            command.add(buildClassPath(gameDir, version));
            // Use mainClass from version.json (Fabric, Forge, etc. have custom main classes).
            // IMPORTANT: If the version.json specifies a modloader main class (KnotClient,
            // LaunchWrapper, etc.), ALWAYS use it. These are merged jars (TLauncher-style)
            // where Fabric/Forge classes are embedded in the version jar. Falling back to
            // vanilla main class would break mod loading and networking.
            String mainClass = getMainClass(gameDir, version);
            System.out.println("[PowerLaunch] Using mainClass: " + mainClass);
            command.add(mainClass);

            // Minecraft args (order matters for some versions)
            command.add("--username");
            command.add(auth.getUsername());
            command.add("--uuid");
            command.add(auth.getUuid().toString().replace("-", ""));
            // Access token: use a valid UUID-like format for offline mode.
            // "0" is rejected by modern servers (1.19.3+). Use a deterministic
            // UUID derived from the player name so servers see a consistent token.
            command.add("--accessToken");
            command.add(auth.getUuid().toString());
            command.add("--version");
            command.add(version);
            command.add("--gameDir");
            command.add(gameDir);
            command.add("--assetsDir");
            command.add(assetsDir);
            command.add("--assetIndex");
            command.add(getAssetIndex(gameDir, version));
            command.add("--userProperties");
            command.add("{}");
            // userType: "legacy" for offline mode, "mojang" for authenticated.
            // Using "mojang" with a fake token causes server auth failures.
            command.add("--userType");
            command.add("legacy");
            // clientVersion: required by modern Minecraft (1.19.3+) for server connection.
            // Without it, the client may fail to connect to multiplayer servers.
            command.add("--clientVersion");
            command.add(version);
            // xuid/identity: used by modern MC for Xbox Live identification.
            // Required by some servers for proper player identification.
            command.add("--xuid");
            command.add("");
            command.add("--identity");
            command.add("");

            if (settings.getBoolean("useCustomResolution", false)) {
                int width = settings.getInt("gameWidth", 854);
                int height = settings.getInt("gameHeight", 480);
                command.add("--width");
                command.add(String.valueOf(width));
                command.add("--height");
                command.add(String.valueOf(height));
            }

            // Server IP for direct connect
            String serverIp = "";
            if (settings.getBoolean("autoConnect", false)) {
                serverIp = settings.getString("connectServerIp", "");
            }
            if (serverIp.isEmpty()) {
                serverIp = settings.getString("serverIp", "");
            }
            if (!serverIp.isEmpty()) {
                command.add("--server");
                command.add(serverIp.split(":")[0]);
                if (serverIp.contains(":")) {
                    command.add("--port");
                    command.add(serverIp.split(":")[1]);
                } else {
                    command.add("--port");
                    command.add("25565");
                }
            }

            // Log the FULL launch command for diagnosing network issues
            System.out.println("[PowerLaunch][Launch] Full command:");
            for (int i = 0; i < command.size(); i++) {
                String arg = command.get(i);
                if (arg.length() > 200) {
                    System.out.println("  [" + i + "] " + arg.substring(0, 200) + "... (" + arg.length() + " chars)");
                } else {
                    System.out.println("  [" + i + "] " + arg);
                }
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(gameDir));
            pb.redirectErrorStream(true);

            minecraftProcess = pb.start();
            System.out.println("[PowerLaunch][Launch] Minecraft process started, PID=" + minecraftProcess.pid());
            running = true;

            // Consume process output in background thread to prevent I/O blocking
            new Thread(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(minecraftProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String logLine = "[Minecraft] " + line;
                        System.out.println(logLine);
                        System.out.flush();
                        synchronized (consoleLog) {
                            consoleLog.add(logLine);
                            if (consoleLog.size() > 10000) {
                                consoleLog.remove(0);
                            }
                        }
                        if (onConsoleLine != null) {
                            onConsoleLine.accept(logLine);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[PowerLaunch] Console reader error: " + e.getMessage());
                }
            }, "Minecraft-Console").start();

            // Monitor process in background
            new Thread(() -> {
                try {
                    int exitCode = minecraftProcess.waitFor();
                    // Small delay to let the console reader thread finish reading all output
                    // Fixes instant crash where callback fires before logs are captured
                    try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (callback != null) {
                        callback.onExit(exitCode);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running = false;
                    minecraftProcess = null;
                }
            }, "Minecraft-Watcher").start();

            return new LaunchResult(true, "Minecraft launched!");

        } catch (IOException e) {
            return new LaunchResult(false, "Launch error: " + e.getMessage());
        }
    }

    private String getJavaPath() {
        String javaHome = System.getProperty("java.home");
        String os = System.getProperty("os.name").toLowerCase();

        File binDir = new File(javaHome + File.separator + "bin");

        // On Windows: prefer java.exe over javaw.exe.
        // javaw.exe doesn't attach to a console and can cause output pipe issues
        // with ProcessBuilder. java.exe with ProcessBuilder still creates a window
        // for the child process; we just need its stdout/stderr piped correctly.
        if (os.contains("win")) {
            File java = new File(binDir, "java.exe");
            if (java.exists()) return java.getAbsolutePath();
            File javaw = new File(binDir, "javaw.exe");
            if (javaw.exists()) return javaw.getAbsolutePath();
        }

        // On non-Windows or if Windows executables not found
        File java = new File(binDir, "java");
        if (java.exists()) return java.getAbsolutePath();

        // Last resort: return the default path anyway
        return java.getAbsolutePath();
    }

    private int getJavaMajorVersion(String javaExe) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{javaExe, "-version"});
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    // Format: openjdk version "26.0.1" 2025-04-15
                    int quoteStart = line.indexOf('"');
                    if (quoteStart >= 0) {
                        int dotIdx = line.indexOf('.', quoteStart);
                        if (dotIdx > quoteStart) {
                            String major = line.substring(quoteStart + 1, dotIdx);
                            try {
                                return Integer.parseInt(major);
                            } catch (NumberFormatException e) {
                                System.err.println("[PowerLaunch] Invalid Java version format: " + major);
                            }
                        }
                    }
                }
            }
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            p.destroy();
        } catch (Exception e) {
            System.err.println("[PowerLaunch] Failed to get Java version: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Searches for a Java installation with version <= maxVersion.
     * Checks common Windows installation directories and the system PATH.
     * This is used to find a Java 8-17 compatible with LWJGL 2.x native code
     * when the launcher itself runs on Java 22+.
     */
    private String findCompatibleJava(int maxVersion) {
        // Check if the current Java is already compatible
        String currentJava = getJavaPath();
        int currentMajor = getJavaMajorVersion(currentJava);
        if (currentMajor > 0 && currentMajor <= maxVersion) {
            return currentJava;
        }

        // Common Windows Java installation directories to search
        List<String> searchPaths = new ArrayList<>();
        searchPaths.add("C:\\Program Files\\Java");
        searchPaths.add("C:\\Program Files (x86)\\Java");
        searchPaths.add("C:\\Program Files\\Eclipse Adoptium");
        searchPaths.add("C:\\Program Files\\Amazon Corretto");
        searchPaths.add("C:\\Program Files\\Zulu");
        searchPaths.add(System.getenv("LOCALAPPDATA") + "\\Programs\\Eclipse Adoptium");
        searchPaths.add(System.getenv("LOCALAPPDATA") + "\\Programs\\Java");
        searchPaths.add("C:\\Program Files\\Microsoft\\jdk-");

        // Search in PATH for other java executables
        String pathFromPath = findJavaInPath();
        if (pathFromPath != null) {
            int pathMajor = getJavaMajorVersion(pathFromPath);
            if (pathMajor > 0 && pathMajor <= maxVersion) {
                return pathFromPath;
            }
        }

        // Search in common directories for java executables
        for (String basePath : searchPaths) {
            if (basePath == null || basePath.startsWith("null")) continue; // Skip if env var not set
            File baseDir = new File(basePath);
            if (!baseDir.exists()) continue;

            File[] jdkDirs = baseDir.listFiles(File::isDirectory);
            if (jdkDirs == null) continue;

            for (File jdkDir : jdkDirs) {
                // Try standard JDK bin/java.exe and JRE bin/java.exe
                String[] javaPaths = {
                    jdkDir.getAbsolutePath() + File.separator + "bin" + File.separator + "java.exe",
                    jdkDir.getAbsolutePath() + File.separator + "bin" + File.separator + "javaw.exe",
                    jdkDir.getAbsolutePath() + File.separator + "jre" + File.separator + "bin" + File.separator + "java.exe",
                    jdkDir.getAbsolutePath() + File.separator + "jre" + File.separator + "bin" + File.separator + "javaw.exe"
                };
                for (String javaCandidate : javaPaths) {
                    File javaFile = new File(javaCandidate);
                    if (!javaFile.exists()) continue;
                    int major = getJavaMajorVersion(javaFile.getAbsolutePath());
                    if (major > 0 && major <= maxVersion) {
                        System.out.println("[PowerLaunch] Found compatible Java " + major
                            + " at: " + javaCandidate);
                        return javaFile.getAbsolutePath();
                    }
                }
            }
        }

        return null;
    }

    private String findJavaInPath() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String cmd = os.contains("win") ? "where java" : "which java";
            Process p = Runtime.getRuntime().exec(cmd);
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    String path = line.trim();
                    if (new File(path).exists()) return path;
                }
            }
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return null;
    }

    private String getGameDirectory() {
        SettingsManager settings = SettingsManager.getInstance();
        String customDir = settings.getString("gameDirectory", "");
        if (!customDir.isEmpty()) {
            return customDir;
        }

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return System.getenv("APPDATA") + File.separator + ".powerlaunch";
        } else if (os.contains("mac")) {
            return System.getProperty("user.home") + File.separator
                    + "Library" + File.separator + "Application Support"
                    + File.separator + ".powerlaunch";
        }
        return System.getProperty("user.home") + File.separator + ".powerlaunch";
    }

    private String buildClassPath(String gameDir, String version) throws IOException {
        String mainClassCheck = getMainClass(gameDir, version);
        boolean isFabric = mainClassCheck != null && mainClassCheck.contains("KnotClient");

        // ─── Fabric / TLauncher merged jar: only the version jar on -cp ───
        // Fabric's KnotClassLoader reads version.json and loads all libraries into its
        // own classloader (KnotClassLoader). The system classloader (which uses -cp)
        // must NOT see the library jars because they cause SecurityException:
        //   "signer information does not match" when signed jars (fabric-loader)
        //   and unsigned jars (sponge-mixin, merged jar) share the same package.
        // By putting ONLY the version jar on -cp, the system classloader loads nothing
        // conflicting, and Fabric handles everything else.
        File versionJar = new File(gameDir + File.separator + "versions"
                + File.separator + version + File.separator + version + ".jar");
        if (!versionJar.exists()) {
            throw new IOException("Version jar not found: " + versionJar.getAbsolutePath());
        }

        if (isFabric) {
            File stripped = stripEmbeddedGson(versionJar);
            // For Fabric with TLauncher merged jars: parse version.json to get the
            // EXACT list of bootstrap libraries. This avoids:
            //   1. Duplicate ASM versions (e.g. 6.2 + 9.10.1 → "duplicate ASM classes")
            //   2. SecurityException from signed/unsigned jar conflicts on -cp
            // Fabric's KnotClassLoader loads ALL libraries at runtime anyway;
            // we only need the ones listed in version.json for system classpath bootstrap.
            List<String> fabricCp = new ArrayList<>();
            fabricCp.add(stripped.getAbsolutePath());

            File librariesDir = new File(gameDir + File.separator + "libraries");
            String versionJsonPath = gameDir + File.separator + "versions" + File.separator
                    + version + File.separator + version + ".json";
            List<String> bootstrapLibs = resolveVersionJsonLibraries(versionJsonPath, librariesDir);

            // Strip signatures from ALL bootstrap jars to prevent SecurityException
            int sigStripped = 0;
            for (String libPath : bootstrapLibs) {
                File libFile = new File(libPath);
                File safe = stripSignaturesIfNeeded(libFile);
                fabricCp.add(safe.getAbsolutePath());
                if (!safe.equals(libFile)) sigStripped++;
            }

            System.out.println("[PowerLaunch] Fabric mode: -cp = version.jar + "
                    + bootstrapLibs.size() + " bootstrap libs (" + sigStripped + " signatures stripped)");
            return String.join(File.pathSeparator, fabricCp);
        }

        // ─── Vanilla / Forge / NeoForge: full classpath with all library jars ───
        File librariesDir = new File(gameDir + File.separator + "libraries");
        List<String> jarPaths = new ArrayList<>();
        if (librariesDir.exists()) {
            collectJars(jarPaths, librariesDir);
        }

        // Add version jar (with Gson classes stripped)
        File stripped = stripEmbeddedGson(versionJar);
        jarPaths.add(stripped.getAbsolutePath());

        // Add the referenced jar (vanilla Minecraft jar) if present
        String referencedJar = getJarReference(gameDir, version);
        if (referencedJar != null) {
            File refJar = new File(gameDir + File.separator + "versions"
                    + File.separator + referencedJar + File.separator + referencedJar + ".jar");
            if (refJar.exists()) {
                jarPaths.add(refJar.getAbsolutePath());
                System.out.println("[PowerLaunch] Added referenced jar: " + refJar.getName());
            }
        }

        // Sort classpath so that LWJGL 3 loads before LWJGL 2 for versions that use LWJGL 3.
        String versionJsonPath = gameDir + File.separator + "versions" + File.separator
                + version + File.separator + version + ".json";
        boolean usesLWJGL3 = checkForLWJGL3InVersion(versionJsonPath);
        if (usesLWJGL3) {
            Collections.sort(jarPaths, Collections.reverseOrder());
        } else {
            Collections.sort(jarPaths);
        }

        // Deduplicate all Maven artifacts
        deduplicateArtifacts(jarPaths, librariesDir);

        if (usesLWJGL3) {
            removeConflictingLWJGL(jarPaths, librariesDir);
        }

        removeDuplicateFatJars(jarPaths);

        // CRITICAL FIX: previously we used a temporary JAR with Class-Path manifest.
        // Class-Path in JAR Manifest is BROKEN on Java 22+ — Netty, Mojang authlib and
        // other dynamic resource lookups silently fail because the manifest is unreliable
        // (paths with spaces, special chars, multi-release jars, no module support).
        // Symptoms: Minecraft launches but NetworkThread/Netty fails to load classes,
        // connection to multiplayer servers times out.
        //
        // NEW APPROACH: build the classpath as a direct string and pass it via -cp.
        // Windows has a ~32K command-line limit, so if the classpath exceeds 8K chars
        // (safe margin), we use Java 9+ argfile syntax: -cp @<file> where file contains
        // the classpath entries on separate lines.
        StringBuilder classpathBuilder = new StringBuilder();
        for (String path : jarPaths) {
            if (classpathBuilder.length() > 0) {
                classpathBuilder.append(File.pathSeparator);
            }
            // Quote the path to handle spaces/special chars in the argfile form.
            // In direct command-line form, ProcessBuilder on Windows already wraps
            // arguments containing spaces — but the @argfile form needs explicit quotes
            // when a path literally contains spaces (e.g., "C:\Users\Dan\My Files\...").
            String normalized = new File(path).getAbsolutePath();
            classpathBuilder.append(normalized);
        }

        String classpath = classpathBuilder.toString();

        // Windows command-line length limit via Win32 CreateProcess is 32767 chars.
        // Minecraft JVM args + -cp + main + game args take ~600 chars; we use 16000 as
        // a safe upper bound for the classpath itself before falling back to @argfile.
        // (At ~31K chars total, we have plenty of headroom for other flags.)
        final int COMMAND_LINE_LIMIT = 16000;
        if (classpath.length() <= COMMAND_LINE_LIMIT) {
            return classpath;
        }

        // Build @argfile with ALL paths on ONE line, separated by File.pathSeparator.
        // CRITICAL: Java's @argfile expansion treats each LINE as a separate argument.
        // If we wrote paths on separate lines, -cp would get ONLY the first path as the
        // classpath, and the SECOND path would become the "main class" argument — causing
        // "Error: Could not find or load main class C:...\26.2.jar".
        //
        // Instead, we write all paths as a single classpath string (separated by ; on
        // Windows), so the @argfile expands to ONE argument: the full classpath.
        //
        // We wrap the entire line in quotes and escape embedded quotes/backslashes to
        // handle paths with spaces (e.g. C:\Users\Dan A. Smith\.minecraft\...).
        // Without quoting, the Java argfile parser would split at spaces — breaking -cp.
        //
        // IMPORTANT: Java's @argfile spec requires the file to be UTF-8 encoded.
        // Using plain FileWriter would use platform default (Windows-1252 on Russian
        // Windows), which corrupts non-ASCII chars in user paths (e.g. C:\Users\Данил\).
        // The JVM then sees mojibake paths and silently fails to load Netty/authlib.
        File argFile = File.createTempFile("powerlaunch-cp-", ".txt");
        argFile.deleteOnExit();
        try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                new FileOutputStream(argFile), java.nio.charset.StandardCharsets.UTF_8)) {
            StringBuilder lineBuilder = new StringBuilder();
            for (int i = 0; i < jarPaths.size(); i++) {
                if (i > 0) {
                    lineBuilder.append(File.pathSeparator);
                }
                String abs = new File(jarPaths.get(i)).getAbsolutePath();
                // Escape backslashes and quotes for Java argfile parser
                // (\ → \\, " → \") inside the quoted string
                String escaped = abs.replace("\\", "\\\\").replace("\"", "\\\"");
                lineBuilder.append(escaped);
            }
            // Wrap entire classpath in quotes to protect spaces/special chars
            writer.write("\"");
            writer.write(lineBuilder.toString());
            writer.write("\"\n");
        }
        System.out.println("[PowerLaunch] Classpath too long for command line (" + classpath.length()
            + " chars), using @argfile: " + argFile.getAbsolutePath());
        return "@" + argFile.getAbsolutePath();
    }

    /**
     * Creates a temporary copy of the given jar with Gson classes removed.
     * This prevents embedded Gson in merged/modloader jars from conflicting with
     * the Gson from Minecraft's libraries directory, which can be a different version.
     */
    /**
     * Resolves library paths from version.json's "libraries" section.
     * Parses Maven coordinates (e.g. "org.ow2.asm:asm:9.10.1") and resolves
     * them to actual jar file paths under the libraries directory.
     * Returns only jars that actually exist on disk.
     */
    private List<String> resolveVersionJsonLibraries(String versionJsonPath, File librariesDir) {
        List<String> result = new ArrayList<>();
        File jsonFile = new File(versionJsonPath);
        if (!jsonFile.exists()) return result;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(jsonFile.toPath()));
            // Find all "name" fields in the libraries array
            int libsIdx = content.indexOf("\"libraries\"");
            if (libsIdx < 0) return result;
            int arrStart = content.indexOf('[', libsIdx);
            if (arrStart < 0) return result;
            int arrEnd = findMatchingBracket(content, arrStart);
            if (arrEnd < 0) return result;
            String libsSection = content.substring(arrStart, arrEnd + 1);

            // Parse each library object
            int pos = 0;
            while (pos < libsSection.length()) {
                int nameIdx = libsSection.indexOf("\"name\"", pos);
                if (nameIdx < 0) break;
                int colonIdx = libsSection.indexOf(':', nameIdx);
                if (colonIdx < 0) break;
                int startQ = libsSection.indexOf('"', colonIdx);
                if (startQ < 0) break;
                int endQ = libsSection.indexOf('"', startQ + 1);
                if (endQ < 0) break;
                String mavenName = libsSection.substring(startQ + 1, endQ);
                pos = endQ + 1;

                // Resolve Maven name to file path
                // Format: groupId:artifactId:version[:classifier]
                String[] parts = mavenName.split(":");
                if (parts.length < 3) continue;
                String groupId = parts[0];
                String artifactId = parts[1];
                String version = parts[2];
                String classifier = (parts.length >= 4) ? parts[3] : null;

                // Convert groupId dots to path separators
                String groupPath = groupId.replace('.', '/');
                // Maven layout: groupPath/artifactId/version/artifactId-version[-classifier].jar
                String fileName = artifactId + "-" + version;
                if (classifier != null && !classifier.isEmpty()) {
                    fileName += "-" + classifier;
                }
                fileName += ".jar";
                String jarPath = groupPath + "/" + artifactId + "/" + version + "/" + fileName;
                File jarFile = new File(librariesDir, jarPath);
                if (jarFile.exists()) {
                    result.add(jarFile.getAbsolutePath());
                }
            }
        } catch (IOException e) {
            System.err.println("[PowerLaunch] Failed to parse version.json libraries: " + e.getMessage());
        }
        return result;
    }

    /**
     * Finds the position of the matching closing bracket ']' for the opening '[' at startIdx.
     */
    private int findMatchingBracket(String content, int startIdx) {
        int depth = 1;
        boolean inString = false;
        for (int i = startIdx + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private File stripEmbeddedGson(File jar) throws IOException {
        // Check if this jar actually contains Gson classes
        boolean hasGson = false;
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith("com/google/gson/")) {
                    hasGson = true;
                    break;
                }
            }
        }
        if (!hasGson) return jar;

        // Create temp copy without embedded Gson classes
        File tempJar = File.createTempFile("powerlaunch-nogson-", ".jar");
        tempJar.deleteOnExit();

        Manifest mf;
        try (JarFile jf = new JarFile(jar)) {
            mf = jf.getManifest();
            if (mf != null) {
                // Remove Sealed attribute if present to prevent sealing issues
                mf.getMainAttributes().remove(new Attributes.Name("Sealed"));
            }
        }

        byte[] buf = new byte[32768];
        java.lang.Runtime.Version baseVersion = JarFile.baseVersion();
        try (JarOutputStream jos = mf != null
                ? new JarOutputStream(new FileOutputStream(tempJar), mf)
                : new JarOutputStream(new FileOutputStream(tempJar));
             JarFile jf = new JarFile(jar, false, ZipFile.OPEN_READ, baseVersion)) {

            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // Skip Gson classes (embedded Gson in merged jars can conflict with
                // the library version, causing NoSuchMethodError on Java 26+)
                if (name.startsWith("com/google/gson/")) {
                    continue;
                }

                // Skip signature files (they become invalid after modifications)
                if ("META-INF/".equals(name) ||
                    "META-INF/MANIFEST.MF".equals(name) ||
                    name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA") || name.endsWith(".EC"))) {
                    continue;
                }

                JarEntry newEntry = new JarEntry(name);
                newEntry.setMethod(entry.getMethod());
                if (entry.getMethod() == JarEntry.STORED) {
                    newEntry.setSize(entry.getSize());
                    newEntry.setCompressedSize(entry.getCompressedSize());
                    newEntry.setCrc(entry.getCrc());
                }
                newEntry.setTime(entry.getTime());

                jos.putNextEntry(newEntry);
                if (!entry.isDirectory()) {
                    try (InputStream is = jf.getInputStream(entry)) {
                        int read;
                        while ((read = is.read(buf)) >= 0) {
                            jos.write(buf, 0, read);
                        }
                    }
                }
                jos.closeEntry();
            }
        }

        System.out.println("[PowerLaunch] Stripped embedded Gson from " + jar.getName());
        return tempJar;
    }

    /**
     * If the given jar has Sealed: true in its manifest, create a temporary copy
     * without the sealing attribute and return that. Otherwise return the original.
     * This prevents sealing violations on Java 26+ where LWJGL jars conflict
     * with each other over the org.lwjgl package.
     */
    private File stripSealingIfNeeded(File jar) throws IOException {
        // Use baseVersion (Java 8) to disable multi-release JAR redirection.
        // On Java 26+, JarFile automatically redirects to META-INF/versions/26/ entries,
        // so jf.getInputStream(entry) returns the Java-26-specific version instead of the
        // root entry. Writing this to a new jar as a root entry corrupts the class file
        // (VerifyError: Bad type on operand stack).
        java.lang.Runtime.Version baseVersion = JarFile.baseVersion();
        try (JarFile jf = new JarFile(jar, false, ZipFile.OPEN_READ, baseVersion)) {
            Manifest mf = jf.getManifest();
            if (mf == null) return jar;
            String sealed = mf.getMainAttributes().getValue("Sealed");
            if (!"true".equalsIgnoreCase(sealed)) return jar;

            // Create temp copy without Sealed: true
            File tempJar = File.createTempFile("powerlaunch-unsafe-", ".jar");
            tempJar.deleteOnExit();

            mf.getMainAttributes().remove(new Attributes.Name("Sealed"));

            byte[] buf = new byte[32768];
            // Use ZipOutputStream instead of JarOutputStream for precise control over entry copying.
            // JarOutputStream can corrupt STORED entries when re-compressing at different levels.
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempJar))) {
                // Write manifest manually as the first entry (required by Jar spec)
                zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                java.io.ByteArrayOutputStream manifestBytes = new java.io.ByteArrayOutputStream();
                mf.write(manifestBytes);
                zos.write(manifestBytes.toByteArray());
                zos.closeEntry();

                // Create the META-INF/ directory entry
                zos.putNextEntry(new ZipEntry("META-INF/"));
                zos.closeEntry();

                // Copy all other entries, preserving exact compression and attributes
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // Skip manifest (already written), META-INF/ (already written), and signature files
                    if ("META-INF/".equals(name) ||
                        "META-INF/MANIFEST.MF".equals(name) ||
                        name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA") || name.endsWith(".EC"))) {
                        continue;
                    }

                    ZipEntry newEntry = new ZipEntry(name);
                    newEntry.setMethod(entry.getMethod());
                    // Only set compressed size/CRC for STORED entries — ZipOutputStream needs
                    // them to know the entry boundaries without re-compressing.
                    if (entry.getMethod() == ZipEntry.STORED) {
                        newEntry.setSize(entry.getSize());
                        newEntry.setCompressedSize(entry.getCompressedSize());
                        newEntry.setCrc(entry.getCrc());
                        newEntry.setTime(entry.getTime());
                    } else {
                        // For DEFLATED entries, set time but let ZipOutputStream handle compression
                        newEntry.setTime(entry.getTime());
                    }

                    zos.putNextEntry(newEntry);
                    if (!entry.isDirectory()) {
                        try (InputStream is = jf.getInputStream(entry)) {
                            int read;
                            while ((read = is.read(buf)) >= 0) {
                                zos.write(buf, 0, read);
                            }
                        }
                    }
                    zos.closeEntry();
                }
            }
            System.out.println("[PowerLaunch] Stripped Sealed from " + jar.getName());
            return tempJar;
        }
    }

    /**
     * Strips cryptographic signature files (.SF, .DSA, .RSA, .EC) from a jar.
     * This is needed because different jars in the same package may have different
     * signing states (signed vs unsigned), causing Java's ClassLoader to throw
     * SecurityException: "signer information does not match". By stripping ALL
     * signatures, we ensure consistent unsigned state across the classpath.
     *
     * If the jar has no signature files, returns the original jar (no copy needed).
     */
    private static final File SIG_CACHE_DIR = new File(
            System.getProperty("java.io.tmpdir"), "powerlaunch-stripped");

    private File stripSignaturesIfNeeded(File jar) {
        try {
            // --- Fast check: detect if jar is signed by looking for .SF entries ---
            // In signed JARs, META-INF/*.SF files are typically among the first entries
            // (added before class files). JarFile.stream().anyMatch() short-circuits
            // on the first match, making this fast for signed jars.
            // For unsigned jars (< 100 entries typically), iterating all is still fast.
            boolean hasSignatures;
            try (JarFile jf = new JarFile(jar)) {
                hasSignatures = jf.stream()
                    .map(java.util.zip.ZipEntry::getName)
                    .anyMatch(name -> name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")));
            }
            if (!hasSignatures) return jar;

            // --- Cache: check if we already stripped this jar ---
            SIG_CACHE_DIR.mkdirs();
            // Use file length + last modified as a fast cache key (no hash needed)
            String cacheName = jar.getName() + "_" + jar.length() + "_" + jar.lastModified() + "_nosig.jar";
            File cached = new File(SIG_CACHE_DIR, cacheName);
            if (cached.exists() && cached.length() > 0) {
                return cached; // Reuse cached stripped jar
            }

            // --- Slow path: copy jar without signature files ---
            java.lang.Runtime.Version baseVersion = JarFile.baseVersion();
            byte[] buf = new byte[65536];
            try (JarFile jf2 = new JarFile(jar, false, ZipFile.OPEN_READ, baseVersion);
                 java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                     new FileOutputStream(cached))) {

                java.util.Enumeration<JarEntry> e = jf2.entries();
                while (e.hasMoreElements()) {
                    JarEntry entry = e.nextElement();
                    String name = entry.getName();

                    // Skip signature files
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".DSA") ||
                         name.endsWith(".RSA") || name.endsWith(".EC"))) {
                        continue;
                    }

                    JarEntry newEntry = new JarEntry(name);
                    newEntry.setMethod(entry.getMethod());
                    if (entry.getMethod() == JarEntry.STORED) {
                        newEntry.setSize(entry.getSize());
                        newEntry.setCompressedSize(entry.getCompressedSize());
                        newEntry.setCrc(entry.getCrc());
                    }
                    newEntry.setTime(entry.getTime());

                    jos.putNextEntry(newEntry);
                    if (!entry.isDirectory()) {
                        try (InputStream is = jf2.getInputStream(entry)) {
                            int read;
                            while ((read = is.read(buf)) >= 0) {
                                jos.write(buf, 0, read);
                            }
                        }
                    }
                    jos.closeEntry();
                }
            }
            return cached;
        } catch (Exception e) {
            // If we can't read the jar, return it as-is
            return jar;
        }
    }

    /**
     * Removes conflicting LWJGL versions when the game uses LWJGL 3.
     * Different LWJGL versions (both 2.x and different 3.x minors) share the
     * org.lwjgl package but have incompatible classes, causing VerifyError.
     * Keeps only the NEWEST LWJGL version across ALL artifacts.
     *
     * Example: if we have lwjgl-2.9.1, lwjgl-3.2.2, lwjgl-3.3.1, lwjgl-3.4.1
     * and their sub-modules (lwjgl-platform, lwjgl-glfw, lwjgl-tinyfd, etc.),
     * only version 3.4.1 entries are kept.
     */
    private void removeConflictingLWJGL(List<String> jarPaths, File librariesDir) {
        String libPrefix = librariesDir.getAbsolutePath().replace('\\', '/');
        if (!libPrefix.endsWith("/")) libPrefix += "/";

        // First pass: find ALL LWJGL versions present.
        // Use startsWith("org.lwjgl") to catch both LWJGL 3.x (group "org.lwjgl") and
        // LWJGL 2.x (group "org.lwjgl.lwjgl" under org/lwjgl/lwjgl/lwjgl/2.9.1/).
        java.util.Set<String> lwjglVersions = new java.util.HashSet<>();
        for (String path : jarPaths) {
            String normalized = path.replace('\\', '/');
            if (!normalized.startsWith(libPrefix)) continue;
            String relative = normalized.substring(libPrefix.length());
            String[] parts = relative.split("/");
            if (parts.length < 4) continue;
            StringBuilder group = new StringBuilder();
            for (int j = 0; j < parts.length - 3; j++) {
                if (group.length() > 0) group.append('.');
                group.append(parts[j]);
            }
            if (group.toString().startsWith("org.lwjgl")) {
                lwjglVersions.add(parts[parts.length - 2]);
            }
        }

        if (lwjglVersions.size() <= 1) return;

        // Find the newest LWJGL version
        String newestVersion = null;
        for (String v : lwjglVersions) {
            if (newestVersion == null || compareVersions(v, newestVersion) > 0) {
                newestVersion = v;
            }
        }

        System.out.println("[PowerLaunch] Found " + lwjglVersions.size() + " LWJGL versions, keeping v" + newestVersion);

        // Second pass: remove ALL LWJGL entries that are NOT the newest version
        int removed = 0;
        for (int i = jarPaths.size() - 1; i >= 0; i--) {
            String path = jarPaths.get(i);
            String normalized = path.replace('\\', '/');
            if (!normalized.startsWith(libPrefix)) continue;
            String relative = normalized.substring(libPrefix.length());
            String[] parts = relative.split("/");
            if (parts.length < 4) continue;
            StringBuilder group = new StringBuilder();
            for (int j = 0; j < parts.length - 3; j++) {
                if (group.length() > 0) group.append('.');
                group.append(parts[j]);
            }
            if (group.toString().startsWith("org.lwjgl")) {
                String version = parts[parts.length - 2];
                if (!newestVersion.equals(version)) {
                    jarPaths.remove(i);
                    removed++;
                    System.out.println("[PowerLaunch]   Removed LWJGL: " + parts[parts.length - 3] + "-" + version + ".jar");
                }
            }
        }

        if (removed > 0) {
            System.out.println("[PowerLaunch] Removed " + removed + " conflicting LWJGL libraries");
        }
    }

    /**
     * Checks if a jar contains Gson classes (embedded, not as a dependency).
     */
    private boolean jarContainsGson(String path) {
        try (JarFile jf = new JarFile(path)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith("com/google/gson/")) {
                    return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }

    /**
     * Removes fat JARs (*-all.jar, asm-debug-all, etc.) that bundle multiple libraries
     * and cause classpath conflicts (e.g., Gson from NeoForge's AutoRenamingTool-overriding
     * the correct Gson version from libraries). Only removes when separate module jars exist.
     */
    private void removeDuplicateFatJars(List<String> jarPaths) {
        // Phase 1: Remove ASM fat jars (asm-all, asm-debug-all)
        boolean hasSeparateAsm = false;
        List<String> toRemove = new ArrayList<>();
        for (String path : jarPaths) {
            String normalized = path.replace('\\', '/');
            String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase();
            if (fileName.contains("asm-debug-all") || fileName.contains("asm-all-")) {
                toRemove.add(path);
            }
            if (fileName.startsWith("asm-") && !fileName.contains("all") && !fileName.contains("debug")) {
                hasSeparateAsm = true;
            }
        }
        if (hasSeparateAsm && !toRemove.isEmpty()) {
            for (String path : toRemove) {
                jarPaths.remove(path);
                String fileName = path.substring(path.lastIndexOf(File.separatorChar) + 1);
                System.out.println("[PowerLaunch] Removed fat jar (duplicates ASM classes): " + fileName);
            }
        }
        // Phase 2: Remove *-all.jar fat jars that contain embedded Gson (like NeoForge's
        // AutoRenamingTool-all.jar) which can override the correct Gson version from libraries.
        toRemove.clear();
        for (String path : jarPaths) {
            String fileName = path.substring(path.lastIndexOf(File.separatorChar) + 1).toLowerCase();
            if (fileName.endsWith("-all.jar") && jarContainsGson(path)) {
                toRemove.add(path);
            }
        }
        if (!toRemove.isEmpty()) {
            for (String path : toRemove) {
                jarPaths.remove(path);
                String fileName = path.substring(path.lastIndexOf(File.separatorChar) + 1);
                System.out.println("[PowerLaunch] Removed fat jar (embedded Gson): " + fileName);
            }
        }
        // Phase 3: Remove *-unsafe.jar copies that duplicate the same classes from the main jar
        // (e.g., lwjgl-3.4.1-unsafe.jar duplicates classes from lwjgl-3.4.1.jar with different
        // implementations, causing VerifyError when mixed in the same classpath).
        toRemove.clear();
        for (String path : jarPaths) {
            String fileName = path.substring(path.lastIndexOf(File.separatorChar) + 1).toLowerCase();
            if (fileName.contains("-unsafe.jar")) {
                // Only remove if the main (non-unsafe) jar exists
                String mainJarName = fileName.replace("-unsafe", "");
                for (String other : jarPaths) {
                    if (!path.equals(other) && other.toLowerCase().endsWith(mainJarName)) {
                        toRemove.add(path);
                        break;
                    }
                }
            }
        }
        if (!toRemove.isEmpty()) {
            for (String path : toRemove) {
                jarPaths.remove(path);
                String fileName = path.substring(path.lastIndexOf(File.separatorChar) + 1);
                System.out.println("[PowerLaunch] Removed unsafe jar (duplicates classes): " + fileName);
            }
        }
        // Phase 4: Remove old net.java.dev.jna:platform (JNA 3.x, artifactId "platform") when
        // net.java.dev.jna:jna-platform (JNA 5.x, artifactId "jna-platform") exists.
        // These have DIFFERENT artifactIds so the regular dedup doesn't merge them, but they
        // contain the SAME classes with incompatible APIs (e.g., getModules(int) vs getModules()).
        boolean hasJnaPlatform = false;
        for (String path : jarPaths) {
            if (path.replace('\\', '/').contains("jna-platform")) {
                hasJnaPlatform = true;
                break;
            }
        }
        if (hasJnaPlatform) {
            toRemove.clear();
            for (String path : jarPaths) {
                String normalized = path.replace('\\', '/');
                if (normalized.contains("net/java/dev/jna/platform/") &&
                    !normalized.contains("jna-platform")) {
                    toRemove.add(path);
                }
            }
            if (!toRemove.isEmpty()) {
                for (String path : toRemove) {
                    jarPaths.remove(path);
                    String fileName = path.substring(path.lastIndexOf(File.separatorChar) + 1);
                    System.out.println("[PowerLaunch] Removed old JNA platform (jna-platform exists): " + fileName);
                }
            }
        }
        // Phase 5: Remove authlib from alternative launchers when com.mojang:authlib exists.
        // Alternative launchers (by.ely, org.tlauncher, sk.launcher, etc.) bundle their own
        // authlib implementations under DIFFERENT groupIds. The regular dedup only merges
        // artifacts with the same groupId:artifactId, so these shadow jars survive dedup and
        // cause NoSuchMethodError when their older authlib classes (without createFriendsService,
        // etc.) shadow the official Mojang authlib (which has the required methods).
        boolean hasMojangAuthlib = false;
        for (String path : jarPaths) {
            String normalized = path.replace('\\', '/').toLowerCase();
            if (normalized.contains("com/mojang/authlib/")) {
                hasMojangAuthlib = true;
                break;
            }
        }
        if (hasMojangAuthlib) {
            toRemove.clear();
            for (String path : jarPaths) {
                String normalized = path.replace('\\', '/').toLowerCase();
                if (normalized.contains("/authlib/") &&
                    !normalized.contains("/com/mojang/authlib/")) {
                    toRemove.add(path);
                }
            }
            if (!toRemove.isEmpty()) {
                for (String path : toRemove) {
                    jarPaths.remove(path);
                    String fileName = path.substring(path.lastIndexOf(File.separatorChar) + 1);
                    System.out.println("[PowerLaunch] Removed shadow authlib (non-Mojang): " + fileName);
                }
            }
        }
    }

    /**
     * Deduplicates all Maven artifacts in the classpath, keeping only the newest version
     * of each artifact (groupId:artifactId). This prevents:
     * - Fabric's "duplicate fabric loader classes" error
     * - Gson version conflicts (setStrictness missing in older versions)
     * - Any other library version conflicts
     */
    private void deduplicateArtifacts(List<String> jarPaths, File librariesDir) {
        if (jarPaths.isEmpty()) return;

        String libPrefix = librariesDir.getAbsolutePath().replace('\\', '/');
        if (!libPrefix.endsWith("/")) libPrefix += "/";

        // Parse each path into (fullPath, groupId:artifactId, version)
        // Maven layout: .../libraries/<groupPath>/<artifact>/<version>/<artifact>-<version>.jar
        // e.g., .../libraries/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar
        //       → group = "net.fabricmc", artifact = "fabric-loader", version = "0.19.3"
        java.util.Map<String, List<String[]>> artifactVersions = new java.util.HashMap<>();

        for (String path : jarPaths) {
            String normalized = path.replace('\\', '/');

            // Must be under the libraries directory
            if (!normalized.startsWith(libPrefix)) continue;

            String relative = normalized.substring(libPrefix.length());
            String[] parts = relative.split("/");
            // Minimum: group/artifact/version/artifact-version.jar (4 parts)
            if (parts.length < 4) continue;

            String version = parts[parts.length - 2];
            String artifactId = parts[parts.length - 3];

            // Reconstruct groupId from remaining path parts
            StringBuilder groupId = new StringBuilder();
            for (int i = 0; i < parts.length - 3; i++) {
                if (groupId.length() > 0) groupId.append('.');
                groupId.append(parts[i]);
            }
            String key = groupId + ":" + artifactId;

            artifactVersions.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{path, version});
        }

        // For each artifact, find the newest version, then keep ALL entries (including
        // classifiers like natives, unsafe, sources) of that version. Remove only entries
        // whose version is strictly older than the newest.
        int totalRemoved = 0;
        for (java.util.Map.Entry<String, List<String[]>> entry : artifactVersions.entrySet()) {
            List<String[]> versions = entry.getValue();
            if (versions.size() <= 1) continue;

            // Sort by version descending (newest first)
            versions.sort((a, b) -> compareVersions(b[1], a[1]));

            String newestVersion = versions.get(0)[1];

            // Special handling for Gson: Minecraft 26.2 needs setStrictness(Strictness)
            // which was added in Gson 2.10 but REMOVED in Gson 2.12+. If the newest Gson
            // is too new (>= 2.12), skip it and use the previous version instead.
            // This check is based on version string comparison, not jar content inspection.
            String key = entry.getKey();
            String targetVersion = newestVersion;
            if (key.contains(":gson") && isGsonTooNew(targetVersion)) {
                // Find the first version that is NOT too new
                for (String[] v : versions) {
                    if (!isGsonTooNew(v[1])) {
                        targetVersion = v[1];
                        break;
                    }
                }
            }

            // Keep ALL entries of the target version (handles classifiers like natives, unsafe, etc.)
            // Remove entries whose version does NOT match the target (both older AND newer).
            // For non-Gson artifacts, targetVersion = newestVersion, so this is equivalent to
            // removing only older versions (nothing is newer than the newest).
            // For Gson with a skipped-too-new target, this removes both older AND newer versions.
            int removedThis = 0;
            for (int i = versions.size() - 1; i >= 1; i--) {
                if (compareVersions(versions.get(i)[1], targetVersion) != 0) {
                    jarPaths.remove(versions.get(i)[0]);
                    removedThis++;
                }
            }
            totalRemoved += removedThis;

            if (removedThis > 0) {
                int keptCount = 0;
                for (String[] v : versions) {
                    if (compareVersions(v[1], targetVersion) == 0) keptCount++;
                }
                System.out.println("[PowerLaunch] Dedup " + key
                    + ": keeping " + keptCount + " entries (v" + targetVersion + ")"
                    + ", removed " + removedThis + " older(s)");
            }
        }

        if (totalRemoved > 0) {
            System.out.println("[PowerLaunch] Deduplication complete: removed " + totalRemoved + " duplicate entries");
        }
    }

    /**
     * Compares two version strings numerically by their dot-separated components.
     * Returns negative if v1 < v2, positive if v1 > v2, 0 if equal.
     */
    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < parts1.length ? parseIntSafely(parts1[i]) : 0;
            int n2 = i < parts2.length ? parseIntSafely(parts2[i]) : 0;
            if (n1 != n2) return n1 - n2;
        }
        return 0;
    }

    /**
     * Checks if Gson version is >= 2.14.0 (where setStrictness was removed).
     * Minecraft 26.2 needs setStrictness(Strictness) which exists in Gson 2.13.x
     * and earlier, but was REMOVED starting in Gson 2.14.0.
     */
    private boolean isGsonTooNew(String version) {
        String[] parts = version.split("\\.");
        if (parts.length >= 2) {
            int major = parseIntSafely(parts[0]);
            int minor = parseIntSafely(parts[1]);
            return major == 2 && minor >= 14;
        }
        return false;
    }

    private int parseIntSafely(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void collectJars(List<String> jarPaths, File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                collectJars(jarPaths, file);
            } else if (file.getName().endsWith(".jar")) {
                jarPaths.add(file.getAbsolutePath());
            }
        }
    }

    /**
     * Scans the libraries directory for native library JARs (LWJGL, etc.)
     * and extracts .dll/.so/.dylib files into the natives directory.
     * Also sets them to overwrite mode so re-launches get fresh natives.
     */
    private void extractNatives(String librariesDir, String nativesDir) throws IOException {
        File nativesFolder = new File(nativesDir);

        // Clean old natives first to prevent .dll version conflicts between different LWJGL versions
        if (nativesFolder.exists()) {
            deleteDirectoryContents(nativesFolder);
        } else {
            nativesFolder.mkdirs();
        }

        File libsFolder = new File(librariesDir);
        if (!libsFolder.exists()) return;

        // Collect all native JARs (those containing "natives" in the filename)
        List<File> nativeJars = new ArrayList<>();
        findNativeJars(nativeJars, libsFolder);

        if (nativeJars.isEmpty()) {
            System.out.println("[PowerLaunch] No native library JARs found in " + librariesDir);
            return;
        }

        byte[] buf = new byte[32768];
        int extractedCount = 0;

        for (File jarFile : nativeJars) {
            try (JarFile jf = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // Only extract native library files (.dll on Windows, .so on Linux, .dylib on macOS)
                    if (!name.endsWith(".dll") && !name.endsWith(".so") && !name.endsWith(".dylib")) {
                        continue;
                    }

                    if (entry.isDirectory()) continue;

                    // Extract to natives directory, preserving relative path
                    File outFile = new File(nativesFolder, name);
                    outFile.getParentFile().mkdirs();

                    try (InputStream is = jf.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(outFile)) {
                        int read;
                        while ((read = is.read(buf)) >= 0) {
                            fos.write(buf, 0, read);
                        }
                    }
                    extractedCount++;
                }
            }
        }

        System.out.println("[PowerLaunch] Extracted " + extractedCount + " native libraries to " + nativesDir);
    }

    /**
     * Recursively finds JARs that contain native libraries in their filename
     * (e.g., lwjgl-platform-natives-windows.jar, lwjgl-3.2.1-natives-windows.jar).
     */
    private void findNativeJars(List<File> result, File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                findNativeJars(result, file);
            } else if (file.getName().endsWith(".jar")
                    && file.getName().contains("natives")) {
                result.add(file);
            }
        }
    }

    /**
     * Recursively deletes all files and subdirectories inside a directory,
     * without deleting the directory itself.
     */
    private void deleteDirectoryContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectoryContents(file);
            }
            // On Windows, read-only files can't be deleted without setting writable first
            if (!file.canWrite()) {
                file.setWritable(true);
            }
            if (!file.delete()) {
                System.err.println("[PowerLaunch] Warning: Could not delete old native: " + file.getAbsolutePath());
            }
        }
    }

    /**
     * Finds the position of the matching closing brace for the opening brace at startIdx.
     * Handles nested braces and string literals.
     */
    private int findMatchingBrace(String content, int startIdx) {
        int depth = 1;
        boolean inString = false;
        for (int i = startIdx + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Checks if the version.json references LWJGL 3.x libraries.
     * Used to determine classpath sorting order for Java 26+ compatibility.
     */
    private boolean checkForLWJGL3InVersion(String versionJsonPath) {
        File jsonFile = new File(versionJsonPath);
        if (!jsonFile.exists()) return false;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(jsonFile.toPath()));
            // LWJGL 3 artifacts in version.json use Maven coordinates like:
            //   "org.lwjgl:lwjgl-glfw:3.2.1"  (lwjgl-glfw is LWJGL 3 specific)
            //   "org.lwjgl:lwjgl:3.2.1"       (LWJGL 3 has version 3.x)
            //   "org/lwjgl/lwjgl/3.2.1/"     (Maven directory path)
            return content.contains("lwjgl-glfw")
                || content.contains(":lwjgl:3.")
                || content.contains("lwjgl/3.");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads the mainClass field from version.json.
     * Fabric, Forge, NeoForge, etc. use custom main classes (e.g., KnotClient)
     * instead of the vanilla net.minecraft.client.main.Main.
     */
    /**
     * Reads the required Java major version from version.json.
     * E.g., for Minecraft 26.x it returns 26, for 1.21.x it returns 21.
     * Returns -1 if not found.
     */
    private int getRequiredJavaVersion(String gameDir, String version) {
        File versionJson = new File(gameDir + File.separator + "versions"
                + File.separator + version + File.separator + version + ".json");
        if (versionJson.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(versionJson.toPath()));
                // Look for "javaVersion" object with "majorVersion" field
                int javaVersionIdx = content.indexOf("\"javaVersion\"");
                if (javaVersionIdx >= 0) {
                    int objStart = content.indexOf('{', javaVersionIdx);
                    if (objStart >= 0 && objStart < javaVersionIdx + 100) {
                        int majorIdx = content.indexOf("\"majorVersion\"", objStart);
                        if (majorIdx >= 0) {
                            int colonIdx = content.indexOf(':', majorIdx);
                            if (colonIdx >= 0) {
                                // Read the number after the colon
                                int numStart = colonIdx + 1;
                                while (numStart < content.length() && content.charAt(numStart) == ' ') numStart++;
                                int numEnd = numStart;
                                while (numEnd < content.length() && Character.isDigit(content.charAt(numEnd))) numEnd++;
                                if (numEnd > numStart) {
                                    int ver = Integer.parseInt(content.substring(numStart, numEnd));
                                    System.out.println("[PowerLaunch] version.json requires Java " + ver + "+");
                                    return ver;
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[PowerLaunch] Failed to read version JSON for javaVersion: " + e.getMessage());
            }
        }
        return -1;
    }

    private String getMainClass(String gameDir, String version) {
        File versionJson = new File(gameDir + File.separator + "versions"
                + File.separator + version + File.separator + version + ".json");
        if (versionJson.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(versionJson.toPath()));
                int mainClassIdx = content.indexOf("\"mainClass\"");
                if (mainClassIdx >= 0) {
                    int colonIdx = content.indexOf(':', mainClassIdx);
                    if (colonIdx >= 0) {
                        int startQuote = content.indexOf('"', colonIdx);
                        if (startQuote >= 0) {
                            int endQuote = content.indexOf('"', startQuote + 1);
                            if (endQuote > startQuote) {
                                String mc = content.substring(startQuote + 1, endQuote);
                                System.out.println("[PowerLaunch] Using mainClass: " + mc);
                                return mc;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[PowerLaunch] Failed to read version JSON for mainClass: " + e.getMessage());
            }
        }
        // Fallback: vanilla Minecraft main class
        return "net.minecraft.client.main.Main";
    }

    /**
     * Reads the "jar" field from version.json.
     * Used by Fabric, Forge, etc. to reference the underlying vanilla Minecraft version jar.
     * Returns null if not present.
     */
    private String getJarReference(String gameDir, String version) {
        File versionJson = new File(gameDir + File.separator + "versions"
                + File.separator + version + File.separator + version + ".json");
        if (versionJson.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(versionJson.toPath()));
                // Look for "jar": "<version>" - the field is typically near the top
                int jarIdx = content.indexOf("\"jar\"");
                if (jarIdx >= 0) {
                    int colonIdx = content.indexOf(':', jarIdx);
                    if (colonIdx >= 0) {
                        int startQuote = content.indexOf('"', colonIdx);
                        if (startQuote >= 0) {
                            int endQuote = content.indexOf('"', startQuote + 1);
                            if (endQuote > startQuote) {
                                return content.substring(startQuote + 1, endQuote);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[PowerLaunch] Failed to read version JSON for jar reference: " + e.getMessage());
            }
        }
        return null;
    }

    private String getAssetIndex(String gameDir, String version) {
        // Read the version JSON to determine the actual asset index
        File versionJson = new File(gameDir + File.separator + "versions"
                + File.separator + version + File.separator + version + ".json");
        if (versionJson.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(versionJson.toPath()));
                // Look for "assets": "index_id" in the JSON
                int assetsIdx = content.indexOf("\"assets\"");
                if (assetsIdx >= 0) {
                    int colonIdx = content.indexOf(':', assetsIdx);
                    if (colonIdx >= 0) {
                        int startQuote = content.indexOf('"', colonIdx);
                        if (startQuote >= 0) {
                            int endQuote = content.indexOf('"', startQuote + 1);
                            if (endQuote > startQuote) {
                                return content.substring(startQuote + 1, endQuote);
                            }
                        }
                    }
                }
                // Also check for "assetIndex": { "id": "..." } (newer format)
                // Find the opening brace after "assetIndex" to scope the search
                int assetIndexIdx = content.indexOf("\"assetIndex\"");
                if (assetIndexIdx >= 0) {
                    int objStart = content.indexOf('{', assetIndexIdx);
                    if (objStart >= 0 && objStart < assetIndexIdx + 200) {
                        int objEnd = findMatchingBrace(content, objStart);
                        if (objEnd > objStart) {
                            int idIdx = content.indexOf("\"id\"", objStart);
                            if (idIdx >= 0 && idIdx < objEnd) {
                                int colonIdx = content.indexOf(':', idIdx);
                                if (colonIdx >= 0) {
                                    int startQuote = content.indexOf('"', colonIdx);
                                    if (startQuote >= 0) {
                                        int endQuote = content.indexOf('"', startQuote + 1);
                                        if (endQuote > startQuote) {
                                            return content.substring(startQuote + 1, endQuote);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[PowerLaunch] Failed to read version JSON for asset index: " + e.getMessage());
            }
        }
        // Fallback: use version name as asset index
        return version;
    }

    public boolean isRunning() {
        return running;
    }

    public void setOnConsoleLine(Consumer<String> listener) {
        this.onConsoleLine = listener;
    }

    public List<String> getConsoleLog() {
        synchronized (consoleLog) {
            return new ArrayList<>(consoleLog);
        }
    }

    public long getProcessPid() {
        if (minecraftProcess != null && minecraftProcess.isAlive()) {
            return minecraftProcess.pid();
        }
        return -1;
    }

    public String getGameDir() {
        return getGameDirectory();
    }

    public void stopMinecraft() {
        if (minecraftProcess != null && minecraftProcess.isAlive()) {
            minecraftProcess.destroy();
            try {
                minecraftProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            if (minecraftProcess.isAlive()) {
                minecraftProcess.destroyForcibly();
            }
            running = false;
            minecraftProcess = null;
        }
    }
}
