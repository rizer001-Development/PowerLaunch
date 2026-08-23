package com.powerlaunch.installer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

public class InstallerMain extends Application {

    private static final String APP_NAME = "PowerLaunch";
    private static final String JAR_NAME = "PowerLaunch-1.0.0.jar";
    private static final String LAUNCHER_MAIN_CLASS = "com.powerlaunch.Main";
    private static final String DEFAULT_INSTALL_DIR = "C:\\PowerLaunch";

    private Stage stage;
    private StackPane root;
    private Path selectedPath = Paths.get(DEFAULT_INSTALL_DIR);
    private Path thisJarPath;
    private boolean isInstalled = false;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.root = new StackPane();
        root.setStyle("-fx-background-color: #1a1a2e;");

        // Find THIS jar (self-contained — contains all classes)
        findThisJar();

        // Check if launcher is already installed
        checkInstallationStatus();

        Scene scene = new Scene(root, 600, 480);
        stage.setTitle("PowerLaunch Installer");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    private void findThisJar() {
        // Method 0: getResource() — MOST RELIABLE. Работает всегда, когда класс загружен из JAR.
        // Parse jar:file:/path/to/jar!/com/...class → /path/to/jar
        try {
            URL resource = InstallerMain.class.getResource("InstallerMain.class");
            if (resource != null && "jar".equals(resource.getProtocol())) {
                String path = resource.getPath();
                int idx = path.indexOf("!/");
                if (idx > 0) {
                    String jarUrl = path.substring(0, idx);
                    Path p = Paths.get(new URI(jarUrl));
                    if (!Files.isDirectory(p) && p.toString().endsWith(".jar")) {
                        thisJarPath = p;
                        System.err.println("Found JAR via getResource: " + p);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Method 0 (getResource) failed: " + e.getMessage());
        }

        // Method 1: getCodeSource() — works in app-image
        try {
            URL location = InstallerMain.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path p = Paths.get(location.toURI());
                if (!Files.isDirectory(p) && p.toString().endsWith(".jar")) {
                    thisJarPath = p;
                    System.err.println("Found JAR via getCodeSource: " + p);
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("Method 1 (getCodeSource) failed: " + e.getMessage());
        }

        // Method 2: java.class.path — parse classpath, look for .jar
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isEmpty()) {
            for (String entry : classPath.split(File.pathSeparator)) {
                Path p = Paths.get(entry);
                if (p.toString().endsWith(".jar") && Files.exists(p)) {
                    thisJarPath = p;
                    System.err.println("Found JAR via java.class.path: " + p);
                    return;
                }
            }
        }

        // Method 3: search user.dir and standard dev paths
        String userDir = System.getProperty("user.dir", "");
        String[] patterns = {
            "build/libs/PowerLaunchInstaller-1.0.0.jar",
            "build/libs/PowerLaunch-1.0.0.jar",
            "PowerLaunchInstaller-1.0.0.jar",
            "../build/libs/PowerLaunchInstaller-1.0.0.jar",
            "../build/libs/PowerLaunch-1.0.0.jar"
        };
        if (!userDir.isEmpty()) {
            for (String pattern : patterns) {
                try {
                    Path p = Paths.get(userDir).resolve(pattern).normalize();
                    if (Files.exists(p)) {
                        thisJarPath = p;
                        System.err.println("Found JAR via user.dir: " + p);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Method 4: powerlaunch.home (from jpackage -Dpowerlaunch.home=...)
        String home = System.getProperty("powerlaunch.home", "");
        if (!home.isEmpty()) {
            String[] homePatterns = {
                "build/libs/PowerLaunchInstaller-1.0.0.jar",
                "build/libs/PowerLaunch-1.0.0.jar"
            };
            for (String pattern : homePatterns) {
                try {
                    Path p = Paths.get(home).resolve(pattern).normalize();
                    if (Files.exists(p)) {
                        thisJarPath = p;
                        System.err.println("Found JAR via powerlaunch.home: " + p);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Method 5: search for any .jar with PowerLaunch in name nearby
        if (!userDir.isEmpty()) {
            try {
                Path base = Paths.get(userDir);
                Files.walk(base, 6)
                    .filter(f -> f.toString().endsWith(".jar")
                            && f.getFileName().toString().contains("PowerLaunch")
                            && !f.toString().contains(".gradle")
                            && !f.toString().contains("caches"))
                    .findFirst()
                    .ifPresent(f -> {
                        thisJarPath = f;
                        System.err.println("Found JAR via filesystem scan: " + f);
                    });
            } catch (Exception ignored) {}
        }

        // If all 6 methods failed, thisJarPath stays null.
        // performInstall() will handle this by creating a JAR from the classpath at runtime.
    }

    private void checkInstallationStatus() {
        Path launcherJar = selectedPath.resolve(JAR_NAME);
        isInstalled = Files.exists(launcherJar);
        showMainScreen();
    }

    private void showMainScreen() {
        VBox mainBox = new VBox(20);
        mainBox.setAlignment(Pos.CENTER);
        mainBox.setPadding(new Insets(40));

        Text title = new Text("⚡ PowerLaunch");
        title.setFont(Font.font("System", FontWeight.BOLD, 32));
        title.setFill(Color.WHITE);

        Text subtitle = new Text("Minecraft Launcher Installer");
        subtitle.setFont(Font.font("System", 14));
        subtitle.setFill(Color.rgb(180, 180, 200));

        VBox titleBox = new VBox(5, title, subtitle);
        titleBox.setAlignment(Pos.CENTER);

        // Status card
        VBox statusCard = new VBox(15);
        statusCard.setMaxWidth(450);
        statusCard.setPadding(new Insets(25));
        statusCard.setStyle(
                "-fx-background-color: rgba(255,255,255,0.05);" +
                "-fx-background-radius: 16;" +
                "-fx-border-color: rgba(255,255,255,0.08);" +
                "-fx-border-radius: 16;"
        );

        Text statusText = new Text(isInstalled
                ? "✓ PowerLaunch installed"
                : "PowerLaunch is not installed");
        statusText.setFont(Font.font("System", FontWeight.BOLD, 18));
        statusText.setFill(isInstalled ? Color.web("#4ade80") : Color.rgb(200, 200, 200));

        Text pathText = new Text("Path: " + selectedPath.toAbsolutePath());
        pathText.setFont(Font.font("System", 12));
        pathText.setFill(Color.rgb(150, 150, 180));

        statusCard.getChildren().addAll(statusText, pathText);

        // Buttons
        VBox buttonBox = new VBox(12);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));

        if (isInstalled) {
            Button launchBtn = createButton("🚀 Launch PowerLaunch", "#10b981", e -> launchLauncher());
            Button reinstallBtn = createButton("🔄 Reinstall", "#3b82f6", e -> showInstallScreen());
            Button changePathBtn = createButton("📁 Change Path", "rgba(255,255,255,0.08)", e -> chooseDirectory());
            buttonBox.getChildren().addAll(launchBtn, reinstallBtn, changePathBtn);
        } else {
            Button installBtn = createButton("📥 Install", "#e94560", e -> showInstallScreen());
            Button changePathBtn = createButton("📁 Choose Folder", "rgba(255,255,255,0.08)", e -> chooseDirectory());
            buttonBox.getChildren().addAll(installBtn, changePathBtn);
        }

        Button exitBtn = createButton("✕ Exit", "rgba(255,255,255,0.05)", e -> stage.close());
        exitBtn.setTextFill(Color.rgb(150, 150, 180));

        mainBox.getChildren().addAll(titleBox, statusCard, buttonBox, exitBtn);
        root.getChildren().setAll(mainBox);
    }

    private void showInstallScreen() {
        VBox installBox = new VBox(20);
        installBox.setAlignment(Pos.CENTER);
        installBox.setPadding(new Insets(40));

        Text title = new Text("📥 PowerLaunch Installer");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setFill(Color.WHITE);

        // Directory selection
        VBox dirCard = new VBox(10);
        dirCard.setMaxWidth(480);
        dirCard.setPadding(new Insets(20));
        dirCard.setStyle(
                "-fx-background-color: rgba(255,255,255,0.05);" +
                "-fx-background-radius: 12;" +
                "-fx-border-color: rgba(255,255,255,0.08);" +
                "-fx-border-radius: 12;"
        );

        Text dirLabel = new Text("Install Directory:");
        dirLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        dirLabel.setFill(Color.WHITE);

        HBox dirRow = new HBox(10);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        TextField dirField = new TextField(selectedPath.toAbsolutePath().toString());
        dirField.setPrefWidth(370);
        dirField.setEditable(false);
        dirField.setStyle(
                "-fx-background-color: rgba(255,255,255,0.08);" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 8;" +
                "-fx-padding: 8 12;"
        );

        Button browseBtn = new Button("📁");
        browseBtn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.1);" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;"
        );

        Label dirStatus = new Label();
        dirStatus.setFont(Font.font("System", 12));

        Runnable checkDir = () -> {
            try {
                if (Files.exists(selectedPath)) {
                    boolean hasOtherFiles = false;
                    if (Files.isDirectory(selectedPath)) {
                        try (var stream = Files.list(selectedPath)) {
                            hasOtherFiles = stream.anyMatch(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return !name.contains("powerlaunch") && !name.equals(JAR_NAME.toLowerCase()) && !name.equals("runtime") && !name.endsWith(".bat");
                            });
                        }
                    }
                    if (hasOtherFiles) {
                        dirStatus.setText("⚠ This folder contains other files. Please choose another.");
                        dirStatus.setTextFill(Color.web("#ef4444"));
                    } else {
                        dirStatus.setText("✓ Folder is ready for installation");
                        dirStatus.setTextFill(Color.web("#4ade80"));
                    }
                } else {
                    dirStatus.setText("✓ Folder will be created");
                    dirStatus.setTextFill(Color.web("#3b82f6"));
                }
            } catch (IOException ex) {
                dirStatus.setText("✗ Failed to check folder");
                dirStatus.setTextFill(Color.web("#ef4444"));
            }
        };
        checkDir.run();

        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Choose install directory");
            dc.setInitialDirectory(selectedPath.toFile());
            File dir = dc.showDialog(stage);
            if (dir != null) {
                selectedPath = dir.toPath();
                dirField.setText(selectedPath.toAbsolutePath().toString());
                checkDir.run();
            }
        });

        dirRow.getChildren().addAll(dirField, browseBtn);
        dirCard.getChildren().addAll(dirLabel, dirRow, dirStatus);

        // Options card
        VBox optionsCard = new VBox(12);
        optionsCard.setMaxWidth(480);
        optionsCard.setPadding(new Insets(20));
        optionsCard.setStyle(
                "-fx-background-color: rgba(255,255,255,0.05);" +
                "-fx-background-radius: 12;" +
                "-fx-border-color: rgba(255,255,255,0.08);" +
                "-fx-border-radius: 12;"
        );

        Text optionsLabel = new Text("Options:");
        optionsLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        optionsLabel.setFill(Color.WHITE);

        CheckBox shortcutDesktop = new CheckBox("Create desktop shortcut");
        shortcutDesktop.setSelected(true);
        shortcutDesktop.setTextFill(Color.rgb(200, 200, 220));
        shortcutDesktop.setStyle("-fx-font-size: 13;");

        CheckBox shortcutStartMenu = new CheckBox("Add to Start Menu");
        shortcutStartMenu.setSelected(true);
        shortcutStartMenu.setTextFill(Color.rgb(200, 200, 220));
        shortcutStartMenu.setStyle("-fx-font-size: 13;");

        CheckBox launchAfterInstall = new CheckBox("Launch PowerLaunch after install");
        launchAfterInstall.setSelected(true);
        launchAfterInstall.setTextFill(Color.rgb(200, 200, 220));
        launchAfterInstall.setStyle("-fx-font-size: 13;");

        optionsCard.getChildren().addAll(optionsLabel, shortcutDesktop, shortcutStartMenu, launchAfterInstall);

        // Install button
        Button installBtn = createButton("✅ Install", "#10b981", e -> {
            try {
                performInstall(selectedPath, shortcutDesktop.isSelected(), shortcutStartMenu.isSelected(), launchAfterInstall.isSelected());
            } catch (Exception ex) {
                showError("Installation error: " + ex.getMessage());
            }
        });

        Button backBtn = createButton("◀ Back", "rgba(255,255,255,0.08)", e -> showMainScreen());
        backBtn.setTextFill(Color.rgb(200, 200, 220));

        installBox.getChildren().addAll(title, dirCard, optionsCard, installBtn, backBtn);
        root.getChildren().setAll(installBox);
    }

    private void performInstall(Path targetPath,
                                boolean createDesktopShortcut,
                                boolean createStartMenuShortcut,
                                boolean launchAfter) throws IOException {
        // Create directory if needed
        Files.createDirectories(targetPath);

        // Check for other files
        boolean hasOtherFiles = false;
        try (var stream = Files.list(targetPath)) {
            hasOtherFiles = stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return !name.contains("powerlaunch") && !name.equals(JAR_NAME.toLowerCase()) && !name.equals("runtime") && !name.endsWith(".bat");
            });
        }

        if (hasOtherFiles) {
            showError("В выбранной папке есть другие файлы.\r\nВыберите другую директорию.");
            return;
        }

        // Copy THIS jar to target (it contains all classes: installer + launcher)
        Path sourceJar = thisJarPath;
        Path targetJar = targetPath.resolve(JAR_NAME);

        if (sourceJar != null && Files.exists(sourceJar)) {
            // JAR found — copy as-is
            Files.copy(sourceJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
        } else {
            // JAR not found — create new from classpath
            createJarFromClasspath(targetJar);
        }

        // Copy runtime (Java + JavaFX) to installation directory
        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isEmpty()) {
            Path runtimeSource = Paths.get(javaHome).getParent();
            if (runtimeSource != null && Files.exists(runtimeSource.resolve("bin").resolve("javaw.exe"))) {
                Path runtimeTarget = targetPath.resolve("runtime");
                copyDirectory(runtimeSource, runtimeTarget);
            }
        }

        // Create launch BAT with logging
        Path launchBat = targetPath.resolve("Launch PowerLaunch.bat");
        String batContent =
                "@echo off\r\n" +
                "title PowerLaunch Launcher\r\n" +
                "cd /d \"%~dp0\"\r\n" +
                "\r\n" +
                "set \"LOG=%~dp0launcher.log\"\r\n" +
                "echo [%date% %time%] ===== PowerLaunch Launcher ===== > \"%LOG%\"\r\n" +
                "\r\n" +
                "REM ---- Find Java ----\r\n" +
                "\r\n" +
                "REM 1) Bundled runtime/ next to this file\r\n" +
                "echo [%date% %time%] Looking for bundled runtime... >> \"%LOG%\"\r\n" +
                "if exist \"runtime\\bin\\javaw.exe\" (\r\n" +
                "  set JAVA_EXE=\"runtime\\bin\\javaw.exe\"\r\n" +
                "  echo [%date% %time%] Found: runtime\\bin\\javaw.exe >> \"%LOG%\"\r\n" +
                "  goto :run\r\n" +
                ")\r\n" +
                "if exist \"runtime\\bin\\java.exe\" (\r\n" +
                "  set JAVA_EXE=\"runtime\\bin\\java.exe\"\r\n" +
                "  echo [%date% %time%] Found: runtime\\bin\\java.exe >> \"%LOG%\"\r\n" +
                "  goto :run\r\n" +
                ")\r\n" +
                "\r\n" +
                "REM 2) Temp runtime from C# launcher\r\n" +
                "echo [%date% %time%] Looking for temp runtime... >> \"%LOG%\"\r\n" +
                "if exist \"%TEMP%\\PowerLaunch\\runtime\\bin\\javaw.exe\" (\r\n" +
                "  set JAVA_EXE=\"%TEMP%\\PowerLaunch\\runtime\\bin\\javaw.exe\"\r\n" +
                "  echo [%date% %time%] Found: %TEMP%\\PowerLaunch\\runtime\\bin\\javaw.exe >> \"%LOG%\"\r\n" +
                "  goto :run\r\n" +
                ")\r\n" +
                "if exist \"%TEMP%\\PowerLaunch\\runtime\\bin\\java.exe\" (\r\n" +
                "  set JAVA_EXE=\"%TEMP%\\PowerLaunch\\runtime\\bin\\java.exe\"\r\n" +
                "  echo [%date% %time%] Found: %TEMP%\\PowerLaunch\\runtime\\bin\\java.exe >> \"%LOG%\"\r\n" +
                "  goto :run\r\n" +
                ")\r\n" +
                "\r\n" +
                "REM ---- Java not found ----\r\n" +
                "echo [%date% %time%] ERROR: Java with JavaFX not found >> \"%LOG%\"\r\n" +
                "echo.\r\n" +
                "echo ============================================\r\n" +
                "echo   Java with JavaFX not found!\r\n" +
                "echo ============================================\r\n" +
                "echo.\r\n" +
                "echo PowerLaunch requires Java with JavaFX.\r\n" +
                "echo Details: %LOG%\r\n" +
                "echo.\r\n" +
                "echo Options:\r\n" +
                "echo 1. Run PowerLaunch Setup.exe once\r\n" +
                "echo 2. Copy the runtime/ folder next to the .bat\r\n" +
                "echo.\r\n" +
                "echo Press any key...\r\n" +
                "pause >nul\r\n" +
                "exit /b 1\r\n" +
                "\r\n" +
                ":run\r\n" +
                "echo [%date% %time%] Starting: %JAVA_EXE% >> \"%LOG%\"\r\n" +
                "echo [%date% %time%] Args: --enable-native-access=ALL-UNNAMED -cp " + JAR_NAME + " " + LAUNCHER_MAIN_CLASS + " >> \"%LOG%\"\r\n" +
                "\r\n" +
                "REM Use java.exe (with console) and redirect errors to log\r\n" +
                "%JAVA_EXE% --enable-native-access=ALL-UNNAMED -cp \"" + JAR_NAME + "\" " + LAUNCHER_MAIN_CLASS + " >> \"%LOG%\" 2>&1\r\n" +
                "\r\n" +
                "echo [%date% %time%] Java exited with code: %ERRORLEVEL% >> \"%LOG%\"\r\n" +
                "echo.\r\n" +
                "pause\r\n";
        Files.writeString(launchBat, batContent);

        // Create desktop shortcut
        if (createDesktopShortcut) {
            createShortcut(targetPath, false);
        }

        // Create start menu shortcut
        if (createStartMenuShortcut) {
            createShortcut(targetPath, true);
        }

        // Show success
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Installation Complete");
            alert.setHeaderText("✅ PowerLaunch installed!");
            alert.setContentText("Launcher installed to:\r\n" + targetPath.toAbsolutePath());
            styleAlert(alert);
            alert.showAndWait();
        });

        // Update state
        selectedPath = targetPath;
        isInstalled = true;

        // Launch if requested
        if (launchAfter) {
            launchInstalled(targetPath);
        } else {
            showMainScreen();
        }
    }

