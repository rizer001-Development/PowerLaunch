# Changelog

Все существенные изменения проекта PowerLaunch. Формат — [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), версии по [SemVer](https://semver.org/).

## [Unreleased]

### Fixed
- Fabric-моды требовали `java.net.http.HttpClient`, которого не было в кастомной JRE → `ClassNotFoundException` при запуске Майнкрафта. Добавлены недостающие JDK-модули (`java.net.http`, `java.desktop`, `java.logging`, `java.xml` и др.) в jlink.
- Выбор версии: список не пересканировался после смены game directory. Теперь сканируются несколько папок (корень, `versions/`, `.minecraft/versions`, дефолтная), а UI пересканирует и обновляет список при смене папки + добавлена кнопка «Scan».
- Portable-сборка падала с `UnsupportedClassVersionError`: JAR компилировался под Java 26, а встроенная JRE — Java 25. Toolchain сведён к Java 25.
- `build.gradle.kts` использовал хардкод абсолютных путей (`C:\PowerLaunch\...`) → заменено на `${projectDir}`, сборка теперь переносимая.

## [1.0.0] — 2026-08-29

### Added
- Единая SQLite БД (`powerlaunch.db`) для всех данных лаунчера: настройки, аккаунты, серверы, версии, вкладки, нумерация логов.
- Логи с монотонно растущим номером в `logs/`: `logs/launcher-N.log` (на запуск лаунчера) и `logs/game-N.log` (на каждый запуск игры — даже краш через 1 секунду создаёт свой файл).
- Унифицированный менеджер БД `storage.AppDatabase` (все таблицы в одном файле).

### Fixed
- Репозиторий: удалены из git runtime-данные (`accounts.json`, `config.json`, `servers.json`, `powerlaunch.db`, `profiles/`, `logs/`), мёртвый `org/example/Main.java`.
- `VersionManager` / `TabManager` использовали хардкод `%APPDATA%` вместо `LauncherHomeProvider` — сломан portable-режим.
- `SettingsManager.save()` — атомарная запись (temp + rename), защита от повреждения при краше.
- Дублированный вызов `FileLogManager.disable()` в `handleConsoleStart`.
- Deadlock в сетевом сенсоре: `readAllBytes()` вызывался до `waitFor()`.
- `NetworkDiagnostics.runAllTests()` блокировал показ окна лаунчера — запущен в фоновом потоке.
- `MinecraftLauncher.stopMinecraft()` — graceful `destroy()` с таймаутом перед `destroyForcibly()` (защита сохранения мира).
- `SkinManager` парсит UUID через Gson вместо хрупкой регулярки.