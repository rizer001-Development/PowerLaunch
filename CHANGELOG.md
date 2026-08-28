# Changelog

All notable changes to the PowerLaunch project. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions follow [SemVer](https://semver.org/).

## [Unreleased]

### Fixed
- Fabric mods required `java.net.http.HttpClient`, which was missing from the bundled custom JRE → `ClassNotFoundException` when launching Minecraft. Added the missing JDK modules (`java.net.http`, `java.desktop`, `java.logging`, `java.xml`, etc.) to jlink.
- Version selection: the list did not rescan after changing the game directory. Now multiple candidate folders are scanned (root, `versions/`, `.minecraft/versions`, default), and the UI rescans/refreshes when the folder changes, plus a "Scan" button was added.
- Portable build crashed with `UnsupportedClassVersionError`: the JAR was compiled for Java 26 while the bundled JRE was Java 25. Toolchain pinned to Java 25.
- `build.gradle.kts` hardcoded absolute paths (`C:\PowerLaunch\...`) → replaced with `${projectDir}`, making the build portable.

## [1.0.0] — 2026-08-29

### Added
- Unified SQLite database (`powerlaunch.db`) for all launcher data: settings, accounts, servers, versions, tabs, log numbering.
- Logs with a monotonically growing number in `logs/`: `logs/launcher-N.log` (per launcher session) and `logs/game-N.log` (per game launch — even a 1-second crash gets its own file).
- Central `storage.AppDatabase` manager (all tables in a single file).

### Fixed
- Repository: removed runtime data from git (`accounts.json`, `config.json`, `servers.json`, `powerlaunch.db`, `profiles/`, `logs/`), removed dead `org/example/Main.java`.
- `VersionManager` / `TabManager` hardcoded `%APPDATA%` instead of `LauncherHomeProvider` — broke portable mode.
- `SettingsManager.save()` — atomic write (temp + rename), protected against corruption on crash.
- Duplicate `FileLogManager.disable()` call in `handleConsoleStart`.
- Deadlock in the network sensor: `readAllBytes()` called before `waitFor()`.
- `NetworkDiagnostics.runAllTests()` blocked the launcher window from showing — moved to a background thread.
- `MinecraftLauncher.stopMinecraft()` — graceful `destroy()` with timeout before `destroyForcibly()` (protects world save).
- `SkinManager` parses UUID with Gson instead of a fragile regex.