    private void createShortcut(Path installPath, boolean startMenu) throws IOException {
        String desktopPath = System.getProperty("user.home") + "\\Desktop";
        String startMenuPath = System.getProperty("user.home")
                + "\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\PowerLaunch";

        String shortcutDir = startMenu ? startMenuPath : desktopPath;
        String shortcutName = shortcutDir + "\\PowerLaunch.lnk";

        // Ensure target directory exists
        Files.createDirectories(Paths.get(shortcutDir));

        // Delete old shortcut if exists
        Files.deleteIfExists(Paths.get(shortcutName));

        // Create VBS script to create the shortcut
        String vbsContent =
                "Set WShell = CreateObject(\"WScript.Shell\")\r\n" +
                "Set Shortcut = WShell.CreateShortcut(\"" + shortcutName.replace("\\", "\\\\") + "\")\r\n" +
                "Shortcut.TargetPath = \"" + installPath.resolve("Launch PowerLaunch.bat").toString().replace("\\", "\\\\") + "\"\r\n" +
                "Shortcut.WorkingDirectory = \"" + installPath.toString().replace("\\", "\\\\") + "\"\r\n" +
                "Shortcut.Description = \"PowerLaunch Minecraft Launcher\"\r\n" +
                "Shortcut.Save\r\n";

        Path vbsPath = Files.createTempFile("create_shortcut_", ".vbs");
        Files.writeString(vbsPath, vbsContent);

        try {
            ProcessBuilder pb = new ProcessBuilder("wscript.exe", vbsPath.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        // Clean up VBS
        try { Files.deleteIfExists(vbsPath); } catch (IOException ignored) {}
    }

    private void launchInstalled(Path installPath) {
        Path jarPath = installPath.resolve(JAR_NAME);

        if (!Files.exists(jarPath)) {
            showError("Launcher file not found: " + jarPath);
            return;
        }

        try {
            String javaBin = getJavaBinPath();

            // Launch with explicit main class (Main, not InstallerMain)
            // Uses -cp instead of -jar because the JAR's manifest Main-Class is InstallerMain
            ProcessBuilder pb = new ProcessBuilder(
                    javaBin,
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp", jarPath.toAbsolutePath().toString(),
                    LAUNCHER_MAIN_CLASS
            );
            pb.directory(installPath.toFile());
            pb.start();

            Platform.runLater(() -> {
                stage.close();
                Platform.exit();
            });
        } catch (IOException e) {
            showError("Failed to launch launcher: " + e.getMessage());
        }
    }

    private void createJarFromClasspath(Path targetJar) throws IOException {
        System.err.println("Creating JAR from classpath at: " + targetJar);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(targetJar.toFile()))) {
            // Add MANIFEST.MF
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            String manifest = "Manifest-Version: 1.0\r\n" +
                              "Main-Class: " + LAUNCHER_MAIN_CLASS + "\r\n" +
                              "Created-By: PowerLaunch Installer\r\n\r\n";
            jos.write(manifest.getBytes("UTF-8"));
            jos.closeEntry();

            // Walk all classpath entries for class files + resources
            String classPath = System.getProperty("java.class.path", "");
            if (!classPath.isEmpty()) {
                for (String entry : classPath.split(File.pathSeparator)) {
                    Path p = Paths.get(entry);
                    if (!Files.exists(p)) continue;

                    if (Files.isDirectory(p)) {
                        // Copy .class, .properties, .css, .fxml, .png, .jpg etc.
                        Files.walk(p)
                            .filter(f -> Files.isRegularFile(f)
                                    && (f.toString().endsWith(".class")
                                        || f.toString().endsWith(".properties")
                                        || f.toString().endsWith(".css")
                                        || f.toString().endsWith(".fxml")
                                        || f.toString().endsWith(".png")
                                        || f.toString().endsWith(".jpg")))
                            .forEach(f -> {
                                try {
                                    String entryName = p.relativize(f).toString().replace('\\', '/');
                                    jos.putNextEntry(new JarEntry(entryName));
                                    Files.copy(f, jos);
                                    jos.closeEntry();
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                    } else if (p.toString().endsWith(".jar") && Files.exists(p)) {
                        // Only from our JARs, not from JDK
                        if (!p.getFileName().toString().contains("PowerLaunch")) continue;
                        // Copy entries from JAR files
                        try (JarInputStream jis = new JarInputStream(new FileInputStream(p.toFile()))) {
                            JarEntry jarIn;
                            while ((jarIn = jis.getNextJarEntry()) != null) {
                                if (jarIn.isDirectory()) continue;
                                // Exclude signatures — they won't match in the new JAR
                                String name = jarIn.getName();
                                if (name.startsWith("META-INF/") &&
                                    (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA")))
                                    continue;
                                jos.putNextEntry(new JarEntry(name));
                                byte[] buf = new byte[8192];
                                int read;
                                while ((read = jis.read(buf)) > 0) {
                                    jos.write(buf, 0, read);
                                }
                                jos.closeEntry();
                            }
                        }
                    }
                }
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void launchLauncher() {
        launchInstalled(selectedPath);
    }

    private String getJavaBinPath() {
        String javaHome = System.getProperty("java.home");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return javaHome + File.separator + "bin" + File.separator + "javaw.exe";
        }
        return javaHome + File.separator + "bin" + File.separator + "java";
    }

    private void chooseDirectory() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose install directory");
        dc.setInitialDirectory(selectedPath.toFile());
        File dir = dc.showDialog(stage);
        if (dir != null) {
            selectedPath = dir.toPath();
            checkInstallationStatus();
        }
    }

    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("⚠ An error occurred");
            alert.setContentText(message);
            styleAlert(alert);
            alert.showAndWait();
        });
    }

    private void styleAlert(Alert alert) {
        DialogPane dp = alert.getDialogPane();
        dp.setStyle(
                "-fx-background-color: #1a1a2e;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 12;" +
                "-fx-background-radius: 12;"
        );
        Scene scene = dp.getScene();
        if (scene != null) scene.setFill(Color.valueOf("#1a1a2e"));
        var header = dp.lookup(".header-panel");
        if (header != null) header.setStyle("-fx-background-color: transparent;");
    }

    private Button createButton(String text, String color, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button btn = new Button(text);
        btn.setMaxWidth(380);
        btn.setPrefHeight(44);
        btn.setStyle(
                "-fx-background-color: " + color + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 14;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: derive(" + color + ", 20%);" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 14;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-cursor: hand;"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: " + color + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 14;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-cursor: hand;"
        ));
        btn.setOnAction(handler);
        return btn;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
