package com.powerlaunch;

import com.powerlaunch.settings.SettingsManager;
import com.powerlaunch.storage.AppDatabase;
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
        System.setProperty("java.net.preferIPv4Stack", "true");

        primaryStage = stage;
        rootContainer = new StackPane();

        // Initialize unified SQLite database FIRST (all managers depend on it)
        AppDatabase.getInstance();

        // Test server connectivity in background
        new Thread(() -> NetworkDiagnostics.runAllTests(), "NetDiag").start();

        // Settings auto-loaded from AppDatabase in constructor

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(Main::saveAllData));

        showMainScreen();

        Scene scene = new Scene(rootContainer, 1200, 800);
        var cssUrl = getClass().getResource("/com/powerlaunch/gui/styles/main.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());

        stage.setTitle("PowerLaunch");
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.centerOnScreen();
        stage.setResizable(true);
        stage.setOnCloseRequest(e -> { saveAllData(); Platform.exit(); System.exit(0); });
        stage.show();
    }

    public static void saveAllData() {
        if (isShuttingDown) return;
        isShuttingDown = true;
        try {
            System.out.println("[PowerLaunch] Saving all data before exit...");
            TabManager.getInstance().updateActiveTabFromSettings();
            SettingsManager.getInstance().save();
            FileLogManager.getInstance().disable();
            AppDatabase.getInstance().close();
            System.out.println("[PowerLaunch] All data saved successfully.");
        } catch (Exception e) {
            System.err.println("[PowerLaunch] Error saving data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static Stage getPrimaryStage() { return primaryStage; }

    public static void showMainScreen() {
        Platform.runLater(() -> {
            try {
                var ctrl = new com.powerlaunch.gui.MainController();
                rootContainer.getChildren().setAll(ctrl.getView());
            } catch (Exception e) {
                System.err.println("Failed to load main screen: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        if (args.length > 0 && "cli".equalsIgnoreCase(args[0])) {
            String[] cliArgs = new String[args.length - 1];
            System.arraycopy(args, 1, cliArgs, 0, args.length - 1);
            CliLauncher.run(cliArgs);
            return;
        }
        launch(args);
    }
}
