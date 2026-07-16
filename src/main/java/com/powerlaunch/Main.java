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
        // 1. IPv4 only: Java по умолчанию предпочитает IPv6. На многих системах DNS
        //    отвечает AAAA (IPv6), но реального IPv6-роутинга до серверов нет → таймаут.
        System.setProperty("java.net.preferIPv4Stack", "true");

        // 2. Явно НЕ включаем java.net.useSystemProxies! На Windows без настроенного
        //    прокси этот флаг может прочитать мусор из реестра и направить трафик
        //    в никуда. Java HttpClient сам по себе нормально работает без прокси.
        //    Если нужен прокси — укажите в Windows/IE настройках, HttpClient
        //    автоматически их подхватит через ProxySelector.getDefault().

        primaryStage = stage;
        rootContainer = new StackPane();

        // Тест соединения с серверами (выводит диагностику в консоль)
        NetworkDiagnostics.runAllTests();

        // Инициализация БД (будет открыта в TabManager.getInstance())
        SettingsManager.getInstance().load();

        // Регистрируем shutdown hook для гарантированного сохранения всех данных при выходе
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

        // Обработчик закрытия окна — сохраняем всё перед выходом
        stage.setOnCloseRequest(e -> {
            saveAllData();
            Platform.exit();
            System.exit(0);
        });

        stage.show();
    }

    /**
     * Сохраняет ВСЕ данные лаунчера на диск: таблицы, настройки, логи.
     * Вызывается при закрытии окна и через shutdown hook.
     */
    public static void saveAllData() {
        if (isShuttingDown) return;
        isShuttingDown = true;
        try {
            System.out.println("[PowerLaunch] Saving all data before exit...");

            // Сохраняем настройки активного таба в БД
            TabManager tabManager = TabManager.getInstance();
            tabManager.updateActiveTabFromSettings();

            // Сохраняем глобальные настройки лаунчера
            SettingsManager.getInstance().save();

            // Закрываем файл лога консоли (если активен)
            FileLogManager.getInstance().disable();

            // Закрываем SQLite БД
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
        // Принудительно только IPv4
        System.setProperty("java.net.preferIPv4Stack", "true");
        // НЕ включаем java.net.useSystemProxies — может ломать HTTP на Windows без прокси

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
