package com.powerlaunch.auth;

import java.util.UUID;

public class AuthManager {
    private static AuthManager instance;
    private String username;
    private UUID uuid;
    private boolean loggedIn;

    private AuthManager() {
        this.loggedIn = false;
        this.username = "";
    }

    public static synchronized AuthManager getInstance() {
        if (instance == null) {
            instance = new AuthManager();
        }
        return instance;
    }

    public static class AuthResult {
        private final boolean success;
        private final String message;
        private final String username;
        private final UUID uuid;

        public AuthResult(boolean success, String message, String username, UUID uuid) {
            this.success = success;
            this.message = message;
            this.username = username;
            this.uuid = uuid;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getUsername() { return username; }
        public UUID getUuid() { return uuid; }
    }

    public AuthResult loginOffline(String username) {
        if (username == null || username.trim().isEmpty()) {
            return new AuthResult(false, "Username cannot be empty", null, null);
        }

        username = username.trim();

        if (username.length() < 3) {
            return new AuthResult(false, "Username must be at least 3 characters", null, null);
        }

        if (username.length() > 16) {
            return new AuthResult(false, "Username cannot be longer than 16 characters", null, null);
        }

        if (!username.matches("[a-zA-Z0-9_]+")) {
            return new AuthResult(false, "Username may only contain letters, numbers, and underscores", null, null);
        }

        this.username = username;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());
        this.loggedIn = true;

        return new AuthResult(true, "Logged in as " + username, username, this.uuid);
    }

    public void logout() {
        this.username = "";
        this.uuid = null;
        this.loggedIn = false;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public String getUsername() {
        return username;
    }

    public UUID getUuid() {
        return uuid;
    }
}
