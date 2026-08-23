package com.powerlaunch;

import com.powerlaunch.settings.SettingsManager;
import com.powerlaunch.tabs.TabDatabase;
import com.powerlaunch.tabs.TabManager;
import com.powerlaunch.utils.FileLogManager;
import com.powerlaunch.utils.NetworkDiagnostics;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Main extends Application {
    private static Stage primaryStage;
    private static StackPane rootContainer;
    private static volatile boolean isShuttingDown = false;

    @Override
    public void start(Stage stage) {
        // 1. IPv4 only: Java prefers IPv6 by default. On many systems DNS
        //    responds with AAAA (IPv6), but there's no real IPv6 routing to servers → timeout.
        System.setProperty("java.net.preferIPv4Stack", "true");

        // 2. We explicitly do NOT enable java.net.useSystemProxies! On Windows without
        //    a proxy this flag can read garbage from the registry and redirect traffic
        //    to nowhere. Java HttpClient works fine without a proxy.
        //    If you need a proxy — set it in Windows/IE settings, HttpClient
        //    will automatically pick them up via ProxySelector.getDefault().

        primaryStage = stage;
        rootContainer = new StackPane();

        // Test server connectivity (outputs diagnostics to console)
        NetworkDiagnostics.runAllTests();

        // Initialize DB (will be opened in TabManager.getInstance())
        SettingsManager.getInstance().load();

        // Register shutdown hook to guarantee all data is saved on exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveAllData();
        }));

        showMainScreen();

        Scene scene = new Scene(rootContainer, 1200, 800);
        var cssUrl = getClass().getResource("/com/powerlaunch/gui/styles/main.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle("PowerLaunch");
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.centerOnScreen();
        stage.setResizable(true);

        // Window close handler — save all before exit
        stage.setOnCloseRequest(e -> {
            saveAllData();
            Platform.exit();
            System.exit(0);
        });

        stage.show();
    }

    /**
     * Saves ALL launcher data to disk: tables, settings, logs.
     * Called on window close and via shutdown hook.
     */
    public static void saveAllData() {
        if (isShuttingDown) return;
        isShuttingDown = true;
        try {
            System.out.println("[PowerLaunch] Saving all data before exit...");

            // Save active tab settings to DB
            TabManager tabManager = TabManager.getInstance();
            tabManager.updateActiveTabFromSettings();

            // Save global launcher settings
            SettingsManager.getInstance().save();

            // Close console log file (if active)
            FileLogManager.getInstance().disable();

            // Close SQLite DB
            TabDatabase.getInstance().close();

            System.out.println("[PowerLaunch] All data saved successfully.");
        } catch (Exception e) {
            System.err.println("[PowerLaunch] Error saving data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void showMainScreen() {
        Platform.runLater(() -> {
            try {
                var mainController = new com.powerlaunch.gui.MainController();
                var mainView = mainController.getView();
                rootContainer.getChildren().setAll(mainView);
            } catch (Exception e) {
                System.err.println("Failed to load main screen: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public static void main(String[] args) {
        // Force IPv4 only
        System.setProperty("java.net.preferIPv4Stack", "true");
        // Do NOT enable java.net.useSystemProxies — may break HTTP on Windows without proxy

        // Check for CLI mode (launch Minecraft without GUI)
        if (args.length > 0 && "cli".equalsIgnoreCase(args[0])) {
            // Remove "cli" prefix and pass the rest to CliLauncher
            String[] cliArgs = new String[args.length - 1];
            System.arraycopy(args, 1, cliArgs, 0, args.length - 1);
            CliLauncher.run(cliArgs);
            return; // CliLauncher calls System.exit() when done
        }
        // Normal GUI mode
        launch(args);
    }
}
