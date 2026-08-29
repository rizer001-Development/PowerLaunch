# PowerLaunch

![Development status](https://img.shields.io/badge/status-Stable-green)

A feature-rich Minecraft launcher built with JavaFX. Manage multiple Minecraft versions, modpacks, and accounts with a modern desktop interface.

---

### 📄 Organization Docs

[![Guide](https://img.shields.io/badge/Guide-rizer001--Development-00AEFF)](https://github.com/rizer001-Development/.github/blob/main/GUIDE.md) · [![Contributing](https://img.shields.io/badge/Contributing-rizer001--Development-4CAF50)](https://github.com/rizer001-Development/.github/blob/main/CONTRIBUTING.md) · [![Security](https://img.shields.io/badge/Security-rizer001--Development-D9534F)](https://github.com/rizer001-Development/.github/blob/main/SECURITY.md) · [![Code of Conduct](https://img.shields.io/badge/Code%20of%20Conduct-rizer001--Development-5BC0DE)](https://github.com/rizer001-Development/.github/blob/main/CODE_OF_CONDUCT.md)

## Features

- **Multi‑version support** — Launch any Minecraft version from vanilla to heavily modded
- **Modpack integration** — Fabric, NeoForge, Forge — automatic library resolution and deduplication
- **Account management** — Mojang/Microsoft authentication with session persistence
- **Performance monitoring** — Real‑time CPU (per‑core), RAM, disk I/O, and network sensors
- **Custom Java path** — Specify a custom JDK/JRE per instance
- **Console logging** — Full game output with optional file logging
- **Modular architecture** — Profile system, settings manager, diagnostics panel

## Getting Started

### Prerequisites
- Java 21+ (JDK recommended)
- Git

### Build & Run
```bash
cd PowerLaunch
./gradlew run
```

### Build a distribution
```bash
./gradlew build
```
The portable launcher directory will be created under `build/`.

## Project Structure

```
PowerLaunch/
├── src/main/java/com/powerlaunch/
│   ├── auth/          — Authentication (Mojang/MSA)
│   ├── gui/           — JavaFX UI controllers
│   ├── minecraft/     — Game launching, classpath assembly
│   ├── settings/      — Profiles, settings persistence
│   └── utils/         — Logging, diagnostics, helpers
├── resources/         — FXML layouts, CSS, config
└── build.gradle       — Gradle build configuration
```

## License

This project is licensed under the **GNU Affero General Public License v3.0**.  
See the [LICENSE](./LICENSE) file for details.
