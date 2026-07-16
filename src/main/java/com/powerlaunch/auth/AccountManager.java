package com.powerlaunch.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AccountManager {
    private static AccountManager instance;
    private final Path accountsPath;
    private final Gson gson;
    private List<Account> accounts;
    private Account currentAccount;

    public static class Account {
        private final String username;
        private final String uuid;
        private final long createdAt;

        public Account(String username, String uuid) {
            this.username = username;
            this.uuid = uuid;
            this.createdAt = System.currentTimeMillis();
        }

        public String getUsername() { return username; }
        public String getUuid() { return uuid; }
        public long getCreatedAt() { return createdAt; }
    }

    private AccountManager() {
        gson = new GsonBuilder().setPrettyPrinting().create();
        accountsPath = com.powerlaunch.launcher.LauncherHomeProvider.getAccountsFile();
        accounts = new ArrayList<>();
        load();
    }

    public static synchronized AccountManager getInstance() {
        if (instance == null) {
            instance = new AccountManager();
        }
        return instance;
    }

    public void load() {
        try {
            if (Files.exists(accountsPath)) {
                String content = Files.readString(accountsPath);
                Type type = new TypeToken<List<Account>>() {}.getType();
                List<Account> loaded = gson.fromJson(content, type);
                if (loaded != null && !loaded.isEmpty()) {
                    accounts = loaded;
                    // Set first account as current if we have a saved username
                    String savedUsername = com.powerlaunch.settings.SettingsManager.getInstance().getString("username", "");
                    if (!savedUsername.isEmpty()) {
                        for (Account acc : accounts) {
                            if (acc.getUsername().equals(savedUsername)) {
                                currentAccount = acc;
                                break;
                            }
                        }
                    }
                    if (currentAccount == null) {
                        currentAccount = accounts.get(0);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load accounts: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(accountsPath.getParent());
            Files.writeString(accountsPath, gson.toJson(accounts));
        } catch (IOException e) {
            System.err.println("Failed to save accounts: " + e.getMessage());
        }
    }

    public Account createAccount(String username) {
        username = username.trim();
        // Check if already exists
        for (Account acc : accounts) {
            if (acc.getUsername().equalsIgnoreCase(username)) {
                currentAccount = acc;
                save();
                return acc;
            }
        }
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).toString();
        Account acc = new Account(username, uuid);
        accounts.add(acc);
        currentAccount = acc;
        save();
        return acc;
    }

    public boolean removeAccount(String username) {
        boolean removed = accounts.removeIf(acc -> acc.getUsername().equals(username));
        if (removed) {
            if (currentAccount != null && currentAccount.getUsername().equals(username)) {
                currentAccount = accounts.isEmpty() ? null : accounts.get(0);
            }
            save();
        }
        return removed;
    }

    public boolean selectAccount(String username) {
        for (Account acc : accounts) {
            if (acc.getUsername().equals(username)) {
                currentAccount = acc;
                com.powerlaunch.settings.SettingsManager.getInstance().set("username", username);
                return true;
            }
        }
        return false;
    }

    public List<Account> getAccounts() {
        return new ArrayList<>(accounts);
    }

    public Account getCurrentAccount() {
        return currentAccount;
    }

    public boolean hasAccounts() {
        return !accounts.isEmpty();
    }

    public int getAccountCount() {
        return accounts.size();
    }
}
