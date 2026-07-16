package com.powerlaunch.gui;

import com.powerlaunch.Main;
import com.powerlaunch.auth.AuthManager;
import com.powerlaunch.settings.SettingsManager;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

public class LoginController {
    private final VBox view;

    public LoginController() {
        view = createLoginView();
    }

    public VBox getView() {
        return view;
    }

    private VBox createLoginView() {
        VBox loginRoot = new VBox();
        loginRoot.setAlignment(Pos.CENTER);
        loginRoot.setId("login-root");
        loginRoot.setPrefSize(960, 640);
        loginRoot.setMinSize(800, 500);

        // Background gradient overlay
        loginRoot.setStyle("-fx-background-color: linear-gradient(to bottom right, #1a1a2e, #16213e, #0f3460);");

        // Center content
        VBox centerBox = new VBox(20);
        centerBox.setAlignment(Pos.CENTER);
        centerBox.setMaxWidth(420);
        centerBox.setPadding(new Insets(40));

        // Glass effect background
        centerBox.setStyle(
                "-fx-background-color: rgba(255, 255, 255, 0.05);" +
                "-fx-background-radius: 16;" +
                "-fx-border-color: rgba(255, 255, 255, 0.1);" +
                "-fx-border-radius: 16;" +
                "-fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.3), 20, 0, 0, 10);"
        );

        // Logo / Title
        VBox titleBox = new VBox(5);
        titleBox.setAlignment(Pos.CENTER);

        Text title = new Text("⚡ PowerLaunch");
        title.setFont(Font.font("System", FontWeight.BOLD, 36));
        title.setFill(Color.WHITE);

        Text subtitle = new Text("Minecraft Launcher");
        subtitle.setFont(Font.font("System", FontWeight.LIGHT, 16));
        subtitle.setFill(Color.rgb(180, 180, 200));

        titleBox.getChildren().addAll(title, subtitle);

        // Separator
        Separator separator = new Separator();
        separator.setStyle("-fx-background-color: rgba(255, 255, 255, 0.1);");
        separator.setPadding(new Insets(5, 0, 5, 0));

        // Login form
        VBox formBox = new VBox(12);
        formBox.setAlignment(Pos.CENTER);

        Label usernameLabel = new Label("Имя пользователя");
        usernameLabel.setFont(Font.font("System", FontWeight.MEDIUM, 13));
        usernameLabel.setTextFill(Color.rgb(200, 200, 220));

        TextField usernameField = new TextField();
        usernameField.setPromptText("Введите ваш ник");
        usernameField.setMaxWidth(340);
        usernameField.setPrefHeight(44);
        usernameField.setId("login-field");
        usernameField.setStyle(
                "-fx-background-color: rgba(255, 255, 255, 0.08);" +
                "-fx-text-fill: white;" +
                "-fx-prompt-text-fill: rgba(255, 255, 255, 0.35);" +
                "-fx-border-color: rgba(255, 255, 255, 0.12);" +
                "-fx-border-radius: 10;" +
                "-fx-background-radius: 10;" +
                "-fx-padding: 8 16;" +
                "-fx-font-size: 15;"
        );

        // Pre-fill from saved settings
        String savedUsername = SettingsManager.getInstance().getString("username", "");
        if (!savedUsername.isEmpty()) {
            usernameField.setText(savedUsername);
        }

        Label errorLabel = new Label();
        errorLabel.setTextFill(Color.rgb(255, 100, 100));
        errorLabel.setFont(Font.font("System", FontWeight.MEDIUM, 12));
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        Button loginButton = new Button("Войти");
        loginButton.setMaxWidth(340);
        loginButton.setPrefHeight(48);
        loginButton.setId("login-button");
        loginButton.setStyle(
                "-fx-background-color: linear-gradient(to right, #e94560, #c23152);" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 16;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 10;" +
                "-fx-border-radius: 10;" +
                "-fx-cursor: hand;"
        );

        // Hover effect
        loginButton.setOnMouseEntered(e -> {
            loginButton.setStyle(
                    "-fx-background-color: linear-gradient(to right, #ff5a77, #e94560);" +
                    "-fx-text-fill: white;" +
                    "-fx-font-size: 16;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 10;" +
                    "-fx-border-radius: 10;" +
                    "-fx-cursor: hand;" +
                    "-fx-effect: dropshadow(gaussian, rgba(233, 69, 96, 0.4), 15, 0, 0, 5);"
            );
        });
        loginButton.setOnMouseExited(e -> {
            loginButton.setStyle(
                    "-fx-background-color: linear-gradient(to right, #e94560, #c23152);" +
                    "-fx-text-fill: white;" +
                    "-fx-font-size: 16;" +
                    "-fx-font-weight: bold;" +
                    "-fx-background-radius: 10;" +
                    "-fx-border-radius: 10;" +
                    "-fx-cursor: hand;"
            );
        });

        // Login button action
        loginButton.setOnAction(e -> performLogin(usernameField, errorLabel));
        usernameField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                performLogin(usernameField, errorLabel);
            }
        });

        formBox.getChildren().addAll(usernameLabel, usernameField, errorLabel, loginButton);

        // Footer text
        Text footerText = new Text("Оффлайн-режим · Редактирование скинов · Модпаки");
        footerText.setFont(Font.font("System", 11));
        footerText.setFill(Color.rgb(140, 140, 170));

        centerBox.getChildren().addAll(titleBox, separator, formBox, footerText);
        loginRoot.getChildren().add(centerBox);

        // Entrance animation
        FadeTransition fadeIn = new FadeTransition(Duration.millis(600), centerBox);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();

        return loginRoot;
    }

    private void performLogin(TextField usernameField, Label errorLabel) {
        String username = usernameField.getText();
        AuthManager.AuthResult result = AuthManager.getInstance().loginOffline(username);

        if (result.isSuccess()) {
            // Save username
            SettingsManager.getInstance().set("username", username);

            // Fade out and switch to main screen
            FadeTransition fadeOut = new FadeTransition(Duration.millis(400), view);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(e -> Main.showMainScreen());
            fadeOut.play();
        } else {
            errorLabel.setText("⚠ " + result.getMessage());
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
    }
}
