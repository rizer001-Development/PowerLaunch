package com.powerlaunch.auth;

import com.powerlaunch.TestSupport;
import com.powerlaunch.storage.AppDatabase;
import com.powerlaunch.storage.AppDatabase.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AccountManager — covers the new thread-safety work
 * (double-checked singleton, synchronized mutations).
 */
class AccountManagerTest {

    @BeforeEach
    void setUp() throws Exception {
        TestSupport.setUpTempHome();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestSupport.tearDown();
    }

    @Test
    void createAccount_trimsAndStores() {
        AccountManager am = AccountManager.getInstance();
        assertFalse(am.hasAccounts());

        am.createAccount("  Steve  ");
        assertEquals(1, am.getAccountCount());
        Account acc = am.getCurrentAccount();
        assertNotNull(acc);
        assertEquals("Steve", acc.getUsername());
        assertNotNull(acc.getUuid());
    }

    @Test
    void createAccount_duplicateIsCaseInsensitive() {
        AccountManager am = AccountManager.getInstance();
        am.createAccount("Alex");
        Account same = am.createAccount("alex");

        assertEquals(1, am.getAccountCount());
        assertEquals("Alex", same.getUsername());
    }

    @Test
    void removeAccount_updatesCurrent() {
        AccountManager am = AccountManager.getInstance();
        am.createAccount("First");
        am.createAccount("Second");

        // Second is current
        assertEquals("Second", am.getCurrentAccount().getUsername());

        assertTrue(am.removeAccount("Second"));
        assertEquals(1, am.getAccountCount());
        assertEquals("First", am.getCurrentAccount().getUsername());
    }

    @Test
    void removeAccount_unknownReturnsFalse() {
        AccountManager am = AccountManager.getInstance();
        am.createAccount("Solo");
        assertFalse(am.removeAccount("Ghost"));
        assertEquals(1, am.getAccountCount());
    }

    @Test
    void selectAccount_switchesCurrentAndPersists() {
        AccountManager am = AccountManager.getInstance();
        am.createAccount("First");
        am.createAccount("Second");

        assertTrue(am.selectAccount("First"));
        assertEquals("First", am.getCurrentAccount().getUsername());
        assertEquals("First", com.powerlaunch.settings.SettingsManager.getInstance()
                .getString("username", ""));

        assertFalse(am.selectAccount("Nobody"));
    }

    @Test
    void concurrentCreateAccount_isSafe() throws Exception {
        AccountManager am = AccountManager.getInstance();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            String name = "Player" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                am.createAccount(name);
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threads, am.getAccountCount());
        assertEquals(threads, AppDatabase.getInstance().getAllAccounts().size());
    }

    @Test
    void accountsPersistAcrossReload() throws Exception {
        AccountManager am = AccountManager.getInstance();
        am.createAccount("Persistent");
        am.selectAccount("Persistent");

        TestSupport.reload();

        AccountManager reloaded = AccountManager.getInstance();
        List<Account> accounts = reloaded.getAccounts();
        assertEquals(1, accounts.size());
        assertEquals("Persistent", accounts.get(0).getUsername());
        assertEquals("Persistent", reloaded.getCurrentAccount().getUsername());
    }
}