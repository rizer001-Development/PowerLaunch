package com.powerlaunch.auth;

import com.powerlaunch.storage.AppDatabase;
import com.powerlaunch.storage.AppDatabase.Account;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AccountManager {
    private static AccountManager instance;
    private final AppDatabase db;
    private List<Account> accounts;
    private Account currentAccount;

    private AccountManager() {
        db = AppDatabase.getInstance();
        accounts = db.getAllAccounts();
        // restore current account from saved username
        String saved = com.powerlaunch.settings.SettingsManager.getInstance()
                .getString("username", "");
        if (!saved.isEmpty()) {
            for (Account a : accounts) {
                if (a.username().equals(saved)) { currentAccount = a; break; }
            }
        }
        if (currentAccount == null && !accounts.isEmpty()) currentAccount = accounts.get(0);
    }

    public static synchronized AccountManager getInstance() {
        if (instance == null) instance = new AccountManager();
        return instance;
    }

    public Account createAccount(String username) {
        username = username.trim();
        for (Account a : accounts) {
            if (a.username().equalsIgnoreCase(username)) {
                currentAccount = a;
                return a;
            }
        }
        String uuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes()).toString();
        db.insertAccount(username, uuid);
        accounts = db.getAllAccounts();
        for (Account a : accounts) {
            if (a.username().equals(username)) { currentAccount = a; break; }
        }
        return currentAccount;
    }

    public boolean removeAccount(String username) {
        db.deleteAccount(username);
        List<Account> fresh = db.getAllAccounts();
        boolean removed = fresh.size() < accounts.size();
        accounts = fresh;
        if (removed && currentAccount != null && currentAccount.username().equals(username)) {
            currentAccount = accounts.isEmpty() ? null : accounts.get(0);
        }
        return removed;
    }

    public boolean selectAccount(String username) {
        for (Account a : accounts) {
            if (a.username().equals(username)) {
                currentAccount = a;
                com.powerlaunch.settings.SettingsManager.getInstance()
                        .set("username", username);
                return true;
            }
        }
        return false;
    }

    public List<Account> getAccounts()              { return new ArrayList<>(accounts); }
    public Account      getCurrentAccount()         { return currentAccount; }
    public boolean      hasAccounts()               { return !accounts.isEmpty(); }
    public int          getAccountCount()           { return accounts.size(); }
}
