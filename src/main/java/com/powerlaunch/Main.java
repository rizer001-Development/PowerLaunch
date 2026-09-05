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
        var enhancedCssUrl = getClass().getResource("/com/powerlaunch/gui/styles/enhanced.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());
        if (enhancedCssUrl != null) scene.getStylesheets().add(enhancedCssUrl.toExternalForm());

        stage.setTitle("PowerLaunch");
        var iconUrl = getClass().getResource("/com/powerlaunch/icons/app-icon.png");
        if (iconUrl != null) stage.getIcons().add(new javafx.scene.image.Image(iconUrl.toExternalForm()));
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.centerOnScreen();
        stage.setResizable(true);
        stage.setOnCloseRequest(e -> { saveAllData(); Platform.exit(); System.exit(0); });
        stage.show();
        applyTheme();
    }

    public static void applyTheme() {
        Platform.runLater(() -> {
            try {
                var settings = SettingsManager.getInstance();
                String theme = settings.getString("theme", "Dark");
                boolean gradientEnabled = settings.getBoolean("gradientEnabled", false);
                String backgroundColor = settings.getString("backgroundColor", "#0f172a");
                
                Scene scene = primaryStage.getScene();
                if (scene != null) {
                    // Remove existing theme classes
                    scene.getRoot().getStyleClass().removeIf(className -> 
                        className.startsWith("gradient-"));
                    
                    if (gradientEnabled) {
                        // Clear the inline background so the CSS gradient class wins
                        scene.getRoot().setStyle(null);
                        // Add new theme class
                        switch (theme.toLowerCase()) {
                            case "light":
                                scene.getRoot().getStyleClass().add("gradient-light");
                                break;
                            case "gradient":
                            case "vibrant":
                                scene.getRoot().getStyleClass().add("gradient-vibrant");
                                break;
                            case "ocean":
                                scene.getRoot().getStyleClass().add("gradient-ocean");
                                break;
                            case "cyberpunk":
                                scene.getRoot().getStyleClass().add("gradient-cyberpunk");
                                break;
                            case "dark":
                            default:
                                scene.getRoot().getStyleClass().add("gradient-dark");
                                break;
                        }
                    } else {
                        // Solid background from the color picker
                        scene.getRoot().setStyle("-fx-background-color: " + backgroundColor + ";");
                    }
                }
            } catch (Exception e) {
                System.err.println("[PowerLaunch] Failed to apply theme: " + e.getMessage());
            }
        });
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
