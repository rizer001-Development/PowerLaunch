package com.powerlaunch.utils;

import java.io.IOException;
import java.net.*;
import java.nio.channels.SocketChannel;

/**
 * Network diagnostics for the launcher.
 * Tests connection to key servers and outputs detailed information.
 * Called at launcher start to help debug connection issues.
 */
public class NetworkDiagnostics {

    // Real launcher URLs
    private static final String[] TEST_URLS = {
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "https://api.mojang.com/users/profiles/minecraft/Test"
    };

    // Known Minecraft servers for TCP test (host:port)
    private static final String[] TEST_SERVERS = {
            "mc.hypixel.net:25565",
            "mc.hypixel.net:80",
            "google.com:80"
    };

    private NetworkDiagnostics() {}

    /**
     * Runs full network diagnostics.
     */
    public static void runAllTests() {
        System.out.println("[PowerLaunch][NetDiag] === Network Diagnostics ===");
        System.out.println("[PowerLaunch][NetDiag] IPv4 preferred: " + System.getProperty("java.net.preferIPv4Stack", "default"));
        System.out.println("[PowerLaunch][NetDiag] Use system proxies: " + System.getProperty("java.net.useSystemProxies", "default"));
        System.out.println("[PowerLaunch][NetDiag] Java version: " + System.getProperty("java.version", "?"));
        System.out.println("[PowerLaunch][NetDiag] OS: " + System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?"));

        // DNS + HTTP tests
        for (String urlStr : TEST_URLS) {
            testUrl(urlStr);
        }

        // Direct TCP test to Minecraft servers
        System.out.println("[PowerLaunch][NetDiag] --- TCP Socket tests ---");
        for (String server : TEST_SERVERS) {
            testTcpConnection(server);
        }

        // DNS test via InetAddress.getByName (as Minecraft does)
        System.out.println("[PowerLaunch][NetDiag] --- Minecraft-style DNS test ---");
        testMinecraftDns();

        System.out.println("[PowerLaunch][NetDiag] === Diagnostics complete ===");
    }

    private static void testTcpConnection(String serverStr) {
        String[] parts = serverStr.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        // 1. DNS for host
        System.out.print("[PowerLaunch][NetDiag] TCP " + host + ":" + port + " → ");
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            StringBuilder ips = new StringBuilder();
            for (int i = 0; i < Math.min(addresses.length, 4); i++) {
                if (i > 0) ips.append(", ");
                ips.append(addresses[i].getHostAddress());
                if (addresses[i] instanceof Inet6Address) ips.append("(IPv6)");
                else ips.append("(IPv4)");
            }
            System.out.println("DNS: " + ips);

            // 2. TCP Socket connect (3s timeout)
            System.out.print("[PowerLaunch][NetDiag]   connect → ");
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(host, port), 3000);
                System.out.println("OK (" + sock.getLocalAddress() + " → " + sock.getRemoteSocketAddress() + ")");
                sock.close();
            } catch (IOException e) {
                System.out.println("FAILED: [" + e.getClass().getSimpleName() + "] " + e.getMessage());
            }
        } catch (UnknownHostException e) {
            System.out.println("DNS FAILED: " + e.getMessage());
        }
    }

    private static void testMinecraftDns() {
        // Minecraft uses InetAddress.getByName() for DNS when connecting to a server
        // Checking that this works for typical addresses
        String[] testHosts = {"localhost", "mc.hypixel.net", "google.com"};
        for (String host : testHosts) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                String type = (addr instanceof Inet6Address) ? "IPv6" : "IPv4";
                System.out.println("[PowerLaunch][NetDiag] DNS " + host + " → " + addr.getHostAddress() + " (" + type + ")");
            } catch (UnknownHostException e) {
                System.out.println("[PowerLaunch][NetDiag] DNS " + host + " → FAILED: " + e.getMessage());
            }
        }
    }

    private static void testUrl(String urlStr) {
        try {
            String host = new URL(urlStr).getHost();

            // 1. DNS resolution
            System.out.print("[PowerLaunch][NetDiag] DNS " + host + " → ");
            try {
                InetAddress addr = InetAddress.getByName(host);
                System.out.println(addr.getHostAddress());
            } catch (UnknownHostException e) {
                System.out.println("FAILED: " + e.getMessage());
                return; // Can't proceed without DNS
            }

            // 2. HTTP connection test (GET with 5s timeout)
            System.out.print("[PowerLaunch][NetDiag] HTTP " + urlStr + " → ");
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                String msg = conn.getResponseMessage();
                System.out.println("HTTP " + code + " " + msg);

                // Check TLS
                if ("https".equalsIgnoreCase(url.getProtocol())) {
                    System.out.println("[PowerLaunch][NetDiag]   TLS OK (connection established)");
                }

                conn.disconnect();
            } catch (IOException e) {
                System.out.println("FAILED: [" + e.getClass().getSimpleName() + "] " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("[PowerLaunch][NetDiag] Error testing " + urlStr + ": " + e.getMessage());
        }
    }
}
