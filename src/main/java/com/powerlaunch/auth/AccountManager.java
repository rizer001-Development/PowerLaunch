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

    public static AccountManager getInstance() {
        if (instance == null) {
            synchronized (AccountManager.class) {
                if (instance == null) instance = new AccountManager();
            }
        }
        return instance;
    }

    public synchronized Account createAccount(String username) {
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

    public synchronized boolean removeAccount(String username) {
        db.deleteAccount(username);
        List<Account> fresh = db.getAllAccounts();
        boolean removed = fresh.size() < accounts.size();
        accounts = fresh;
        if (removed && currentAccount != null && currentAccount.username().equals(username)) {
            currentAccount = accounts.isEmpty() ? null : accounts.get(0);
        }
        return removed;
    }

    public synchronized boolean selectAccount(String username) {
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

    public synchronized List<Account> getAccounts()              { return new ArrayList<>(accounts); }
    public synchronized Account      getCurrentAccount()         { return currentAccount; }
    public synchronized boolean      hasAccounts()               { return !accounts.isEmpty(); }
    public synchronized int          getAccountCount()           { return accounts.size(); }
}
