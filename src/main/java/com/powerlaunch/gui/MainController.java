package com.powerlaunch.gui;

import com.powerlaunch.Main;
import com.powerlaunch.auth.AccountManager;
import com.powerlaunch.auth.AccountManager.Account;
import com.powerlaunch.auth.AuthManager;
import com.powerlaunch.minecraft.MinecraftLauncher;
import com.powerlaunch.minecraft.ServerManager;
import com.powerlaunch.minecraft.ServerManager.ServerEntry;
import com.powerlaunch.minecraft.VersionManager;
import com.powerlaunch.settings.SettingsManager;
import com.powerlaunch.tabs.TabData;
import com.powerlaunch.tabs.TabManager;
import com.powerlaunch.utils.FileLogManager;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.util.Duration;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainController {
    private final BorderPane view;
    private final StackPane mainContent;
    private Label statusLabel;
    private Label ramLabel;

    // Managers
    private final AccountManager accountManager;
    private final VersionManager versionManager;
    private final AuthManager auth;
    private final SettingsManager settings;
    private final MinecraftLauncher launcher;
    private final ServerManager serverManager;

    // UI state
    private String currentPage = "main";
    private Account selectedAccount;
    private String selectedVersion;
    private Button versionButton;

    // Account settings state
    private String deleteTargetAccount;
    private double sliderProgress = 0;

    // Console
    private TextArea consoleArea;
    private String consoleMode = "off"; // off, errors, all
    private boolean consoleVisible = false;

    // Console overlay
    private StackPane overlayContainer;

    // Console sensors
    private ScheduledExecutorService sensorExecutor;
    private Label cpuLabel, netDownLabel, netUpLabel, diskLabel;
    private volatile long prevCpuKernel = 0, prevCpuUser = 0, prevSensorTime = 0;
    private int cpuRamErrorCount = 0;
    private long prevDiskTime = 0;
    private volatile long lastDiskSize = -1;
    // Network traffic rate tracking (cumulative counters — compute rate via delta)
    private volatile long prevRxBytes = 0, prevTxBytes = 0;

    // Launcher status indicator
    private Label statusIndicator;
    private String launcherStatus = "off"; // off, starting, running, error, timeout
    private Timer timeoutTimer;
    private boolean timeoutReached = false;

    // ==================== Tab System ====================
    private final TabManager tabManager;
    private HBox tabBar;
    private final java.util.List<Label> tabLabels = new java.util.ArrayList<>();
    private final java.util.List<Button> tabCloseButtons = new java.util.ArrayList<>();
    private Button addTabButton;
    private Label activeTabLabel;
    private static final String TAB_ACTIVE_STYLE =
            "-fx-background-color: rgba(233,69,96,0.15);" +
            "-fx-text-fill: #e94560;" +
            "-fx-font-weight: bold;" +
            "-fx-font-size: 12;" +
            "-fx-background-radius: 6 6 0 0;" +
            "-fx-padding: 5 12 5 12;" +
            "-fx-border-color: rgba(233,69,96,0.3) rgba(233,69,96,0.3) transparent rgba(233,69,96,0.3);" +
            "-fx-border-width: 1 1 0 1;" +
            "-fx-border-radius: 6 6 0 0;" +
            "-fx-cursor: hand;";
    private static final String TAB_INACTIVE_STYLE =
            "-fx-background-color: rgba(255,255,255,0.04);" +
            "-fx-text-fill: rgba(255,255,255,0.5);" +
            "-fx-font-size: 12;" +
            "-fx-background-radius: 6 6 0 0;" +
            "-fx-padding: 5 12 5 12;" +
            "-fx-border-color: rgba(255,255,255,0.05) rgba(255,255,255,0.05) transparent rgba(255,255,255,0.05);" +
            "-fx-border-width: 1 1 0 1;" +
            "-fx-border-radius: 6 6 0 0;" +
            "-fx-cursor: hand;";

    public MainController() {
        accountManager = AccountManager.getInstance();
        versionManager = VersionManager.getInstance();
        auth = AuthManager.getInstance();
        settings = SettingsManager.getInstance();
        launcher = MinecraftLauncher.getInstance();
        serverManager = ServerManager.getInstance();

        // If accounts exist, auto-login the current one
        if (accountManager.hasAccounts()) {
            Account current = accountManager.getCurrentAccount();
            if (current != null) {
                auth.loginOffline(current.getUsername());
            }
        }

        selectedVersion = versionManager.getCurrentVersion();
        if (selectedVersion.isEmpty() && !versionManager.getInstalledVersions().isEmpty()) {
            selectedVersion = versionManager.getInstalledVersions().get(0);
        }

        view = new BorderPane();
        view.setId("main-root");
        view.setStyle("-fx-background-color: #1a1a2e;");

        // Initialize tab manager and apply active tab settings
        tabManager = TabManager.getInstance();
        tabManager.applyActiveTabSettings();
        // Sync selectedVersion from tab
        TabData activeTab = tabManager.getActiveTab();
        if (activeTab != null && activeTab.getVersion() != null && !activeTab.getVersion().isEmpty()) {
            selectedVersion = activeTab.getVersion();
        }

        // Outer frame (screen edge frame)
        BorderPane frame = new BorderPane();
        frame.setPadding(new Insets(3));
        frame.setStyle("-fx-border-color: rgba(233,69,96,0.15); -fx-border-width: 1;");

        // Top: Tab bar + Top bar stacked
        frame.setTop(createTopArea());

        // Main content area (switches between main page, account settings, version settings)
        mainContent = new StackPane();
        mainContent.setStyle("-fx-background-color: rgba(0,0,0,0.15);");
        frame.setCenter(mainContent);

        // Bottom status bar
        VBox bottomArea = new VBox();
        bottomArea.getChildren().add(createControlBar());
        bottomArea.getChildren().add(createStatusBar());
        frame.setBottom(bottomArea);

        // Wrap frame in StackPane for console overlay (above everything: tabs, topbar, buttons)
        overlayContainer = new StackPane();
        overlayContainer.setStyle("-fx-background-color: transparent;");
        overlayContainer.getChildren().add(frame);

        view.setCenter(overlayContainer);

        // Load main page
        showMainPage();

        // Entrance
        FadeTransition fadeIn = new FadeTransition(Duration.millis(400), view);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
    }

    public BorderPane getView() {
        return view;
    }

    // ==================== TOP AREA (Tab Bar + Top Bar) ====================

    private VBox createTopArea() {
        VBox topArea = new VBox();
        topArea.setStyle("-fx-background-color: rgba(0,0,0,0.3);");

        // Tab bar at the very top
        topArea.getChildren().add(createTabBar());

        // Original top bar below
        topArea.getChildren().add(createTopBar());

        return topArea;
    }

    private HBox createTabBar() {
        tabBar = new HBox(0);
        tabBar.setAlignment(Pos.CENTER_LEFT);
        tabBar.setPadding(new Insets(6, 8, 0, 8));
        tabBar.setStyle(
                "-fx-background-color: rgba(0,0,0,0.25);" +
                "-fx-border-color: rgba(255,255,255,0.03);" +
                "-fx-border-width: 0 0 1 0;"
        );

        // Right-click on tab bar background → context menu
        tabBar.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                showTabBarContextMenu(e.getScreenX(), e.getScreenY());
            }
        });

        rebuildTabBar();
        tabBar.setPrefHeight(32);
        return tabBar;
    }

    private void rebuildTabBar() {
        tabBar.getChildren().clear();
        tabLabels.clear();
        tabCloseButtons.clear();

        int activeIdx = tabManager.getActiveTabIndex();
        java.util.List<TabData> tabs = tabManager.getTabs();

        for (int i = 0; i < tabs.size(); i++) {
            TabData tab = tabs.get(i);
            boolean isActive = (i == activeIdx);

            HBox tabNode = createTabNode(tab, i, isActive);
            tabBar.getChildren().add(tabNode);
        }

        // Add button at the end
        addTabButton = new Button("+");
        addTabButton.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: rgba(255,255,255,0.4);" +
                "-fx-font-size: 16;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 2 10 4 10;" +
                "-fx-cursor: hand;" +
                "-fx-background-radius: 4;"
        );
        addTabButton.setOnMouseEntered(e ->
                addTabButton.setStyle(
                        "-fx-background-color: rgba(255,255,255,0.08);" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 16;" +
                        "-fx-font-weight: bold;" +
                        "-fx-padding: 2 10 4 10;" +
                        "-fx-cursor: hand;" +
                        "-fx-background-radius: 4;"
                ));
        addTabButton.setOnMouseExited(e ->
                addTabButton.setStyle(
                        "-fx-background-color: transparent;" +
                        "-fx-text-fill: rgba(255,255,255,0.4);" +
                        "-fx-font-size: 16;" +
                        "-fx-font-weight: bold;" +
                        "-fx-padding: 2 10 4 10;" +
                        "-fx-cursor: hand;" +
                        "-fx-background-radius: 4;"
                ));
        addTabButton.setOnAction(e -> showCreateTabDialog());
        Tooltip.install(addTabButton, new Tooltip("Create new tab"));
        tabBar.getChildren().add(addTabButton);

        // Push everything left, spacer fills remaining space
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        tabBar.getChildren().add(spacer);
    }

    private HBox createTabNode(TabData tab, int index, boolean isActive) {
        HBox node = new HBox(4);
        node.setAlignment(Pos.CENTER);
        node.setPadding(new Insets(4, 6, 4, 10));
        node.setStyle(isActive ? TAB_ACTIVE_STYLE : TAB_INACTIVE_STYLE);

        // Tab icon (small circle indicator)
        Label dot = new Label(isActive ? "●" : "○");
        dot.setStyle(
                "-fx-font-size: 8;" +
                "-fx-text-fill: " + (isActive ? "#e94560" : "rgba(255,255,255,0.2)") + ";" +
                "-fx-padding: 0;"
        );

        // Tab name label
        Label nameLabel = new Label(tab.getName());
        nameLabel.setStyle(
                "-fx-font-size: 12;" +
                "-fx-text-fill: " + (isActive ? "#e94560" : "rgba(255,255,255,0.5)") + ";" +
                "-fx-font-weight: " + (isActive ? "bold" : "normal") + ";" +
                "-fx-padding: 0;"
        );

        // Close button (X) - only show on hover
        Button closeBtn = new Button("×");
        closeBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: " + (isActive ? "rgba(233,69,96,0.5)" : "rgba(255,255,255,0.15)") + ";" +
                "-fx-font-size: 12;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 0 2;" +
                "-fx-cursor: hand;" +
                "-fx-background-radius: 3;" +
                "-fx-min-width: 16;" +
                "-fx-min-height: 16;" +
                "-fx-visible: false;"
        );
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(
                "-fx-background-color: rgba(233,69,96,0.3);" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 12;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 0 2;" +
                "-fx-cursor: hand;" +
                "-fx-background-radius: 3;" +
                "-fx-min-width: 16;" +
                "-fx-min-height: 16;"
        ));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: rgba(233,69,96,0.5);" +
                "-fx-font-size: 12;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 0 2;" +
                "-fx-cursor: hand;" +
                "-fx-background-radius: 3;" +
                "-fx-min-width: 16;" +
                "-fx-min-height: 16;"
        ));
        closeBtn.setOnAction(e -> {
            e.consume();
            deleteTab(index);
        });

        node.getChildren().addAll(dot, nameLabel, closeBtn);

        // Hide close button initially, show on hover
        closeBtn.setVisible(false);
        node.setOnMouseEntered(e -> closeBtn.setVisible(true));
        node.setOnMouseExited(e -> closeBtn.setVisible(false));

        // LMB: Switch to this tab
        node.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                switchToTab(index);
            } else if (e.getButton() == MouseButton.SECONDARY) {
                showTabContextMenu(index, e.getScreenX(), e.getScreenY());
            }
        });

        return node;
    }

    private void switchToTab(int index) {
        if (index == tabManager.getActiveTabIndex()) return;

        // Save current tab state (including console) before switching
        saveCurrentConsoleState();
        tabManager.updateActiveTabFromSettings();

        // Switch active tab
        tabManager.setActiveTab(index);

        // Apply the new tab's settings directly to SettingsManager
        tabManager.applyActiveTabSettings();

        // Restore console state for the new tab
        restoreConsoleState();

        // Sync local state
        TabData active = tabManager.getActiveTab();
        if (active != null) {
            selectedVersion = active.getVersion();
        }

        // Update version button text
        if (versionButton != null) {
            updateVersionButton(versionButton);
        }

        // Close console overlay if switching tabs (it's per-tab)
        if (consoleVisible) {
            consoleVisible = false;
            stopSensors();
            if (overlayContainer != null) {
                overlayContainer.getChildren().removeIf(c -> c instanceof StackPane && c != overlayContainer.getChildren().get(0));
            }
        }

        // Rebuild tab bar to update active/inactive styles
        rebuildTabBar();

        // Refresh main page if we're on it
        if ("main".equals(currentPage)) {
            showMainPage();
        }

        String tabName = active != null ? active.getName() : "?";
        setStatus("✓ Switched to tab: " + tabName);
    }

    /**
     * Saves current console UI state to the active tab in database.
     */
    private void saveCurrentConsoleState() {
        tabManager.saveConsoleState(consoleMode, consoleVisible);
    }

    /**
     * Restores console UI state from the active tab.
     */
    private void restoreConsoleState() {
        String[] state = tabManager.loadConsoleState();
        consoleMode = state[0];
        consoleVisible = "true".equals(state[1]);
    }

    // ==================== TAB DIALOGS ====================

    private void showCreateTabDialog() {
        TextInputDialog dialog = new TextInputDialog("New Tab");
        dialog.setTitle("Create Tab");
        dialog.setHeaderText("Enter a name for the new tab");
        dialog.setContentText("Name:");

        DialogPane dp = dialog.getDialogPane();
        dp.setStyle("-fx-background-color: #1a1a2e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12; -fx-background-radius: 12;");
        Scene s = dp.getScene();
        if (s != null) s.setFill(Color.valueOf("#1a1a2e"));
        var hdr = dp.lookup(".header-panel");
        if (hdr != null) hdr.setStyle("-fx-background-color: transparent;");
        var hdrText = dp.lookup(".header-text");
        if (hdrText instanceof Label hl) { hl.setTextFill(Color.WHITE); hl.setFont(Font.font("System", FontWeight.BOLD, 16)); }
        var contentText = dp.lookup(".content");
        if (contentText instanceof Label cl) cl.setTextFill(Color.rgb(200, 200, 220));
        var textField = dp.lookup(".text-field");
        if (textField instanceof TextField tf) {
            tf.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12;");
        }

        Button okBtn = (Button) dp.lookupButton(ButtonType.OK);
        if (okBtn != null) okBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");
        Button cancelBtn = (Button) dp.lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) cancelBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(255,255,255,0.6); -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");

        dialog.initOwner(view.getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                tabManager.updateActiveTabFromSettings();
                tabManager.createTab(name.trim());
                rebuildTabBar();
                tabManager.applyActiveTabSettings();
                TabData active = tabManager.getActiveTab();
                if (active != null) selectedVersion = active.getVersion();
                if ("main".equals(currentPage)) showMainPage();
                setStatus("✓ Tab created: " + name.trim());
            }
        });
    }

    private void showRenameTabDialog(int index) {
        TabData tab = tabManager.getTab(index);
        if (tab == null) return;

        TextInputDialog dialog = new TextInputDialog(tab.getName());
        dialog.setTitle("Rename Tab");
        dialog.setHeaderText("Enter new name");
        dialog.setContentText("Name:");

        DialogPane dp = dialog.getDialogPane();
        dp.setStyle("-fx-background-color: #1a1a2e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12; -fx-background-radius: 12;");
        Scene s = dp.getScene();
        if (s != null) s.setFill(Color.valueOf("#1a1a2e"));
        var hdr = dp.lookup(".header-panel");
        if (hdr != null) hdr.setStyle("-fx-background-color: transparent;");
        var hdrText = dp.lookup(".header-text");
        if (hdrText instanceof Label hl) { hl.setTextFill(Color.WHITE); hl.setFont(Font.font("System", FontWeight.BOLD, 16)); }
        var contentText = dp.lookup(".content");
        if (contentText instanceof Label cl) cl.setTextFill(Color.rgb(200, 200, 220));

        Button okBtn = (Button) dp.lookupButton(ButtonType.OK);
        if (okBtn != null) okBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");
        Button cancelBtn = (Button) dp.lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) cancelBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(255,255,255,0.6); -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");

        dialog.initOwner(view.getScene().getWindow());
        dialog.showAndWait().ifPresent(newName -> {
            if (!newName.trim().isEmpty()) {
                tabManager.renameTab(index, newName.trim());
                rebuildTabBar();
                if ("main".equals(currentPage)) showMainPage();
                setStatus("✓ Tab renamed to: " + newName.trim());
            }
        });
    }

    private void deleteTab(int index) {
        if (tabManager.getTabCount() <= 1) {
            setStatus("✗ Cannot delete the last tab");
            return;
        }

        TabData tab = tabManager.getTab(index);
        String tabName = tab != null ? tab.getName() : "?";

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete Tab");
        alert.setHeaderText("Delete tab \"" + tabName + "\"?");
        alert.setContentText("Tab settings will be lost. This action cannot be undone.");

        DialogPane dp = alert.getDialogPane();
        dp.setStyle("-fx-background-color: #1a1a2e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12; -fx-background-radius: 12;");
        Scene s = dp.getScene();
        if (s != null) s.setFill(Color.valueOf("#1a1a2e"));
        var hdr = dp.lookup(".header-panel");
        if (hdr != null) hdr.setStyle("-fx-background-color: transparent;");
        var hdrText = dp.lookup(".header-text");
        if (hdrText instanceof Label hl) { hl.setTextFill(Color.WHITE); hl.setFont(Font.font("System", FontWeight.BOLD, 16)); }

        Button okBtn = (Button) dp.lookupButton(ButtonType.OK);
        if (okBtn != null) okBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");
        Button cancelBtn = (Button) dp.lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) cancelBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(255,255,255,0.6); -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            tabManager.deleteTab(index);
            rebuildTabBar();
            tabManager.applyActiveTabSettings();
            TabData active = tabManager.getActiveTab();
            if (active != null) selectedVersion = active.getVersion();
            if ("main".equals(currentPage)) showMainPage();
            setStatus("✓ Tab \"" + tabName + "\" deleted");
        }
    }

    private void showTabContextMenu(int index, double screenX, double screenY) {
        TabData tab = tabManager.getTab(index);
        if (tab == null) return;

        ContextMenu menu = new ContextMenu();
        menu.setStyle(
                "-fx-background-color: #16213e;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 4;"
        );

        MenuItem renameItem = new MenuItem("✏️  Rename");
        renameItem.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-padding: 6 20;");
        renameItem.setOnAction(e -> showRenameTabDialog(index));

        MenuItem duplicateItem = new MenuItem("📋  Duplicate");
        duplicateItem.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-padding: 6 20;");
        duplicateItem.setOnAction(e -> {
            tabManager.updateActiveTabFromSettings();
            tabManager.duplicateTab(index);
            rebuildTabBar();
            tabManager.applyActiveTabSettings();
            TabData active = tabManager.getActiveTab();
            if (active != null) selectedVersion = active.getVersion();
            if ("main".equals(currentPage)) showMainPage();
            setStatus("✓ Tab duplicated");
        });

        MenuItem exportItem = new MenuItem("📤  Export...");
        exportItem.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-padding: 6 20;");
        exportItem.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Export Tab");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PowerLaunch Tab", "*.json"));
            fc.setInitialFileName(tab.getName().replaceAll("[\\/:*?\"<>|]", "_") + ".json");
            File file = fc.showSaveDialog(view.getScene().getWindow());
            if (file != null) {
                if (tabManager.exportTab(index, file)) {
                    setStatus("✓ Tab exported: " + file.getName());
                } else {
                    setStatus("✗ Tab export failed");
                }
            }
        });

        if (tabManager.getTabCount() > 1) {
            menu.getItems().addAll(renameItem, duplicateItem, exportItem);
            menu.getItems().add(new SeparatorMenuItem());

            MenuItem deleteItem = new MenuItem("🗑️  Delete");
            deleteItem.setStyle("-fx-text-fill: #ef4444; -fx-padding: 6 20;");
            deleteItem.setOnAction(e -> deleteTab(index));
            menu.getItems().add(deleteItem);
        } else {
            menu.getItems().addAll(renameItem, duplicateItem, exportItem);
        }

        menu.show(tabBar, javafx.geometry.Side.BOTTOM, screenX - tabBar.localToScreen(0, 0).getX(), 0);
    }

    private void showTabBarContextMenu(double screenX, double screenY) {
        ContextMenu menu = new ContextMenu();
        menu.setStyle(
                "-fx-background-color: #16213e;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 4;"
        );

        MenuItem createItem = new MenuItem("➕  Create Tab");
        createItem.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-padding: 6 20;");
        createItem.setOnAction(e -> showCreateTabDialog());

        MenuItem importItem = new MenuItem("📥  Import Tab...");
        importItem.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-padding: 6 20;");
        importItem.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Import Tab");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PowerLaunch Tab", "*.json"));
            File file = fc.showOpenDialog(view.getScene().getWindow());
            if (file != null) {
                tabManager.updateActiveTabFromSettings();
                TabData imported = tabManager.importTab(file);
                if (imported != null) {
                    rebuildTabBar();
                    tabManager.applyActiveTabSettings();
                    TabData active = tabManager.getActiveTab();
                    if (active != null) selectedVersion = active.getVersion();
                    if ("main".equals(currentPage)) showMainPage();
                    setStatus("✓ Imported tab: " + imported.getName());
                } else {
                    setStatus("✗ Tab import failed");
                }
            }
        });

        menu.getItems().addAll(createItem, importItem);

        menu.show(tabBar, javafx.geometry.Side.BOTTOM, screenX - tabBar.localToScreen(0, 0).getX(), 0);
    }

    // ==================== TOP BAR ====================

    private HBox createTopBar() {
        HBox bar = new HBox();
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 20, 10, 20));
        bar.setStyle(
                "-fx-background-color: rgba(0,0,0,0.35);" +
                "-fx-border-color: rgba(255,255,255,0.05);" +
                "-fx-border-width: 0 0 1 0;"
        );

        Text logo = new Text("⚡ PowerLaunch");
        logo.setFont(Font.font("System", FontWeight.BOLD, 17));
        logo.setFill(Color.WHITE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Account button
        Button accountBtn = createAccountButton();
        HBox.setMargin(accountBtn, new Insets(0, 0, 0, 10));

        bar.getChildren().addAll(logo, spacer, accountBtn);
        return bar;
    }

    private Button createAccountButton() {
        Button btn = new Button();
        updateAccountButton(btn);
        return btn;
    }

    private void updateAccountButton(Button btn) {
        if (accountManager.hasAccounts()) {
            Account current = accountManager.getCurrentAccount();
            String name = (current != null) ? current.getUsername() : accountManager.getAccounts().get(0).getUsername();
            btn.setText("👤 " + name + "  ▾");
        } else {
            btn.setText("👤 Create Account  ▾");
        }
        btn.setStyle(
                "-fx-background-color: rgba(233,69,96,0.12);" +
                "-fx-text-fill: #e94560;" +
                "-fx-border-color: rgba(233,69,96,0.3);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 7 16;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: rgba(233,69,96,0.2);" +
                "-fx-text-fill: #ff5a77;" +
                "-fx-border-color: rgba(233,69,96,0.5);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 7 16;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-cursor: hand;"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: rgba(233,69,96,0.12);" +
                "-fx-text-fill: #e94560;" +
                "-fx-border-color: rgba(233,69,96,0.3);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 7 16;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-cursor: hand;"
        ));

        btn.setOnAction(e -> showAccountMenu(btn));
    }

    private void showAccountMenu(Button btn) {
        // If no accounts, go directly to create
        if (!accountManager.hasAccounts()) {
            showCreateAccountDialog(() -> {
                updateAccountButton(btn);
                showMainPage();
            });
            return;
        }

        // Create a popup menu
        ContextMenu menu = new ContextMenu();
        menu.setStyle(
                "-fx-background-color: #16213e;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 4;"
        );

        // Add account items
        for (Account acc : accountManager.getAccounts()) {
            String name = acc.getUsername();
            boolean isCurrent = accountManager.getCurrentAccount() != null
                    && accountManager.getCurrentAccount().getUsername().equals(name);
            MenuItem item = new MenuItem((isCurrent ? "✓ " : "   ") + name);
            item.setStyle(
                    "-fx-text-fill: " + (isCurrent ? "#e94560" : "white") + ";" +
                    "-fx-font-weight: " + (isCurrent ? "bold" : "normal") + ";" +
                    "-fx-padding: 6 20;"
            );
            item.setOnAction(e -> {
                accountManager.selectAccount(name);
                auth.loginOffline(name);
                updateAccountButton(btn);
                setStatus("✓ Account: " + name);
            });
            menu.getItems().add(item);
        }

        menu.getItems().add(new SeparatorMenuItem());

        MenuItem settingsItem = new MenuItem("⚙️ Account Settings");
        settingsItem.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-padding: 6 20;");
        settingsItem.setOnAction(e -> showAccountSettings());
        menu.getItems().add(settingsItem);

        menu.show(btn, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    // ==================== CONTROL BAR ====================

    private HBox createControlBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(14, 25, 14, 25));
        bar.setStyle(
                "-fx-background-color: rgba(0,0,0,0.2);" +
                "-fx-border-color: rgba(255,255,255,0.04);" +
                "-fx-border-width: 1 0 0 0;"
        );

        // Version button - left
        versionButton = createVersionButton();
        versionButton.setAlignment(Pos.CENTER_LEFT);

        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);

        // Control buttons - center, like Play in LL
        HBox controlGroup = new HBox(10);
        controlGroup.setAlignment(Pos.CENTER);

        Button startBtn = createStyledButton("▶  Start", "#10b981");
        Button restartBtn = createStyledButton("🔄  Restart", "#f59e0b");
        Button stopBtn = createStyledButton("⏹  Stop", "#e94560");
        Button killBtn = createStyledButton("💀  Kill", "#ef4444");

        startBtn.setOnAction(e -> {
            if (!accountManager.hasAccounts()) {
                flashButton(startBtn);
                setStatus("✗ Please create an account first");
                return;
            }
            if (selectedVersion == null || selectedVersion.isEmpty()) {
                flashButton(startBtn);
                setStatus("✗ Please select a version first");
                return;
            }
            handleConsoleStart();
        });

        restartBtn.setOnAction(e -> handleConsoleRestart());

        stopBtn.setOnAction(e -> handleConsoleStop());

        killBtn.setOnAction(e -> handleConsoleKill());

        controlGroup.getChildren().addAll(startBtn, restartBtn, stopBtn, killBtn);

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        // Utility buttons - right
        HBox utilGroup = new HBox(8);
        utilGroup.setAlignment(Pos.CENTER_RIGHT);

        Button folderBtn = createSvgIconButton(createFolderSvg(), "Open game directory");
        folderBtn.setOnAction(e -> {
            try {
                String path = versionManager.getGameDirectory().toAbsolutePath().toString();
                Runtime.getRuntime().exec("explorer.exe " + path);
            } catch (Exception ex) {
                setStatus("✗ Failed to open directory");
            }
        });

        Button settingsBtn = createSvgIconButton(createGearSvg(), "Launcher Settings");
        settingsBtn.setOnAction(e -> showLauncherSettings());

        Button consoleBtn = createConsoleButton();
        utilGroup.getChildren().addAll(folderBtn, consoleBtn, settingsBtn);
        // Add status indicator
        Label statusInd = new Label("●  Off");
        statusInd.setFont(Font.font("System", 12));
        statusInd.setTextFill(Color.web("#ef4444"));
        statusInd.setAlignment(Pos.CENTER);
        statusIndicator = statusInd;
        HBox.setMargin(statusInd, new Insets(0, 10, 0, 0));
        bar.getChildren().addAll(versionButton, leftSpacer, controlGroup, rightSpacer, statusInd, utilGroup);
        return bar;
    }

    private void flashButton(Button btn) {
        String orig = btn.getStyle();
        btn.setStyle(
                "-fx-background-color: #ef4444;" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 8 18;" +
                "-fx-cursor: hand;" +
                "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.6), 20, 0, 0, 4);"
        );
        javafx.animation.Timeline flash = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.millis(300),
                        new javafx.animation.KeyValue(btn.styleProperty(), orig, javafx.animation.Interpolator.EASE_OUT)
                )
        );
        flash.play();
    }

    private Button createStyledButton(String text, String color) {
        Button btn = new Button(text);
        btn.setPrefHeight(40);
        btn.setStyle(
                "-fx-background-color: " + color + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 8 18;" +
                "-fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: derive(" + color + ", 20%);" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 8 18;" +
                "-fx-cursor: hand;" +
                "-fx-effect: dropshadow(gaussian, " + color + "40, 12, 0, 0, 4);"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: " + color + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 8 18;" +
                "-fx-cursor: hand;"
        ));
        return btn;
    }

    // ==================== SVG ICONS ====================

    private SVGPath createFolderSvg() {
        SVGPath svg = new SVGPath();
        svg.setContent("M2 6V4c0-1.1.9-2 2-2h4l2 2h8a2 2 0 0 1 2 2v2H4a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8H2z");
        svg.setFill(Color.rgb(200, 200, 220));
        svg.setScaleX(0.75);
        svg.setScaleY(0.75);
        return svg;
    }

    private SVGPath createGearSvg() {
        SVGPath svg = new SVGPath();
        svg.setContent("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.07.62-.07.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z");
        svg.setFill(Color.rgb(200, 200, 220));
        svg.setScaleX(0.7);
        svg.setScaleY(0.7);
        return svg;
    }

    private SVGPath createTerminalSvg() {
        SVGPath svg = new SVGPath();
        svg.setContent("M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm0 2v12h16V6H4zm2 2l3 3-3 3 1.5 1.5L11 11 7.5 7.5 6 8zm7 6v2h4v-2h-4z");
        svg.setFill(Color.rgb(200, 200, 220));
        svg.setScaleX(0.7);
        svg.setScaleY(0.7);
        return svg;
    }

    private SVGPath createHomeSvg() {
        SVGPath svg = new SVGPath();
        svg.setContent("M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z");
        svg.setFill(Color.rgb(200, 200, 220));
        svg.setScaleX(0.7);
        svg.setScaleY(0.7);
        return svg;
    }

    private Button createSvgIconButton(SVGPath svg, String tooltip) {
        StackPane graphic = new StackPane(svg);
        graphic.setPrefSize(22, 22);
        graphic.setAlignment(Pos.CENTER);

        Button btn = new Button();
        btn.setGraphic(graphic);
        btn.setPrefSize(36, 36);
        btn.setMinSize(36, 36);
        btn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.05);" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: rgba(255,255,255,0.08);" +
                "-fx-border-radius: 8;" +
                "-fx-cursor: hand;" +
                "-fx-padding: 0;"
        );
        if (tooltip != null) {
            Tooltip tp = new Tooltip(tooltip);
            tp.setStyle(
                    "-fx-background-color: #16213e;" +
                    "-fx-text-fill: white;" +
                    "-fx-font-size: 11;" +
                    "-fx-background-radius: 4;" +
                    "-fx-padding: 4 8;"
            );
            Tooltip.install(btn, tp);
        }
        btn.setOnMouseEntered(e -> {
            btn.setStyle(
                    "-fx-background-color: rgba(255,255,255,0.1);" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: rgba(255,255,255,0.15);" +
                    "-fx-border-radius: 8;" +
                    "-fx-cursor: hand;" +
                    "-fx-padding: 0;"
            );
            svg.setFill(Color.WHITE);
        });
        btn.setOnMouseExited(e -> {
            btn.setStyle(
                    "-fx-background-color: rgba(255,255,255,0.05);" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: rgba(255,255,255,0.08);" +
                    "-fx-border-radius: 8;" +
                    "-fx-cursor: hand;" +
                    "-fx-padding: 0;"
            );
            svg.setFill(Color.rgb(200, 200, 220));
        });
        return btn;
    }

    private Button createVersionButton() {
        Button btn = new Button();
        updateVersionButton(btn);
        return btn;
    }

    private void updateVersionButton(Button btn) {
        String version = selectedVersion.isEmpty() ? "none selected" : selectedVersion;
        btn.setText("📦 Version: " + version + "  ▾");
        btn.setStyle(
                "-fx-background-color: rgba(59,130,246,0.12);" +
                "-fx-text-fill: #3b82f6;" +
                "-fx-border-color: rgba(59,130,246,0.3);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 7 16;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: rgba(59,130,246,0.2);" +
                "-fx-text-fill: #60a5fa;" +
                "-fx-border-color: rgba(59,130,246,0.5);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 7 16;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-cursor: hand;"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: rgba(59,130,246,0.12);" +
                "-fx-text-fill: #3b82f6;" +
                "-fx-border-color: rgba(59,130,246,0.3);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 7 16;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-cursor: hand;"
        ));

        btn.setOnAction(e -> showVersionMenu(btn));
    }

    private void showVersionMenu(Button btn) {
        ContextMenu menu = new ContextMenu();
        menu.setStyle(
                "-fx-background-color: #16213e;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 4;"
        );

        List<String> versions = versionManager.getInstalledVersions();

        if (versions.isEmpty()) {
            MenuItem emptyItem = new MenuItem("No installed versions");
            emptyItem.setDisable(true);
            emptyItem.setStyle("-fx-text-fill: rgba(255,255,255,0.4); -fx-padding: 6 20;");
            menu.getItems().add(emptyItem);
        } else {
            for (String v : versions) {
                boolean isCurrent = v.equals(selectedVersion);
                MenuItem item = new MenuItem((isCurrent ? "✓ " : "   ") + v);
                item.setStyle(
                        "-fx-text-fill: " + (isCurrent ? "#3b82f6" : "white") + ";" +
                        "-fx-font-weight: " + (isCurrent ? "bold" : "normal") + ";" +
                        "-fx-padding: 6 20;"
                );
                item.setOnAction(e -> {
                    selectedVersion = v;
                    versionManager.selectVersion(v);
                    updateVersionButton(btn);
                    // Save to active tab
                    tabManager.updateActiveTabFromSettings();
                    setStatus("✓ Version: " + v);
                });
                menu.getItems().add(item);
            }
        }

        menu.getItems().add(new SeparatorMenuItem());
        MenuItem manageItem = new MenuItem("⚙️ Manage Versions");
        manageItem.setStyle("-fx-text-fill: rgba(255,255,255,0.8); -fx-padding: 6 20;");
        manageItem.setOnAction(e -> showVersionSettings());
        menu.getItems().add(manageItem);

        menu.show(btn, javafx.geometry.Side.TOP, 0, 4);
    }

    // ==================== STATUS BAR ====================

    private HBox createStatusBar() {
        HBox bar = new HBox();
        bar.setPadding(new Insets(8, 20, 8, 20));
        bar.setStyle(
                "-fx-background-color: rgba(0,0,0,0.35);" +
                "-fx-border-color: rgba(255,255,255,0.05);" +
                "-fx-border-width: 1 0 0 0;"
        );
        bar.setAlignment(Pos.CENTER_LEFT);

        Label status = new Label("✓ Ready to launch");
        status.setFont(Font.font("System", 13));
        status.setTextFill(Color.rgb(200, 200, 230));

        bar.getChildren().add(status);

        statusLabel = status;
        return bar;
    }

    private PauseTransition statusTimeout;
    private static final String DEFAULT_STATUS = "✓ Ready to launch";

    private void setStatus(String text) {
        Platform.runLater(() -> {
            if (statusTimeout != null) {
                statusTimeout.stop();
            }
            statusLabel.setText(text);
            statusTimeout = new PauseTransition(javafx.util.Duration.seconds(4));
            statusTimeout.setOnFinished(e -> statusLabel.setText(DEFAULT_STATUS));
            statusTimeout.play();
        });
    }

    // ==================== PAGE NAVIGATION ====================

    private void showPage(String page) {
        currentPage = page;
        switch (page) {
            case "main" -> showMainPage();
            case "account-settings" -> showAccountSettingsPage();
            case "version-settings" -> showVersionSettingsPage();
            case "launcher-settings" -> showLauncherSettingsPage();
            case "console" -> showConsolePanel();
        }
    }

    private void showLauncherSettings() {
        showPage("launcher-settings");
    }

    private void showLauncherSettingsPage() {
        BorderPane page = new BorderPane();
        page.setPadding(new Insets(25, 30, 25, 30));
        page.setStyle("-fx-background-color: transparent;");

        Text title = new Text("⚙️ Launcher Settings");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setFill(Color.WHITE);
        BorderPane.setMargin(title, new Insets(0, 0, 20, 0));
        page.setTop(title);

        VBox form = new VBox(16);
        form.setPadding(new Insets(0, 0, 15, 0));

        // === 1. Game Directory ===
        VBox gameDirCard = createSettingsCard("Game Directory",
                "Path to Minecraft files (.minecraft / .powerlaunch)");
        HBox dirRow = new HBox(10);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        TextField dirField = new TextField(settings.getString("gameDirectory", ""));
        dirField.setPromptText("e.g.: C:\\Users\\Name\\AppData\\Roaming\\.powerlaunch");
        dirField.setPrefWidth(400);
        dirField.setStyle(settingsInputStyle());
        dirField.textProperty().addListener((obs, old, val) -> settings.set("gameDirectory", val));

        Button dirBrowseBtn = createBrowseButton(createFolderSvg(), "Browse folder");
        dirBrowseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select game directory");
            if (!dirField.getText().isEmpty()) {
                dc.setInitialDirectory(new File(dirField.getText()));
            }
            File dir = dc.showDialog(view.getScene().getWindow());
            if (dir != null) {
                dirField.setText(dir.getAbsolutePath());
            }
        });

        dirRow.getChildren().addAll(dirField, dirBrowseBtn);
        gameDirCard.getChildren().add(dirRow);
        form.getChildren().add(gameDirCard);

        // === 2. Java Selection ===
        VBox javaCard = createSettingsCard("Java Selection",
                "Choose Java version for Minecraft or specify path manually");

        // Java choice dropdown
        HBox javaChoiceRow = new HBox(10);
        javaChoiceRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> javaChoice = new ComboBox<>();
        javaChoice.setPrefWidth(300);

        // Auto-detect Java versions
        String currentJavaHome = System.getProperty("java.home");
        String currentJavaVersion = System.getProperty("java.version");
        String autoLabel = "Auto-detect (Java " + currentJavaVersion + ")";
        javaChoice.getItems().add(autoLabel);
        javaChoice.getItems().add("Current only");

        // Try to find other Java installations
        try {
            String javas = System.getProperty("java.ext.dirs", "");
            File[] roots = File.listRoots();
            // Common Java paths to check
            String[] possiblePaths = {
                    "C:\\Program Files\\Java",
                    "C:\\Program Files\\Eclipse Adoptium",
                    "C:\\Program Files\\Microsoft",
                    "C:\\Program Files\\Amazon Corretto",
                    System.getProperty("user.home") + "\\.jdks",
                    System.getenv("JAVA_HOME") != null ? System.getenv("JAVA_HOME") : ""
            };
            for (String basePath : possiblePaths) {
                if (basePath.isEmpty()) continue;
                File base = new File(basePath);
                if (base.exists() && base.isDirectory()) {
                    File[] dirs = base.listFiles();
                    if (dirs != null) {
                        for (File dir : dirs) {
                            if (dir.isDirectory()) {
                                String name = dir.getName();
                                if (name.toLowerCase().contains("java") || name.toLowerCase().contains("jdk") || name.toLowerCase().contains("jre")) {
                                    javaChoice.getItems().add(name + " (" + dir.getAbsolutePath() + ")");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        String savedJavaChoice = settings.getString("javaChoice", "auto");
        if (savedJavaChoice.equals("auto")) {
            javaChoice.setValue(autoLabel);
        } else {
            javaChoice.setValue(savedJavaChoice);
        }
        javaChoice.setStyle(settingsComboStyle());
        javaChoice.setOnAction(e -> {
            String val = javaChoice.getValue();
            settings.set("javaChoice", val != null && val.equals(autoLabel) ? "auto" : val);
        });

        Label javaChoiceLabel = new Label("Version:");
        javaChoiceLabel.setTextFill(Color.rgb(200, 200, 220));
        javaChoiceRow.getChildren().add(javaChoiceLabel);
        javaChoiceRow.getChildren().add(javaChoice);
        javaCard.getChildren().add(javaChoiceRow);

        // Custom Java path
        HBox javaPathRow = new HBox(10);
        javaPathRow.setAlignment(Pos.CENTER_LEFT);
        javaPathRow.setPadding(new Insets(8, 0, 0, 0));

        TextField javaPathField = new TextField(settings.getString("javaPath", ""));
        javaPathField.setPromptText("Path to javaw.exe / java");
        javaPathField.setPrefWidth(400);
        javaPathField.setStyle(settingsInputStyle());
        javaPathField.textProperty().addListener((obs, old, val) -> settings.set("javaPath", val));

        Button javaBrowseBtn = createBrowseButton(createFolderSvg(), "Browse java/javaw.exe");
        javaBrowseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select java/javaw.exe");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java Executable", "*.exe", "*"));
            if (!javaPathField.getText().isEmpty()) {
                fc.setInitialDirectory(new File(javaPathField.getText()).getParentFile());
            }
            File file = fc.showOpenDialog(view.getScene().getWindow());
            if (file != null) {
                javaPathField.setText(file.getAbsolutePath());
            }
        });

        Label javaPathLabel = new Label("Path:");
        javaPathLabel.setTextFill(Color.rgb(200, 200, 220));
        javaPathRow.getChildren().add(javaPathLabel);
        javaPathRow.getChildren().add(javaPathField);
        javaPathRow.getChildren().add(javaBrowseBtn);
        javaCard.getChildren().add(javaPathRow);

        form.getChildren().add(javaCard);

        // === 3. JVM Arguments ===
        VBox jvmCard = createSettingsCard("JVM Arguments",
                "Arguments for the Java Virtual Machine. Default allocation is 4096 MB RAM.");

        TextField jvmField = new TextField();
        String savedArgs = settings.getString("javaArgs", "");
        if (savedArgs.isEmpty()) {
            jvmField.setText("-Xmx4096M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200");
        } else {
            jvmField.setText(savedArgs);
        }
        jvmField.setPrefWidth(600);
        jvmField.setStyle(settingsInputStyle());
        jvmField.textProperty().addListener((obs, old, val) -> settings.set("javaArgs", val));

        jvmCard.getChildren().add(jvmField);
        form.getChildren().add(jvmCard);

        // === 4. GPU Selection ===
        VBox gpuCard = createSettingsCard("GPU Selection",
                "Choose GPU for Minecraft (if multiple available)");
        HBox gpuRow = new HBox(10);
        gpuRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> gpuChoice = new ComboBox<>();
        gpuChoice.setPrefWidth(400);
        gpuChoice.getItems().add("Auto (system)");

        // Try to detect available GPUs
        try {
            // On Windows, try to read from registry or WMI
            Process process = Runtime.getRuntime().exec("wmic path win32_VideoController get name");
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.equalsIgnoreCase("Name") && !line.toLowerCase().contains("name")) {
                        gpuChoice.getItems().add(line);
                    }
                }
                process.waitFor();
            } finally {
                process.destroy();
            }
        } catch (Exception ignored) {}

        // Fallback if no GPUs detected
        if (gpuChoice.getItems().size() <= 1) {
            gpuChoice.getItems().add("Integrated");
            gpuChoice.getItems().add("Discrete (High Performance)");
        }

        String savedGpu = settings.getString("gpuChoice", "auto");
        if (savedGpu.equals("auto")) {
            gpuChoice.setValue("Auto (system)");
        } else {
            gpuChoice.setValue(savedGpu);
        }
        gpuChoice.setStyle(settingsComboStyle());
        gpuChoice.setOnAction(e -> {
            String val = gpuChoice.getValue();
            settings.set("gpuChoice", val != null && val.equals("Auto (system)") ? "auto" : val);
        });

        gpuRow.getChildren().add(gpuChoice);
        gpuCard.getChildren().add(gpuRow);
        form.getChildren().add(gpuCard);

        // === 5. Auto-connect Server ===
        VBox serverCard = createSettingsCard("Server Connection",
                "Auto-connect to server when Minecraft starts. Manage server list.");

        // Auto-connect toggle
        HBox autoConnectRow = new HBox(12);
        autoConnectRow.setAlignment(Pos.CENTER_LEFT);

        ToggleButton autoConnectToggle = new ToggleButton();
        boolean autoConnect = settings.getBoolean("autoConnect", false);
        autoConnectToggle.setSelected(autoConnect);
        updateToggleStyle(autoConnectToggle);
        autoConnectToggle.setText(autoConnect ? "🟢 On" : "🔴 Off");
        autoConnectToggle.selectedProperty().addListener((obs, old, val) -> {
            settings.set("autoConnect", val);
            updateToggleStyle(autoConnectToggle);
            autoConnectToggle.setText(val ? "🟢 On" : "🔴 Off");
        });

        Label autoConnectLabel = new Label("Connect on launch:");
        autoConnectLabel.setTextFill(Color.rgb(200, 200, 220));

        autoConnectRow.getChildren().addAll(autoConnectLabel, autoConnectToggle);
        serverCard.getChildren().add(autoConnectRow);

        // Server IP field (for auto-connect)
        HBox serverIpRow = new HBox(10);
        serverIpRow.setAlignment(Pos.CENTER_LEFT);
        serverIpRow.setPadding(new Insets(5, 0, 0, 0));

        Label ipLabel = new Label("Server IP:");
        ipLabel.setTextFill(Color.rgb(200, 200, 220));

        TextField serverIpField = new TextField(settings.getString("connectServerIp", ""));
        serverIpField.setPromptText("e.g.: play.example.com:25565");
        serverIpField.setPrefWidth(300);
        serverIpField.setStyle(settingsInputStyle());
        serverIpField.textProperty().addListener((obs, old, val) -> settings.set("connectServerIp", val));

        serverIpRow.getChildren().addAll(ipLabel, serverIpField);
        serverCard.getChildren().add(serverIpRow);

        // Separator
        Separator serverSep = new Separator();
        serverSep.setStyle("-fx-background-color: rgba(255,255,255,0.06);");
        serverSep.setPadding(new Insets(8, 0, 8, 0));
        serverCard.getChildren().add(serverSep);

        // Server list management section
        Text serversSubtitle = new Text("Saved Servers");
        serversSubtitle.setFont(Font.font("System", FontWeight.BOLD, 13));
        serversSubtitle.setFill(Color.rgb(200, 200, 220));
        serverCard.getChildren().add(serversSubtitle);

        ListView<ServerEntry> serverListView = new ListView<>();
        serverListView.setPrefHeight(140);
        serverListView.setStyle(
                "-fx-background-color: rgba(0,0,0,0.2);" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: rgba(255,255,255,0.06);" +
                "-fx-border-radius: 8;"
        );
        serverListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ServerEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    HBox cell = new HBox(10);
                    cell.setAlignment(Pos.CENTER_LEFT);
                    cell.setPadding(new Insets(6, 12, 6, 12));

                    Label nameLabel = new Label(entry.getName());
                    nameLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
                    nameLabel.setTextFill(Color.WHITE);

                    Label ipLabel2 = new Label(entry.getDisplayIp());
                    ipLabel2.setFont(Font.font("System", 12));
                    ipLabel2.setTextFill(Color.rgb(160, 160, 190));

                    Region spacer2 = new Region();
                    HBox.setHgrow(spacer2, Priority.ALWAYS);

                    cell.getChildren().addAll(nameLabel, ipLabel2, spacer2);
                    setGraphic(cell);
                    setStyle("-fx-background-color: transparent;");
                }
            }
        });
        refreshServerList(serverListView);

        // Server management buttons
        HBox serverBtnRow = new HBox(8);
        serverBtnRow.setAlignment(Pos.CENTER_LEFT);
        serverBtnRow.setPadding(new Insets(8, 0, 0, 0));

        Button addServerBtn = new Button("➕ Add");
        styleSettingsButton(addServerBtn, "#10b981", "#059669");
        addServerBtn.setOnAction(e -> showAddServerDialog(serverListView));

        Button editServerBtn = new Button("✏️ Edit");
        styleSettingsButton(editServerBtn, "#3b82f6", "#2563eb");
        editServerBtn.setOnAction(e -> {
            int idx = serverListView.getSelectionModel().getSelectedIndex();
            if (idx < 0) {
                setStatus("✗ Select a server from the list");
                return;
            }
            showEditServerDialog(serverListView, idx);
        });

        Button removeServerBtn = new Button("🗑️ Delete");
        styleSettingsButton(removeServerBtn, "#ef4444", "#dc2626");
        removeServerBtn.setOnAction(e -> {
            int idx = serverListView.getSelectionModel().getSelectedIndex();
            if (idx < 0) {
                setStatus("✗ Select a server from the list");
                return;
            }
            serverManager.removeServer(idx);
            refreshServerList(serverListView);
            setStatus("✓ Server deleted");
        });

        serverBtnRow.getChildren().addAll(addServerBtn, editServerBtn, removeServerBtn);
        serverCard.getChildren().addAll(serverListView, serverBtnRow);
        form.getChildren().add(serverCard);

        // === Save button ===
        HBox buttonRow = new HBox(15);
        buttonRow.setAlignment(Pos.CENTER);
        buttonRow.setPadding(new Insets(10, 0, 0, 0));

        Button saveBtn = new Button("💾 Save & return to menu");
        saveBtn.setPrefHeight(44);
        saveBtn.setStyle(
                "-fx-background-color: linear-gradient(to right, #e94560, #c23152);" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 14;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 30;" +
                "-fx-cursor: hand;"
        );
        saveBtn.setOnMouseEntered(e -> saveBtn.setStyle(
                "-fx-background-color: linear-gradient(to right, #ff5a77, #e94560);" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 14;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 30;" +
                "-fx-cursor: hand;"
        ));
        saveBtn.setOnMouseExited(e -> saveBtn.setStyle(
                "-fx-background-color: linear-gradient(to right, #e94560, #c23152);" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 14;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 30;" +
                "-fx-cursor: hand;"
        ));
        saveBtn.setOnAction(e -> {
            settings.save();
            setStatus("✓ Settings saved");
            showMainPage();
        });

        Button cancelBtn = new Button("◀ Back without saving");
        cancelBtn.setPrefHeight(44);
        cancelBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: rgba(255,255,255,0.6);" +
                "-fx-border-color: rgba(255,255,255,0.15);" +
                "-fx-border-radius: 10;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 20;" +
                "-fx-cursor: hand;"
        );
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.05);" +
                "-fx-text-fill: white;" +
                "-fx-border-color: rgba(255,255,255,0.3);" +
                "-fx-border-radius: 10;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 20;" +
                "-fx-cursor: hand;"
        ));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: rgba(255,255,255,0.6);" +
                "-fx-border-color: rgba(255,255,255,0.15);" +
                "-fx-border-radius: 10;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 20;" +
                "-fx-cursor: hand;"
        ));
        cancelBtn.setOnAction(e -> showMainPage());

        buttonRow.getChildren().addAll(saveBtn, cancelBtn);
        form.getChildren().add(buttonRow);

        ScrollPane scrollPane = new ScrollPane(form);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        page.setCenter(scrollPane);

        FadeTransition fade = new FadeTransition(Duration.millis(300), page);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        mainContent.getChildren().setAll(page);
    }

    private VBox createSettingsCard(String title, String desc) {
        VBox card = new VBox(10);
        card.setPadding(new Insets(18));
        card.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-background-radius: 12;" +
                "-fx-border-color: rgba(255,255,255,0.06);" +
                "-fx-border-radius: 12;"
        );
        Text titleText = new Text(title);
        titleText.setFont(Font.font("System", FontWeight.BOLD, 15));
        titleText.setFill(Color.WHITE);
        Text descText = new Text(desc);
        descText.setFont(Font.font("System", 12));
        descText.setFill(Color.rgb(160, 160, 185));
        card.getChildren().addAll(titleText, descText);
        return card;
    }

    private Button createBrowseButton(SVGPath svg, String tooltip) {
        StackPane graphic = new StackPane(svg);
        graphic.setPrefSize(18, 18);
        graphic.setAlignment(Pos.CENTER);

        Button btn = new Button();
        btn.setGraphic(graphic);
        btn.setPrefSize(36, 36);
        btn.setMinSize(36, 36);
        btn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.08);" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: rgba(255,255,255,0.12);" +
                "-fx-border-radius: 8;" +
                "-fx-cursor: hand;" +
                "-fx-padding: 0;"
        );
        if (tooltip != null) {
            Tooltip tp = new Tooltip(tooltip);
            tp.setStyle(
                    "-fx-background-color: #16213e;" +
                    "-fx-text-fill: white;" +
                    "-fx-font-size: 11;" +
                    "-fx-background-radius: 4;" +
                    "-fx-padding: 4 8;"
            );
            Tooltip.install(btn, tp);
        }
        btn.setOnMouseEntered(e -> {
            btn.setStyle(
                    "-fx-background-color: rgba(255,255,255,0.14);" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: rgba(233,69,96,0.3);" +
                    "-fx-border-radius: 8;" +
                    "-fx-cursor: hand;" +
                    "-fx-padding: 0;"
            );
            svg.setFill(Color.WHITE);
        });
        btn.setOnMouseExited(e -> {
            btn.setStyle(
                    "-fx-background-color: rgba(255,255,255,0.08);" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-color: rgba(255,255,255,0.12);" +
                    "-fx-border-radius: 8;" +
                    "-fx-cursor: hand;" +
                    "-fx-padding: 0;"
            );
            svg.setFill(Color.rgb(200, 200, 220));
        });
        return btn;
    }

    private String settingsInputStyle() {
        return "-fx-background-color: rgba(255,255,255,0.06);" +
                "-fx-text-fill: white;" +
                "-fx-prompt-text-fill: rgba(255,255,255,0.3);" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 9 14;" +
                "-fx-font-size: 13;" +
                "-fx-font-family: 'Consolas', 'Courier New', monospace;";
    }

    private String settingsComboStyle() {
        return "-fx-background-color: rgba(255,255,255,0.06);" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 8;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 8;" +
                "-fx-padding: 4 8;" +
                "-fx-font-size: 13;";
    }

    private void showMainPage() {
        VBox page = new VBox();
        page.setAlignment(Pos.CENTER);
        page.setPadding(new Insets(40));
        page.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);

        // Welcome/status info
        Text welcomeText = new Text("Welcome to PowerLaunch!");
        welcomeText.setFont(Font.font("System", FontWeight.BOLD, 24));
        welcomeText.setFill(Color.WHITE);

        String accountInfo = accountManager.hasAccounts()
                ? "Account: " + accountManager.getCurrentAccount().getUsername()
                : "No account selected";
        String versionInfo = selectedVersion.isEmpty() ? "Version none selected" : selectedVersion;

        Text infoText = new Text(accountInfo + "  ·  " + versionInfo);
        infoText.setFont(Font.font("System", 14));
        infoText.setFill(Color.rgb(180, 180, 200));

        // Quick info cards
        HBox infoCards = new HBox(15);
        infoCards.setAlignment(Pos.CENTER);
        infoCards.setPadding(new Insets(30, 0, 0, 0));

        infoCards.getChildren().add(createInfoCard("👤",
                accountManager.hasAccounts() ? accountManager.getCurrentAccount().getUsername() : "No account",
                "Click the account above"));
        infoCards.getChildren().add(createInfoCard("📦",
                selectedVersion.isEmpty() ? "None selected" : selectedVersion,
                "Choose a version below"));
        page.getChildren().addAll(welcomeText, infoText, infoCards);

        FadeTransition fade = new FadeTransition(Duration.millis(300), page);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        mainContent.getChildren().setAll(page);
    }

    private VBox createInfoCard(String icon, String title, String desc) {
        VBox card = new VBox(8);
        card.setPrefSize(180, 120);
        card.setPadding(new Insets(18));
        card.setAlignment(Pos.CENTER);
        card.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-background-radius: 12;" +
                "-fx-border-color: rgba(255,255,255,0.06);" +
                "-fx-border-radius: 12;"
        );

        Text iconText = new Text(icon);
        iconText.setFont(Font.font("System", 28));

        Text titleText = new Text(title);
        titleText.setFont(Font.font("System", FontWeight.BOLD, 14));
        titleText.setFill(Color.WHITE);

        Text descText = new Text(desc);
        descText.setFont(Font.font("System", 11));
        descText.setFill(Color.rgb(150, 150, 180));
        descText.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        card.getChildren().addAll(iconText, titleText, descText);
        return card;
    }

    // ==================== ACCOUNT SETTINGS ====================

    private void showAccountSettings() {
        showPage("account-settings");
    }

    private void showAccountSettingsPage() {
        BorderPane page = new BorderPane();
        page.setPadding(new Insets(20));
        page.setStyle("-fx-background-color: transparent;");

        // Title
        Text title = new Text("⚙️ Account Settings");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setFill(Color.WHITE);
        BorderPane.setMargin(title, new Insets(0, 0, 15, 0));
        page.setTop(title);

        // Account list
        ListView<Account> accountList = new ListView<>();
        accountList.setStyle(
                "-fx-background-color: rgba(255,255,255,0.03);" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: rgba(255,255,255,0.06);" +
                "-fx-border-radius: 10;"
        );
        accountList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Account acc, boolean empty) {
                super.updateItem(acc, empty);
                if (empty || acc == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    boolean isCurrent = accountManager.getCurrentAccount() != null
                            && accountManager.getCurrentAccount().getUsername().equals(acc.getUsername());
                    HBox cell = new HBox(10);
                    cell.setAlignment(Pos.CENTER_LEFT);
                    cell.setPadding(new Insets(8, 12, 8, 12));

                    Label nameLabel = new Label((isCurrent ? "✓ " : "   ") + acc.getUsername());
                    nameLabel.setFont(Font.font("System", isCurrent ? FontWeight.BOLD : FontWeight.NORMAL, 14));
                    nameLabel.setTextFill(isCurrent ? Color.web("#e94560") : Color.WHITE);

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    if (isCurrent) {
                        Label currentBadge = new Label("Current");
                        currentBadge.setStyle(
                                "-fx-background-color: rgba(233,69,96,0.2);" +
                                "-fx-text-fill: #e94560;" +
                                "-fx-font-size: 10;" +
                                "-fx-font-weight: bold;" +
                                "-fx-padding: 2 8;" +
                                "-fx-background-radius: 4;"
                        );
                        cell.getChildren().addAll(nameLabel, spacer, currentBadge);
                    } else {
                        cell.getChildren().addAll(nameLabel, spacer);
                    }

                    cell.setStyle("-fx-background-color: transparent;");
                    setGraphic(cell);
                    setStyle("-fx-background-color: transparent;");
                }
            }
        });

        // Populate list
        accountList.getItems().setAll(accountManager.getAccounts());

        // Selection listener
        accountList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            selectedAccount = selected;
        });

        // Bottom buttons
        VBox bottomBox = new VBox(10);
        bottomBox.setAlignment(Pos.CENTER);
        bottomBox.setPadding(new Insets(15, 0, 0, 0));

        HBox topBtnRow = new HBox(10);
        topBtnRow.setAlignment(Pos.CENTER);

        Button createBtn = new Button("➕ Create new account");
        styleSettingsButton(createBtn, "#10b981", "#059669");
        createBtn.setOnAction(e -> showCreateAccountDialog(() -> {
            accountList.getItems().setAll(accountManager.getAccounts());
            showAccountSettingsPage();
        }));

        Button backBtn = new Button("◀ Back");
        styleSettingsButton(backBtn, "rgba(255,255,255,0.08)", "rgba(255,255,255,0.12)");
        backBtn.setTextFill(Color.rgb(200, 200, 220));
        backBtn.setOnAction(e -> showMainPage());

        topBtnRow.getChildren().addAll(createBtn, backBtn);

        // Delete confirmation slider
        HBox deleteRow = new HBox(12);
        deleteRow.setAlignment(Pos.CENTER);

        StackPane sliderPane = new StackPane();
        sliderPane.setPrefWidth(300);
        sliderPane.setPrefHeight(42);
        sliderPane.setStyle(
                "-fx-background-color: rgba(255,255,255,0.06);" +
                "-fx-background-radius: 21;" +
                "-fx-border-color: rgba(239,68,68,0.3);" +
                "-fx-border-radius: 21;" +
                "-fx-border-width: 1;"
        );

        ProgressBar deleteProgress = new ProgressBar(0);
        deleteProgress.setPrefWidth(298);
        deleteProgress.setPrefHeight(40);
        deleteProgress.setStyle(
                "-fx-accent: #ef4444;" +
                "-fx-background-color: transparent;" +
                "-fx-background-radius: 20;" +
                "-fx-control-inner-background: transparent;" +
                "-fx-padding: 0;"
        );

        Label deleteLabel = new Label("→  Slide to delete");
        deleteLabel.setFont(Font.font("System", 13));
        deleteLabel.setTextFill(Color.rgb(160, 160, 180));
        deleteLabel.setMouseTransparent(true);

        Slider deleteSlider = new Slider(0, 1, 0);
        deleteSlider.setPrefWidth(300);
        deleteSlider.setPrefHeight(42);
        deleteSlider.setShowTickLabels(false);
        deleteSlider.setShowTickMarks(false);
        deleteSlider.setBlockIncrement(0.01);
        deleteSlider.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-background-radius: 21;" +
                "-fx-padding: 0;"
        );
        sliderPane.getChildren().addAll(deleteProgress, deleteLabel, deleteSlider);
        StackPane.setAlignment(deleteProgress, Pos.CENTER);
        StackPane.setAlignment(deleteLabel, Pos.CENTER);

        // Delete button (disabled initially)
        Button deleteBtn = new Button("Delete");
        deleteBtn.setDisable(true);
        deleteBtn.setPrefHeight(42);
        deleteBtn.setStyle(
                "-fx-background-color: #6b7280;" +
                "-fx-text-fill: rgba(255,255,255,0.4);" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 21;" +
                "-fx-padding: 8 24;"
        );

        // Slider logic
        deleteSlider.valueProperty().addListener((obs, old, val) -> {
            double v = val.doubleValue();
            deleteProgress.setProgress(v);
            if (v >= 1.0) {
                deleteBtn.setDisable(false);
                deleteBtn.setStyle(
                        "-fx-background-color: #ef4444;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 13;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 21;" +
                        "-fx-padding: 8 24;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.4), 12, 0, 0, 4);"
                );
                deleteLabel.setText("✓  Подтверждено — нажмите «Delete»");
                deleteLabel.setTextFill(Color.web("#10b981"));
            } else {
                deleteBtn.setDisable(true);
                deleteBtn.setStyle(
                        "-fx-background-color: #6b7280;" +
                        "-fx-text-fill: rgba(255,255,255,0.4);" +
                        "-fx-font-size: 13;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 21;" +
                        "-fx-padding: 8 24;"
                );
                deleteLabel.setText("→  Slide to delete");
                deleteLabel.setTextFill(Color.rgb(160, 160, 180));
            }
        });

        deleteSlider.setOnMouseReleased(e -> {
            if (deleteSlider.getValue() < 1.0) {
                javafx.animation.Timeline snapBack = new javafx.animation.Timeline(
                        new javafx.animation.KeyFrame(
                                javafx.util.Duration.millis(200),
                                new javafx.animation.KeyValue(deleteSlider.valueProperty(), 0, javafx.animation.Interpolator.EASE_OUT)
                        )
                );
                snapBack.play();
            }
        });

        deleteBtn.setOnAction(e -> {
            if (selectedAccount == null) {
                setStatus("✗ Select an account from the list");
                deleteSlider.setValue(0);
                return;
            }
            String name = selectedAccount.getUsername();
            boolean isCurrent = accountManager.getCurrentAccount() != null
                    && accountManager.getCurrentAccount().getUsername().equals(name);

            accountManager.removeAccount(name);
            if (isCurrent) {
                auth.logout();
            }

            setStatus("✓ Аккаунт \"" + name + "\" deleted");

            deleteSlider.setValue(0);
            deleteProgress.setProgress(0);
            deleteBtn.setDisable(true);
            deleteBtn.setStyle(
                    "-fx-background-color: #6b7280;" +
                    "-fx-text-fill: rgba(255,255,255,0.4);" +
                    "-fx-font-size: 13;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 21;" +
                    "-fx-padding: 8 24;"
            );
            deleteLabel.setText("→  Slide to delete");
            deleteLabel.setTextFill(Color.rgb(160, 160, 180));

            accountList.getItems().setAll(accountManager.getAccounts());
            showAccountSettingsPage();
        });

        deleteBtn.setOnMouseEntered(e -> {
            if (!deleteBtn.isDisabled()) {
                deleteBtn.setStyle(
                        "-fx-background-color: #dc2626;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 13;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 21;" +
                        "-fx-padding: 8 24;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.5), 16, 0, 0, 4);"
                );
            }
        });
        deleteBtn.setOnMouseExited(e -> {
            if (!deleteBtn.isDisabled()) {
                deleteBtn.setStyle(
                        "-fx-background-color: #ef4444;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 13;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 21;" +
                        "-fx-padding: 8 24;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.4), 12, 0, 0, 4);"
                );
            }
        });

        deleteRow.getChildren().addAll(sliderPane, deleteBtn);

        bottomBox.getChildren().addAll(topBtnRow, deleteRow);
        page.setBottom(bottomBox);

        // Wrap in scroll pane for the list
        ScrollPane scrollPane = new ScrollPane(accountList);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        page.setCenter(scrollPane);

        FadeTransition fade = new FadeTransition(Duration.millis(300), page);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        mainContent.getChildren().setAll(page);
    }


    private Button createConsoleButton() {
        Button btn = createSvgIconButton(createTerminalSvg(), "Console (logging)");
        btn.setOnAction(e -> showConsolePanel());
        return btn;
    }

    private void showConsolePanel() {
        consoleVisible = true;
        if (consoleArea == null) {
            consoleArea = new TextArea();
        }
        consoleArea.setEditable(false);
        consoleArea.setWrapText(true);
        consoleArea.setStyle(
                "-fx-control-inner-background: #0d1117;" +
                "-fx-text-fill: rgb(200, 200, 220);" +
                "-fx-font-size: 13;" +
                "-fx-font-family: 'Consolas', 'Courier New', monospace;" +
                "-fx-background-color: #0d1117;" +
                "-fx-border-color: rgba(255,255,255,0.06);" +
                "-fx-border-radius: 6;" +
                "-fx-background-radius: 6;"
        );

        StringBuilder existing = new StringBuilder();
        for (String line : launcher.getConsoleLog()) {
            if (shouldShowInConsole(line)) {
                existing.append(line).append("\n");
            }
        }
        consoleArea.setText(existing.toString());
        consoleArea.setScrollTop(Double.MAX_VALUE);

        // Note: setOnConsoleLine is already set in handleConsoleStart()
        // Do NOT re-set it here — it would overwrite the launch callback

        stopSensors();

        BorderPane page = new BorderPane();
        page.setPadding(new Insets(15, 20, 15, 20));
        page.setStyle("-fx-background-color: transparent;");

        // TOP: Title + mode radios
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Text title = new Text("🖥  Console");
        title.setFont(Font.font("System", FontWeight.BOLD, 20));
        title.setFill(Color.WHITE);

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton offMode = new RadioButton("Off");
        offMode.setToggleGroup(modeGroup);
        offMode.setTextFill(Color.rgb(180, 180, 200));
        offMode.setFont(Font.font("System", 12));
        offMode.setStyle("-fx-cursor: hand; -fx-mark-color: #e94560;");

        RadioButton errorsMode = new RadioButton("Errors");
        errorsMode.setToggleGroup(modeGroup);
        errorsMode.setTextFill(Color.rgb(180, 180, 200));
        errorsMode.setFont(Font.font("System", 12));
        errorsMode.setStyle("-fx-cursor: hand; -fx-mark-color: #e94560;");

        RadioButton allMode = new RadioButton("All");
        allMode.setToggleGroup(modeGroup);
        allMode.setTextFill(Color.rgb(180, 180, 200));
        allMode.setFont(Font.font("System", 12));
        allMode.setStyle("-fx-cursor: hand; -fx-mark-color: #e94560;");

        switch (consoleMode) {
            case "off" -> offMode.setSelected(true);
            case "errors" -> errorsMode.setSelected(true);
            case "all" -> allMode.setSelected(true);
        }
        offMode.setOnAction(e -> consoleMode = "off");
        errorsMode.setOnAction(e -> consoleMode = "errors");
        Tooltip allWarn = new Tooltip("⚠ May cause additional system load");
        allWarn.setStyle("-fx-background-color: #16213e; -fx-text-fill: #f59e0b; -fx-font-size: 11; -fx-background-radius: 4; -fx-padding: 4 8;");
        Tooltip.install(allMode, allWarn);
        allMode.setOnAction(e -> consoleMode = "all");

        HBox modeBox = new HBox(4, offMode, errorsMode, allMode);
        modeBox.setAlignment(Pos.CENTER_LEFT);
        topBar.getChildren().addAll(title, modeBox, topSpacer);
        BorderPane.setMargin(topBar, new Insets(0, 0, 8, 0));
        page.setTop(topBar);

        // LEFT: Sensors
        VBox sensorPanel = createSensorPanel();

        // CENTER: Console output
        ScrollPane consoleScroll = new ScrollPane(consoleArea);
        consoleScroll.setFitToWidth(true);
        consoleScroll.setFitToHeight(true);
        consoleScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(consoleScroll, Priority.ALWAYS);
        HBox.setHgrow(consoleScroll, Priority.ALWAYS);

        HBox centerArea = new HBox(10);
        centerArea.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(consoleScroll, Priority.ALWAYS);
        centerArea.getChildren().addAll(sensorPanel, consoleScroll);
        VBox.setVgrow(centerArea, Priority.ALWAYS);
        page.setCenter(centerArea);

        // BOTTOM: controls + actions
        VBox bottomArea = new VBox(6);

        HBox ctrlRow = new HBox(8);
        ctrlRow.setAlignment(Pos.CENTER_LEFT);
        Button startBtn = createStyledButton("▶  Start", "#10b981");
        Button restartBtn = createStyledButton("🔄  Restart", "#f59e0b");
        Button stopBtn = createStyledButton("⏹  Stop", "#e94560");
        Button killBtn = createStyledButton("💀  Kill", "#ef4444");
        startBtn.setOnAction(e -> {
            if (!accountManager.hasAccounts()) {
                flashButton(startBtn);
                setStatus("✗ Please create an account first");
                return;
            }
            if (selectedVersion == null || selectedVersion.isEmpty()) {
                flashButton(startBtn);
                setStatus("✗ Please select a version first");
                return;
            }
            handleConsoleStart();
        });
        restartBtn.setOnAction(e -> handleConsoleRestart());
        stopBtn.setOnAction(e -> handleConsoleStop());
        killBtn.setOnAction(e -> handleConsoleKill());
        ctrlRow.getChildren().addAll(startBtn, restartBtn, stopBtn, killBtn);

        HBox actionRow = new HBox(8);
        actionRow.setAlignment(Pos.CENTER_RIGHT);

        Button clearBtn = new Button("🗑  Clear");
        clearBtn.setPrefHeight(36);
        clearBtn.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 6 14; -fx-cursor: hand;");
        clearBtn.setOnMouseEntered(e -> clearBtn.setStyle("-fx-background-color: rgba(255,255,255,0.14); -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 6 14; -fx-cursor: hand;"));
        clearBtn.setOnMouseExited(e -> clearBtn.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 6 14; -fx-cursor: hand;"));
        clearBtn.setOnAction(e -> { consoleArea.clear(); setStatus("✓ Console cleared"); });

        Button exportBtn = new Button("📥  Export");
        exportBtn.setPrefHeight(36);
        exportBtn.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 6 14; -fx-cursor: hand;");
        exportBtn.setOnMouseEntered(e -> exportBtn.setStyle("-fx-background-color: rgba(255,255,255,0.14); -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 6 14; -fx-cursor: hand;"));
        exportBtn.setOnMouseExited(e -> exportBtn.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 6 14; -fx-cursor: hand;"));
        exportBtn.setOnAction(e -> exportConsoleLogs());

        Button backBtn = new Button();
        SVGPath homeIcon = createHomeSvg();
        StackPane homePane = new StackPane(homeIcon);
        homePane.setPrefSize(20, 20);
        homePane.setAlignment(Pos.CENTER);
        backBtn.setGraphic(homePane);
        backBtn.setPrefSize(36, 36);
        backBtn.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 8; -fx-border-color: rgba(255,255,255,0.08); -fx-border-radius: 8; -fx-cursor: hand; -fx-padding: 0;");
        Tooltip.install(backBtn, new Tooltip("Back to main menu"));
        backBtn.setOnMouseEntered(e -> { backBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-background-radius: 8; -fx-border-color: rgba(255,255,255,0.15); -fx-border-radius: 8; -fx-cursor: hand; -fx-padding: 0;"); homeIcon.setFill(Color.WHITE); });
        backBtn.setOnMouseExited(e -> { backBtn.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 8; -fx-border-color: rgba(255,255,255,0.08); -fx-border-radius: 8; -fx-cursor: hand; -fx-padding: 0;"); homeIcon.setFill(Color.rgb(200, 200, 220)); });
        backBtn.setOnAction(e -> {
            consoleVisible = false;
            stopSensors();
            if (overlayContainer != null) {
                overlayContainer.getChildren().removeIf(c -> c instanceof StackPane && c != overlayContainer.getChildren().get(0));
            }
        });

        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        actionRow.getChildren().addAll(actionSpacer, clearBtn, exportBtn, backBtn);
        bottomArea.getChildren().addAll(ctrlRow, actionRow);
        BorderPane.setMargin(bottomArea, new Insets(8, 0, 0, 0));
        page.setBottom(bottomArea);

        startSensors();

        // Show as overlay
        if (overlayContainer != null) {
            overlayContainer.getChildren().removeIf(c -> c instanceof StackPane && c != overlayContainer.getChildren().get(0));
            StackPane overlay = new StackPane(page);
            overlay.setStyle("-fx-background-color: #1a1a2e;");
            overlayContainer.getChildren().add(overlay);
        }
    }

    // ==================== CONSOLE HELPERS ====================

    private void handleConsoleStart() {
        if (!accountManager.hasAccounts()) {
            setStatus("✗ Please create an account first"); return;
        }
        if (selectedVersion == null || selectedVersion.isEmpty()) {
            setStatus("✗ Please select a version first"); return;
        }
        if (launcher.isRunning()) {
            setStatus("✗ Minecraft is already running"); return;
        }
        if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
        timeoutReached = false;

        // Включаем запись логов консоли в файл (если включено в настройках)
        if (settings.getBoolean("saveConsoleLog", true)) {
            FileLogManager.getInstance().enable();
        }

        updateStatusIndicator("starting");
        setStatus("▶ Launching Minecraft " + selectedVersion + "...");

        // Auto-enable console output when game starts (if currently off)
        if ("off".equals(consoleMode)) {
            consoleMode = "errors";
        }

        // Always capture MC output to console log and file, regardless of console panel state.
        launcher.setOnConsoleLine(line -> {
            if (settings.getBoolean("saveConsoleLog", true)) {
                FileLogManager.getInstance().log("[MC]", line);
            }
            if (consoleArea != null && consoleVisible && shouldShowInConsole(line)) {
                Platform.runLater(() -> {
                    consoleArea.appendText(line + "\n");
                    consoleArea.setScrollTop(Double.MAX_VALUE);
                });
            }
        });

        // Auto-show console panel so user can see MC output
        if (!consoleVisible) {
            Platform.runLater(this::showConsolePanel);
        }

        new Thread(() -> {
            var result = launcher.launchMinecraft(selectedVersion, "vanilla", exitCode -> {
                // Close console log when Minecraft exits
                FileLogManager.getInstance().disable();
                FileLogManager.getInstance().disable();
                Platform.runLater(() -> {
                    if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
                    if (exitCode != 0) {
                        updateStatusIndicator(timeoutReached ? "error" : "startup-error");
                        showCrashDialog(exitCode);
                    } else { updateStatusIndicator("off"); }
                });
            });
            Platform.runLater(() -> {
                if (result.isSuccess()) {
                    setStatus("✓ " + result.getMessage());
                    int timeoutSec = 30;
                    if (timeoutSec > 0) {
                        Timer t = new Timer("LaunchTimeout");
                        t.schedule(new TimerTask() {
                            @Override public void run() {
                                Platform.runLater(() -> { timeoutReached = true; updateStatusIndicator("running"); });
                            }
                        }, timeoutSec * 1000L);
                        timeoutTimer = t;
                    } else { timeoutReached = true; updateStatusIndicator("running"); }
                } else {
                    if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
                    updateStatusIndicator("error");
                    setStatus("✗ " + result.getMessage());
                }
            });
        }).start();
    }

    private void handleConsoleRestart() {
        if (launcher.isRunning()) {
            launcher.stopMinecraft();
            if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
            timeoutReached = false;
            updateStatusIndicator("off");
            setStatus("🔄 Restarting...");
            new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                if (!accountManager.hasAccounts() || selectedVersion == null) {
                    Platform.runLater(() -> setStatus("✗ Cannot restart")); return;
                }
                Platform.runLater(() -> updateStatusIndicator("starting"));
                // Re-set console callback for restart
                launcher.setOnConsoleLine(line -> {
                    if (settings.getBoolean("saveConsoleLog", true)) {
                        FileLogManager.getInstance().log("[MC]", line);
                    }
                    if (consoleArea != null && consoleVisible && shouldShowInConsole(line)) {
                        Platform.runLater(() -> {
                            consoleArea.appendText(line + "\n");
                            consoleArea.setScrollTop(Double.MAX_VALUE);
                        });
                    }
                });
                var result = launcher.launchMinecraft(selectedVersion, "vanilla", exitCode -> {
                    Platform.runLater(() -> {
                        if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
                        if (exitCode != 0) {
                            updateStatusIndicator(timeoutReached ? "error" : "startup-error");
                            showCrashDialog(exitCode);
                        } else { updateStatusIndicator("off"); }
                    });
                });
                Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        setStatus("✓ " + result.getMessage());
                        int timeoutSec = 30;
                        if (timeoutSec > 0) {
                            Timer t = new Timer("LaunchTimeout");
                            t.schedule(new TimerTask() {
                                @Override public void run() {
                                    Platform.runLater(() -> { timeoutReached = true; updateStatusIndicator("running"); });
                                }
                            }, timeoutSec * 1000L);
                            timeoutTimer = t;
                        } else { timeoutReached = true; updateStatusIndicator("running"); }
                    } else {
                        if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
                        updateStatusIndicator("error");
                        setStatus("✗ " + result.getMessage());
                    }
                });
            }).start();
        } else { setStatus("✗ Minecraft is not running"); }
    }

    private void handleConsoleStop() {
        if (launcher.isRunning()) {
            launcher.stopMinecraft();
            if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
            updateStatusIndicator("off");
            setStatus("✗ Minecraft stopped");
        } else { setStatus("✗ Minecraft is not running"); }
        // Закрываем файл лога консоли
        FileLogManager.getInstance().disable();
    }

    private void handleConsoleKill() {
        launcher.stopMinecraft();
        if (timeoutTimer != null) { timeoutTimer.cancel(); timeoutTimer = null; }
        updateStatusIndicator("off");
        setStatus("💀 Process killed");
        // Закрываем файл лога консоли
        FileLogManager.getInstance().disable();
    }

    // ==================== SENSORS ====================

    private VBox createSensorPanel() {
        VBox panel = new VBox(6);
        panel.setPrefWidth(180); panel.setMinWidth(180); panel.setMaxWidth(180);
        panel.setPadding(new Insets(8, 10, 8, 10));
        panel.setStyle("-fx-background-color: rgba(0,0,0,0.25); -fx-background-radius: 10; -fx-border-color: rgba(255,255,255,0.06); -fx-border-radius: 10;");

        Text sensorTitle = new Text("Monitoring");
        sensorTitle.setFont(Font.font("System", FontWeight.BOLD, 11));
        sensorTitle.setFill(Color.rgb(160, 160, 185));

        VBox cpuCard = createSensorCard();
        Text cpuIcon = new Text("⚡"); cpuIcon.setFont(Font.font("System", 16));
        cpuLabel = new Label("CPU: —"); cpuLabel.setTextFill(Color.rgb(200, 200, 220)); cpuLabel.setFont(Font.font("System", 13));
        cpuCard.getChildren().add(new HBox(6, cpuIcon, cpuLabel));

        VBox ramCard = createSensorCard();
        Text ramIcon = new Text("💾"); ramIcon.setFont(Font.font("System", 16));
        ramLabel = new Label("RAM: —"); ramLabel.setTextFill(Color.rgb(200, 200, 220)); ramLabel.setFont(Font.font("System", 13));
        ramCard.getChildren().add(new HBox(6, ramIcon, ramLabel));

        VBox netDownCard = createSensorCard();
        Text netDownIcon = new Text("📥"); netDownIcon.setFont(Font.font("System", 16));
        netDownLabel = new Label("↓ —"); netDownLabel.setTextFill(Color.rgb(200, 200, 220)); netDownLabel.setFont(Font.font("System", 13));
        netDownCard.getChildren().add(new HBox(6, netDownIcon, netDownLabel));

        VBox netUpCard = createSensorCard();
        Text netUpIcon = new Text("📤"); netUpIcon.setFont(Font.font("System", 16));
        netUpLabel = new Label("↑ —"); netUpLabel.setTextFill(Color.rgb(200, 200, 220)); netUpLabel.setFont(Font.font("System", 13));
        netUpCard.getChildren().add(new HBox(6, netUpIcon, netUpLabel));

    VBox diskCard = createSensorCard();
    Text diskIcon = new Text("💾"); diskIcon.setFont(Font.font("System", 16));
    diskLabel = new Label("SSD: —"); diskLabel.setTextFill(Color.rgb(200, 200, 220)); diskLabel.setFont(Font.font("System", 13));
    diskCard.getChildren().add(new HBox(6, diskIcon, diskLabel));

        panel.getChildren().addAll(sensorTitle, cpuCard, ramCard, netDownCard, netUpCard, diskCard);
        return panel;
    }

    private VBox createSensorCard() {
        VBox card = new VBox(2);
        card.setPadding(new Insets(6, 8, 6, 8));
        card.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 6; -fx-border-color: rgba(255,255,255,0.05); -fx-border-radius: 6;");
        return card;
    }

    private void startSensors() {
        stopSensors();
        sensorExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "Sensor-Thread");
            t.setDaemon(true);
            return t;
        });
        sensorExecutor.scheduleAtFixedRate(() -> {
            long pid = launcher.getProcessPid();
            if (pid > 0) {
                updateCpuAndRam(pid);
            } else {
                Platform.runLater(() -> {
                    if (cpuLabel != null) cpuLabel.setText("CPU: —");
                    if (ramLabel != null) ramLabel.setText("RAM: —");
                });
            }
        }, 0, 1, TimeUnit.SECONDS);
        sensorExecutor.scheduleAtFixedRate(() -> updateNetwork(), 1, 1, TimeUnit.SECONDS);
        sensorExecutor.scheduleAtFixedRate(() -> updateDisk(), 2, 60, TimeUnit.SECONDS);
        sensorExecutor.submit(this::updateDisk);
    }

    private void stopSensors() {
        if (sensorExecutor != null && !sensorExecutor.isShutdown()) {
            sensorExecutor.shutdownNow();
        }
        sensorExecutor = null;
    }

    private void updateCpuAndRam(long pid) {
        try {
            // FIX 1: locale-independent counter via .Ticks (Int64, resolution = 100ns).
            // OLD code used .TotalMilliseconds (Double) which PowerShell formats with locale-specific
            // decimal separator (e.g. "12,34" on ru-RU), breaking Java's split(",") + parseDouble.
            // FIX 2: force UTF-8 output encoding so PS never injects UTF-16 BOM into the stream.
            String psCmd =
                    "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
                    "$p = Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; " +
                    "if ($p) { Write-Output (([int64]$p.TotalProcessorTime.Ticks).ToString() + ',' + ([int64]$p.WorkingSet64).ToString()) }";
            // FIX 3: ProcessBuilder + redirectErrorStream(true) so PS errors don't deadlock the read pipe.
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", psCmd)
                    .redirectErrorStream(true)
                    .start();
            // FIX 4: waitFor() BEFORE readAllBytes() — was the deadlock source (inverse order froze the scheduler on first PS hang).
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.err.println("[PowerLaunch][cpu] PowerShell timeout 5s for pid=" + pid);
                return;
            }
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            // FIX 5: strip BOM + any non-digit/non-comma garbage before split/parse.
            output = output.replace("\uFEFF", "").replaceAll("[^0-9,]", "").trim();
            if (output.isEmpty()) {
                System.err.println("[PowerLaunch][cpu] PS returned empty (process " + pid + " not found?)");
                return;
            }
            String[] parts = output.split(",");
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                System.err.println("[PowerLaunch][cpu] PS parse fail: '" + output + "'");
                return;
            }
            long totalTicks = Long.parseLong(parts[0]);
            long ramBytes = Long.parseLong(parts[1]);
            long now = System.currentTimeMillis();

            // FIX 6: CPU update on every iteration. First tick shows "CPU: …" instead of staying at "—".
            // Idle process (Ticks don't grow) is reported honestly as "CPU: 0/X%" — never get stuck on "…".
            if (prevSensorTime > 0) {
                long deltaTicks = Math.max(0L, totalTicks - prevCpuKernel);  // idle → 0
                long deltaTime = now - prevSensorTime;
                if (deltaTime > 0) {
                    // Правильная формула: Ticks = 100ns. 10 000 000 ticks = 1 CPU-second.
                    // deltaTime в ms. CPU% (суммарный по всем ядрам) = deltaTicks / 100 / deltaTime.
                    // Пример: 50M ticks за 1000ms = 50M/100/1000 = 500% (5 ядер по 100%).
                    double cpuPercent = deltaTicks / 100.0 / deltaTime;
                    int cpuCores = Runtime.getRuntime().availableProcessors();
                    cpuPercent = Math.min(cpuPercent, 100.0 * cpuCores);
                    // Формат: <суммарный %>/<макс суммарный %> — пример: CPU: 243/400%
                    String cpuStr = String.format("CPU: %.0f/%d%%", cpuPercent, cpuCores * 100);
                    Platform.runLater(() -> { if (cpuLabel != null) cpuLabel.setText(cpuStr); });
                }
            } else {
                // First successful sample: package as "CPU: …" so user knows it's loading.
                Platform.runLater(() -> { if (cpuLabel != null) cpuLabel.setText("CPU: …"); });
            }
            prevCpuKernel = totalTicks;
            prevCpuUser = 0;
            prevSensorTime = now;            // RAM — процесс + всего в системе
            if (ramBytes > 0) {
                long totalPhys = 0;
                try {
                    com.sun.management.OperatingSystemMXBean osBean =
                            (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                    totalPhys = osBean.getTotalMemorySize();
                } catch (Throwable t) {
                    System.err.println("[PowerLaunch][ram] MXBean read failed: " + t.getMessage());
                }
                // Формат: <RAM процесса>/<всего RAM в системе>
                String procStr = (ramBytes > 1_073_741_824L) ? String.format("%.1f GB", ramBytes / 1_073_741_824.0)
                        : (ramBytes / 1_048_576) + " MB";
                String totalStr = (totalPhys > 1_073_741_824L) ? String.format("%.1f GB", totalPhys / 1_073_741_824.0)
                        : (totalPhys / 1_048_576) + " MB";
                String finalRamStr = procStr + " / " + totalStr;
                Platform.runLater(() -> { if (ramLabel != null) ramLabel.setText("RAM: " + finalRamStr); });
            }
        } catch (Throwable ex) {
            // FIX 7: never silently swallow (was: `catch (Exception ignored) {}`) —
            // print WHY sensor is empty so user can debug. Throttle stack trace to avoid
            // log flooding on persistent failures (Defender block etc.): first 2 + every 30th.
            System.err.println("[PowerLaunch][cpu/ram] sensor failure for pid=" + pid + ": " + ex.getMessage());
            cpuRamErrorCount++;
            if (cpuRamErrorCount <= 2 || cpuRamErrorCount % 30 == 0) {
                ex.printStackTrace();
            }
        }
    }

    private void updateNetwork() {
        long rxRate = 0, txRate = 0;
        try {
            long[] cumulative = readNetCumulativeWindows();
            long newRx = cumulative[0];
            long newTx = cumulative[1];
            // Cumulative counters — compute rate as delta since last sample.
            if (prevRxBytes > 0) rxRate = Math.max(0, newRx - prevRxBytes);
            if (prevTxBytes > 0) txRate = Math.max(0, newTx - prevTxBytes);
            prevRxBytes = newRx;
            prevTxBytes = newTx;
        } catch (Exception ex) {
            // Диагностика: раньше всё проглатывалось через `catch (Exception ignored) {}`
            System.err.println("[PowerLaunch][net] sensor failure: " + ex.getMessage());
        }
        String rxStr = formatSpeed(rxRate), txStr = formatSpeed(txRate);
        Platform.runLater(() -> {
            if (netDownLabel != null) netDownLabel.setText("↓ " + rxStr + "/s");
            if (netUpLabel != null) netUpLabel.setText("↑ " + txStr + "/s");
        });
    }

    /**
     * Reliable reading of cumulative network counters on Windows.
     * First tries PowerShell {@code Get-NetAdapterStatistics} (Win10+), then falls back to
     * {@code netstat -e}, which is NOT localized and works on all Windows locales.
     * @return [receivedBytes, sentBytes] cumulative across all adapters
     */
    private long[] readNetCumulativeWindows() throws java.io.IOException, InterruptedException {
        long[] result = new long[2];

        // Способ 1: PowerShell Get-NetAdapterStatistics (локаль-независимый [long] cast, безопасный для null)
        try {
            // Используем [long] cast для каждого адаптера, суммируем вручную (без Measure-Object -Sum
            // который на русской локали падает с InvalidCastFromStringToDoubleOrSingle).
            // Вывод: "<rx>,<tx>" — всегда инвариантная культура (без пробелов-разделителей тысяч).
            String psCmd =
                    "$r=0;$s=0;Get-NetAdapterStatistics|%{if($_.ReceivedBytes-ne$null){$r+=[long]$_.ReceivedBytes};if($_.SentBytes-ne$null){$s+=[long]$_.SentBytes}};echo \"$r,$s\"";
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", psCmd)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            p.destroyForcibly();
            if (done && !out.isEmpty()) {
                // Убираем всё кроме цифр и запятой (на случай если echo добавила лишнего)
                String clean = out.replaceAll("[^0-9,]", "");
                String[] parts = clean.split(",");
                if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                    // Убираем возможную дробную часть (на всякий случай)
                    String rxStr = parts[0].contains(".") ? parts[0].substring(0, parts[0].indexOf('.')) : parts[0];
                    String txStr = parts[1].contains(".") ? parts[1].substring(0, parts[1].indexOf('.')) : parts[1];
                    result[0] = Long.parseLong(rxStr);
                    result[1] = Long.parseLong(txStr);
                    return result;
                }
                System.err.println("[PowerLaunch][net] PS unparseable output: '" + out.replaceAll("\\s+", " ").trim() + "'");
            }
        } catch (Exception psEx) {
            System.err.println("[PowerLaunch][net] Get-NetAdapterStatistics failed: " + psEx.getMessage() + "; fallback to netstat -e");
        }

        // Способ 2: netstat -e — родная команда Windows, вывод ВСЕГДА на английском
        Process p2 = new ProcessBuilder("netstat", "-e").redirectErrorStream(true).start();
        String out2 = new String(p2.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        boolean done2 = p2.waitFor(5, TimeUnit.SECONDS);
        p2.destroyForcibly();
        if (!done2) {
            throw new java.io.IOException("netstat -e timeout");
        }
        // Парсим строку с двумя большими числами — заголовок может быть "Bytes" (EN), "Байт" (RU), "Bytes" (DE) и т.д.
        // Ищем любую строку, где есть два числа >= 4 цифр (это RX/TX bytes).
        // Lockdown-friendly regex без Unicode, локаль-независимый.
        java.util.regex.Pattern bytesLine = java.util.regex.Pattern.compile(
                "^[^\\d\\n]*\\b(\\d{4,})\\b[^\\d\\n]*\\b(\\d{4,})\\b[^\\d\\n]*$",
                java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = bytesLine.matcher(out2);
        if (m.find()) {
            result[0] = Long.parseLong(m.group(1));
            result[1] = Long.parseLong(m.group(2));
        } else {
            throw new java.io.IOException("netstat -e output unparseable: " + out2.replaceAll("\\s+", " ").trim());
        }
        return result;
    }

    private String formatSpeed(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes > 1_073_741_824L) return String.format("%.2f GB", bytes / 1_073_741_824.0);
        else if (bytes > 1_048_576) return String.format("%.1f MB", bytes / 1_048_576.0);
        else if (bytes > 1024) return String.format("%.0f KB", bytes / 1024.0);
        else return bytes + " B";
    }

    private void updateDisk() {
        long now = System.currentTimeMillis();
        if (lastDiskSize >= 0 && now - prevDiskTime < 60000) return;
        prevDiskTime = now;
        new Thread(() -> {
            try {
                String gameDir = launcher.getGameDir();
                java.nio.file.Path dir = java.nio.file.Paths.get(gameDir);
                if (java.nio.file.Files.exists(dir)) {
                    long size = getFolderSize(dir);
                    lastDiskSize = size;
                    String sizeStr;            sizeStr = (size > 1_073_741_824L) 
                    ? String.format("SSD: %.1f GB", size / 1_073_741_824.0) 
                    : (size > 1_048_576) 
                        ? String.format("SSD: %.0f MB", size / 1_048_576.0) 
                        : String.format("SSD: %.0f KB", size / 1024.0);
                    String finalSizeStr = sizeStr;
                    Platform.runLater(() -> { if (diskLabel != null) diskLabel.setText(finalSizeStr); });
                }
            } catch (Exception ignored) {}
        }, "Disk-Sensor").start();
    }

    private long getFolderSize(java.nio.file.Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            return walk.filter(java.nio.file.Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (java.io.IOException e) {
            return lastDiskSize >= 0 ? lastDiskSize : 0;
        }
    }

    private void exportConsoleLogs() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Logs");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text file", "*.txt"));
        fc.setInitialFileName("powerlaunch-console-" + java.time.LocalDate.now() + ".txt");
        File file = fc.showSaveDialog(view.getScene().getWindow());
        if (file != null) {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("=== PowerLaunch Console Log ===\r\n");
                sb.append("Date: ").append(java.time.LocalDateTime.now()).append("\r\n\r\n");
                for (String line : launcher.getConsoleLog()) {
                    sb.append(line).append("\r\n");
                }
                java.nio.file.Files.writeString(file.toPath(), sb.toString());
                setStatus("✓ Logs exported: " + file.getName());
            } catch (java.io.IOException ex) {
                setStatus("✗ Export error: " + ex.getMessage());
            }
        }
    }

    private boolean shouldShowInConsole(String line) {
        if ("all".equals(consoleMode)) return true;
        if ("errors".equals(consoleMode)) {
            return line.contains("ERROR") || line.contains("Exception")
                || line.contains("error") || line.contains("Error")
                || line.contains("WARN") || line.contains("warn")
                || line.contains("FAILED") || line.contains("failed");
        }
        return false;
    }

    private void showCrashDialog(int exitCode) {
        String gameDir = launcher.getGameDir();
        String crashReportsDir = gameDir + File.separator + "crash-reports";

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Game Crash");
        alert.setHeaderText("💥 Game crashed (exit code: " + exitCode + ")");

        VBox content2 = new VBox(10);
        content2.setPadding(new Insets(5, 0, 5, 0));

        Label info = new Label("Crash reports are located in:");
        info.setTextFill(Color.rgb(200, 200, 220));
        info.setFont(Font.font("System", 12));

        HBox pathRow = new HBox(8);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        TextField pathField = new TextField(crashReportsDir);
        pathField.setEditable(false);
        pathField.setPrefWidth(400);
        pathField.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-text-fill: rgb(180,180,210); -fx-font-size: 12; -fx-font-family: 'Consolas','Courier New',monospace; -fx-border-color: rgba(255,255,255,0.08); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 6 10;");

        Button openBtn = new Button("📂");
        openBtn.setPrefSize(32, 32);
        openBtn.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 6; -fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 6; -fx-cursor: hand; -fx-padding: 0;");
        Tooltip.install(openBtn, new Tooltip("Open crash reports folder"));
        openBtn.setOnAction(ev -> { try { Runtime.getRuntime().exec("explorer.exe \"" + crashReportsDir + "\""); } catch (Exception ex) { setStatus("✗ Failed to open crash reports directory"); } });

        pathRow.getChildren().addAll(pathField, openBtn);
        content2.getChildren().addAll(info, pathRow);

        DialogPane dp = alert.getDialogPane();
        dp.setContent(content2);
        dp.setStyle("-fx-background-color: #1a1a2e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12; -fx-background-radius: 12;");
        Scene s = dp.getScene();
        if (s != null) s.setFill(Color.valueOf("#1a1a2e"));
        var hdr = dp.lookup(".header-panel");
        if (hdr != null) hdr.setStyle("-fx-background-color: transparent;");
        var hdrText = dp.lookup(".header-text");
        if (hdrText instanceof Label hl) { hl.setTextFill(Color.WHITE); hl.setFont(Font.font("System", FontWeight.BOLD, 16)); }

        Button okBtn = (Button) dp.lookupButton(ButtonType.OK);
        if (okBtn != null) okBtn.setStyle("-fx-background-color: linear-gradient(to right, #e94560, #c23152); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");

        alert.initOwner(view.getScene().getWindow());
        alert.showAndWait();
    }

    // ==================== STATUS INDICATOR ====================

    private void updateStatusIndicator(String status) {
        this.launcherStatus = status;
        Platform.runLater(() -> {
            if (statusIndicator == null) return;
            switch (status) {
                case "off" -> { statusIndicator.setText("●  Off"); statusIndicator.setTextFill(Color.web("#ef4444")); }
                case "starting" -> { statusIndicator.setText("●  Starting..."); statusIndicator.setTextFill(Color.web("#f59e0b")); }
                case "running" -> { statusIndicator.setText("●  Running"); statusIndicator.setTextFill(Color.web("#10b981")); }
                case "startup-error" -> { statusIndicator.setText("●  Launch error"); statusIndicator.setTextFill(Color.web("#ef4444")); }
                case "error" -> { statusIndicator.setText("●  Error"); statusIndicator.setTextFill(Color.web("#ef4444")); }
                case "timeout" -> { statusIndicator.setText("●  Timed out"); statusIndicator.setTextFill(Color.web("#ef4444")); }
            }
        });
    }

    private void styleSettingsButton(Button btn, String color, String hoverColor) {
        btn.setStyle(
                "-fx-background-color: " + color + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 20;" +
                "-fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: " + hoverColor + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 20;" +
                "-fx-cursor: hand;"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: " + color + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 10 20;" +
                "-fx-cursor: hand;"
        ));
    }

    private void showCreateAccountDialog(Runnable onSuccess) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Create Account");
        dialog.setHeaderText("Enter your Minecraft nickname");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.setStyle(
                "-fx-background-color: #1a1a2e;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 12;" +
                "-fx-background-radius: 12;"
        );
        dialogPane.setPrefWidth(380);

        Scene scene = dialogPane.getScene();
        if (scene != null) scene.setFill(Color.valueOf("#1a1a2e"));

        var header = dialogPane.lookup(".header-panel");
        if (header != null) header.setStyle("-fx-background-color: transparent;");
        var headerText = dialogPane.lookup(".header-text");
        if (headerText instanceof Label hl) {
            hl.setTextFill(Color.WHITE);
            hl.setFont(Font.font("System", FontWeight.BOLD, 16));
        }

        TextField nickField = new TextField();
        nickField.setPromptText("Your nickname");
        nickField.setMaxWidth(300);
        nickField.setStyle(
                "-fx-background-color: rgba(255,255,255,0.08);" +
                "-fx-text-fill: white;" +
                "-fx-prompt-text-fill: rgba(255,255,255,0.35);" +
                "-fx-border-color: rgba(255,255,255,0.12);" +
                "-fx-border-radius: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 10 14;" +
                "-fx-font-size: 15;"
        );

        VBox contentBox = new VBox(12, nickField);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.setPadding(new Insets(10, 0, 5, 0));
        dialogPane.setContent(contentBox);

        ButtonType confirmType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(confirmType, cancelType);

        Button confirmBtn = (Button) dialogPane.lookupButton(confirmType);
        if (confirmBtn != null) {
            confirmBtn.setStyle(
                    "-fx-background-color: linear-gradient(to right, #e94560, #c23152);" +
                    "-fx-text-fill: white;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 8;" +
                    "-fx-padding: 8 20;" +
                    "-fx-cursor: hand;"
            );
        }
        Button cancelBtn = (Button) dialogPane.lookupButton(cancelType);
        if (cancelBtn != null) {
            cancelBtn.setStyle(
                    "-fx-background-color: transparent;" +
                    "-fx-text-fill: rgba(255,255,255,0.6);" +
                    "-fx-border-color: rgba(255,255,255,0.2);" +
                    "-fx-border-radius: 8;" +
                    "-fx-background-radius: 8;" +
                    "-fx-padding: 8 20;" +
                    "-fx-cursor: hand;"
            );
        }

        nickField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && confirmBtn != null) confirmBtn.fire();
        });

        dialog.setResultConverter(btn -> btn == confirmType ? nickField.getText() : null);
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.initOwner(view.getScene().getWindow());

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(nick -> {
            AuthManager.AuthResult authResult = auth.loginOffline(nick);
            if (authResult.isSuccess()) {
                accountManager.createAccount(nick);
                settings.set("username", nick);
                setStatus("✓ Аккаунт \"" + nick + "\" created!");
                if (onSuccess != null) onSuccess.run();
            } else {
                setStatus("✗ " + authResult.getMessage());
                showCreateAccountDialog(onSuccess);
            }
        });
    }

    private void showDeleteAccountConfirm(String username, Runnable onSuccess) {
        // If it's the last account, show simple dialog
        if (accountManager.getAccountCount() <= 1) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Delete Account");
            alert.setHeaderText("Delete Account \"" + username + "\"?");
            alert.setContentText("This action cannot be undone. The account will be permanently deleted.");

            DialogPane dp = alert.getDialogPane();
            dp.setStyle("-fx-background-color: #1a1a2e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12; -fx-background-radius: 12;");
            alert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    accountManager.removeAccount(username);
                    auth.logout();
                    setStatus("✓ Аккаунт \"" + username + "\" deleted");
                    if (onSuccess != null) onSuccess.run();
                }
            });
            return;
        }

        // Full confirmation dialog with slider
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Confirm Deletion");
        dialog.setHeaderText("Delete Account \"" + username + "\"?");

        DialogPane dp = dialog.getDialogPane();
        dp.setStyle(
                "-fx-background-color: #1a1a2e;" +
                "-fx-border-color: rgba(255,255,255,0.1);" +
                "-fx-border-radius: 12;" +
                "-fx-background-radius: 12;"
        );
        dp.setPrefWidth(420);

        Scene dlgScene = dp.getScene();
        if (dlgScene != null) dlgScene.setFill(Color.valueOf("#1a1a2e"));

        var hdr = dp.lookup(".header-panel");
        if (hdr != null) hdr.setStyle("-fx-background-color: transparent;");
        var hdrText = dp.lookup(".header-text");
        if (hdrText instanceof Label hl) {
            hl.setTextFill(Color.WHITE);
            hl.setFont(Font.font("System", FontWeight.BOLD, 16));
        }

        VBox confirmContent = new VBox(15);
        confirmContent.setAlignment(Pos.CENTER);
        confirmContent.setPadding(new Insets(15, 10, 5, 10));

        Label warningLabel = new Label("⚠ Account will be permanently deleted!");
        warningLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        warningLabel.setTextFill(Color.web("#ef4444"));
        warningLabel.setAlignment(Pos.CENTER);

        Label instructionLabel = new Label("Slide the slider right to confirm:");
        instructionLabel.setFont(Font.font("System", 12));
        instructionLabel.setTextFill(Color.rgb(180, 180, 200));

        // Confirmation slider
        Slider confirmSlider = new Slider(0, 100, 0);
        confirmSlider.setPrefWidth(350);
        confirmSlider.setShowTickLabels(false);
        confirmSlider.setShowTickMarks(false);
        confirmSlider.setStyle(
                "-fx-control-inner-background: #ef4444;" +
                "-fx-background-color: transparent;"
        );

        Label sliderLabel = new Label("Confirm deletion");
        sliderLabel.setFont(Font.font("System", 12));
        sliderLabel.setTextFill(Color.rgb(200, 200, 220));

        Button confirmDeleteBtn = new Button("🗑️ Confirm deletion");
        confirmDeleteBtn.setDisable(true);
        confirmDeleteBtn.setPrefWidth(300);
        confirmDeleteBtn.setStyle(
                "-fx-background-color: #6b7280;" +
                "-fx-text-fill: white;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 8;" +
                "-fx-padding: 10 20;"
        );

        confirmSlider.valueProperty().addListener((obs, old, val) -> {
            boolean confirmed = val.doubleValue() >= 90;
            confirmDeleteBtn.setDisable(!confirmed);
            if (confirmed) {
                sliderLabel.setText("✓ Confirmed");
                sliderLabel.setTextFill(Color.web("#4ade80"));
                confirmDeleteBtn.setStyle(
                        "-fx-background-color: #ef4444;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 8;" +
                        "-fx-padding: 10 20;" +
                        "-fx-cursor: hand;"
                );
            } else {
                sliderLabel.setText("Slide to end (" + (int) val.doubleValue() + "%)");
                sliderLabel.setTextFill(Color.rgb(200, 200, 220));
                confirmDeleteBtn.setStyle(
                        "-fx-background-color: #6b7280;" +
                        "-fx-text-fill: white;" +
                        "-fx-font-weight: bold;" +
                        "-fx-background-radius: 8;" +
                        "-fx-padding: 10 20;"
                );
            }
        });

        confirmContent.getChildren().addAll(warningLabel, instructionLabel, confirmSlider, sliderLabel, confirmDeleteBtn);
        dp.setContent(confirmContent);

        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dp.getButtonTypes().add(cancelType);

        Button cancelBtn = (Button) dp.lookupButton(cancelType);
        if (cancelBtn != null) {
            cancelBtn.setStyle(
                    "-fx-background-color: transparent;" +
                    "-fx-text-fill: rgba(255,255,255,0.6);" +
                    "-fx-border-color: rgba(255,255,255,0.2);" +
                    "-fx-border-radius: 8;" +
                    "-fx-background-radius: 8;" +
                    "-fx-padding: 8 20;" +
                    "-fx-cursor: hand;"
            );
        }

        confirmDeleteBtn.setOnAction(e -> {
            accountManager.removeAccount(username);
            auth.logout();
            setStatus("✓ Аккаунт \"" + username + "\" deleted");
            dialog.close();
            if (onSuccess != null) onSuccess.run();
        });

        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.initOwner(view.getScene().getWindow());
        dialog.showAndWait();
    }

    // ==================== VERSION SETTINGS ====================

    private void showVersionSettings() {
        showPage("version-settings");
    }

    private void showVersionSettingsPage() {
        BorderPane page = new BorderPane();
        page.setPadding(new Insets(20));
        page.setStyle("-fx-background-color: transparent;");

        Text title = new Text("⚙️ Manage Versions");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setFill(Color.WHITE);
        BorderPane.setMargin(title, new Insets(0, 0, 15, 0));
        page.setTop(title);

        ListView<String> versionList = new ListView<>();
        versionList.setStyle(
                "-fx-background-color: rgba(255,255,255,0.03);" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: rgba(255,255,255,0.06);" +
                "-fx-border-radius: 10;"
        );
        versionList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    boolean isCurrent = v.equals(selectedVersion);
                    HBox cell = new HBox(10);
                    cell.setAlignment(Pos.CENTER_LEFT);
                    cell.setPadding(new Insets(8, 12, 8, 12));

                    Label nameLabel = new Label((isCurrent ? "✓ " : "   ") + v);
                    nameLabel.setFont(Font.font("System", isCurrent ? FontWeight.BOLD : FontWeight.NORMAL, 14));
                    nameLabel.setTextFill(isCurrent ? Color.web("#3b82f6") : Color.WHITE);

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    if (isCurrent) {
                        Label badge = new Label("Selected");
                        badge.setStyle(
                                "-fx-background-color: rgba(59,130,246,0.2);" +
                                "-fx-text-fill: #3b82f6;" +
                                "-fx-font-size: 10;" +
                                "-fx-font-weight: bold;" +
                                "-fx-padding: 2 8;" +
                                "-fx-background-radius: 4;"
                        );
                        cell.getChildren().addAll(nameLabel, spacer, badge);
                    } else {
                        cell.getChildren().addAll(nameLabel, spacer);
                    }

                    setGraphic(cell);
                    setStyle("-fx-background-color: transparent;");
                }
            }
        });

        // Populate
        versionList.getItems().setAll(versionManager.getInstalledVersions());
        versionList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            selectedVersion = selected;
        });

        // Buttons
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPadding(new Insets(15, 0, 0, 0));

        Button deleteBtn = new Button("🗑️ Delete версию");
        styleSettingsButton(deleteBtn, "#ef4444", "#dc2626");
        deleteBtn.setOnAction(e -> {
            if (selectedVersion == null || selectedVersion.isEmpty()) {
                setStatus("✗ Select a version from the list");
                return;
            }
            // Confirm
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Delete Version");
            alert.setHeaderText("Delete версию \"" + selectedVersion + "\"?");
            alert.setContentText("Version will be removed from the list. Files will remain in the folder.");

            DialogPane dp = alert.getDialogPane();
            dp.setStyle("-fx-background-color: #1a1a2e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12; -fx-background-radius: 12;");
            alert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    String removed = selectedVersion;
                    versionManager.removeVersion(selectedVersion);
                    selectedVersion = versionManager.getCurrentVersion();
                    setStatus("✓ Версия \"" + removed + "\" removed from list");
                    showVersionSettingsPage();
                }
            });
        });

        Button backBtn = new Button("◀ Back");
        styleSettingsButton(backBtn, "rgba(255,255,255,0.08)", "rgba(255,255,255,0.12)");
        backBtn.setTextFill(Color.rgb(200, 200, 220));
        backBtn.setOnAction(e -> showMainPage());

        buttons.getChildren().addAll(deleteBtn, backBtn);
        page.setBottom(buttons);

        ScrollPane scrollPane = new ScrollPane(versionList);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        page.setCenter(scrollPane);

        FadeTransition fade = new FadeTransition(Duration.millis(300), page);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        mainContent.getChildren().setAll(page);
    }

    private void updateToggleStyle(ToggleButton btn) {
        if (btn.isSelected()) {
            btn.setStyle(
                    "-fx-background-color: #10b981;" +
                    "-fx-text-fill: white;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 6;" +
                    "-fx-padding: 6 16;" +
                    "-fx-cursor: hand;"
            );
        } else {
            btn.setStyle(
                    "-fx-background-color: rgba(255,255,255,0.08);" +
                    "-fx-text-fill: rgba(255,255,255,0.6);" +
                    "-fx-font-weight: normal;" +
                    "-fx-background-radius: 6;" +
                    "-fx-padding: 6 16;" +
                    "-fx-cursor: hand;"
            );
        }
    }

    private void refreshServerList(ListView<ServerEntry> listView) {
        listView.getItems().setAll(serverManager.getServers());
    }

    private void showAddServerDialog(ListView<ServerEntry> listView) {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle("Add Server");
        dialog.setHeaderText("Enter server name and IP");

        DialogPane dp = dialog.getDialogPane();
        dp.setStyle("-fx-background-color: #1a1a2e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12; -fx-background-radius: 12;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 20, 20));

        Label nameLabel = new Label("Name:");
        nameLabel.setTextFill(Color.rgb(200, 200, 220));
        TextField nameField = new TextField();
        nameField.setPromptText("My Server");
        nameField.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12;");

        Label ipLabel = new Label("IP Address:");
        ipLabel.setTextFill(Color.rgb(200, 200, 220));
        TextField ipField = new TextField();
        ipField.setPromptText("play.example.com:25565");
        ipField.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12;");

        grid.add(nameLabel, 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(ipLabel, 0, 1);
        grid.add(ipField, 1, 1);

        dp.setContent(grid);

        ButtonType addBtn = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dp.getButtonTypes().addAll(addBtn, cancelBtn);

        Node okNode = dp.lookupButton(addBtn);
        if (okNode instanceof Button ok) {
            ok.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");
        }
        Node cancelNode2 = dp.lookupButton(cancelBtn);
        if (cancelNode2 instanceof Button cancel) {
            cancel.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(255,255,255,0.6); -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");
        }

        dialog.setResultConverter(dialogBtn -> {
            if (dialogBtn == addBtn) {
                return List.of(nameField.getText().trim(), ipField.getText().trim());
            }
            return null;
        });

        dialog.initOwner(view.getScene().getWindow());
        dialog.showAndWait().ifPresent(result -> {
            if (result.size() >= 2 && !result.get(0).isEmpty() && !result.get(1).isEmpty()) {
                serverManager.addServer(result.get(0), result.get(1), "");
                refreshServerList(listView);
                setStatus("✓ Сервер \"" + result.get(0) + "\" added");
            }
        });
    }

    private void showEditServerDialog(ListView<ServerEntry> listView, int idx) {
        ServerEntry entry = serverManager.getServers().get(idx);
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle("Edit Server");
        dialog.setHeaderText("Edit server name and IP");

        DialogPane dp = dialog.getDialogPane();
        dp.setStyle("-fx-background-color: #1a1a2e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12; -fx-background-radius: 12;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 20, 20));

        Label nameLabel = new Label("Name:");
        nameLabel.setTextFill(Color.rgb(200, 200, 220));
        TextField nameField = new TextField(entry.getName());
        nameField.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12;");

        Label ipLabel = new Label("IP Address:");
        ipLabel.setTextFill(Color.rgb(200, 200, 220));
        TextField ipField = new TextField(entry.getDisplayIp());
        ipField.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: white; -fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8 12;");

        grid.add(nameLabel, 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(ipLabel, 0, 1);
        grid.add(ipField, 1, 1);

        dp.setContent(grid);

        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dp.getButtonTypes().addAll(saveBtn, cancelBtn);

        Node okNode = dp.lookupButton(saveBtn);
        if (okNode instanceof Button ok) {
            ok.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");
        }
        Node cancelNode2 = dp.lookupButton(cancelBtn);
        if (cancelNode2 instanceof Button cancel) {
            cancel.setStyle("-fx-background-color: transparent; -fx-text-fill: rgba(255,255,255,0.6); -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 8 20; -fx-cursor: hand;");
        }

        dialog.setResultConverter(dialogBtn -> {
            if (dialogBtn == saveBtn) {
                return List.of(nameField.getText().trim(), ipField.getText().trim());
            }
            return null;
        });

        dialog.initOwner(view.getScene().getWindow());
        dialog.showAndWait().ifPresent(result -> {
            if (result.size() >= 2 && !result.get(0).isEmpty() && !result.get(1).isEmpty()) {
                serverManager.removeServer(idx);
                serverManager.addServer(result.get(0), result.get(1), "");
                refreshServerList(listView);
                setStatus("✓ Сервер \"" + result.get(0) + "\" updated");
            }
        });
    }


}
