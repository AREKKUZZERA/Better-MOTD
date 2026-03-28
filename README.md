![BetterMOTD](src/main/resources/bettermotd-logo.png)

![Java Version](https://img.shields.io/badge/Java-21+-blue)
![PaperMC](https://img.shields.io/badge/Paper-1.21.x-white)
![Release](https://img.shields.io/github/v/release/AREKKUZZERA/better-motd?style=flat-square&logo=github)
[![Modrinth](https://img.shields.io/badge/Modrinth-Available-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/better-motd)

**BetterMOTD** is a lightweight and flexible plugin for **Paper/Spigot** Minecraft servers that provides
custom **server MOTD** and **server icon** customization with MiniMessage, legacy, JSON, and hex color support.
 
The plugin is designed to be minimal, fast, and easy to configure.  
No NMS. No performance overhead.

---

## ✨ Features

- 🎨 Dynamic MOTD with **MiniMessage**, legacy, JSON, and Birdflop-style `&#RRGGBB` support
- 🌈 HEX colors, gradients, and formatting (including §x RGB on Spigot)
- 🖼️ Server icon switching per preset
- 🎯 Weighted random, sticky-per-IP, hashed-per-IP, or rotating preset selection
- 🧩 Default icon (`default.png`) generated on first startup
- ⚡ Cached components for non-placeholder MOTD frames
- ⚡ Lightweight, async-safe implementation

---

## 📦 Compatibility

- **Minecraft:** 1.21 - 1.21.11  
- **Server:** Paper / Spigot  
- **Java:** 21+

---

## 🚀 Installation

1. Open the [releases page](https://github.com/AREKKUZZERA/better-motd/releases)
2. Download the **latest release** (`BetterMOTD-<version>.jar`)
3. Place the JAR file into your server’s `plugins/` directory
4. Start the server
5. Edit `plugins/BetterMOTD/config.yml`
6. Restart the server  
   > `/reload` is not recommended for production environments

---

## ⚡ Quick Start

After the first server start, the plugin will automatically generate:

```txt
plugins/BetterMOTD/
 ├─ config.yml
 └─ icons/
    └─ default.png
```

You can immediately customize:

* MOTD text and animation frames
* Preset weights and selection mode
* Player count display options
* Server icons per preset

---

## 🧰 Commands

All commands require the `bettermotd.admin` permission (default: op).

* `/bettermotd reload` - Reload the config and caches.
* `/bettermotd profile <profileId>` - Switch the active profile.
* `/bettermotd preview <profileId|presetId>` - Print a preview of the selected preset.
* `/bettermotd diagnostics` - Show active profile, cache sizes, sticky counts, and formatter warning stats.

---

## 🖼️ Server Icons

* **Format:** PNG
* **Resolution:** 64×64
* **Path:** `plugins/BetterMOTD/icons/`

If the `icons/` directory is empty, a default placeholder icon is created automatically.

```txt
icons/default.png
```

---

## 📝 MOTD Format

BetterMOTD supports multiple formatting syntaxes and automatically detects them in `AUTO`/`AUTO_STRICT` modes:

- MiniMessage tags (`<gradient:#00ffcc:#0099ff>`, `<#00ffcc>`, `<bold>`, etc.)
- Birdflop-style inline hex (`&#00D431Text`)
- JSON components (`{"text":"","extra":[{"text":"M","color":"#00D431"}]}`)
- Legacy section/ampersand codes (`§a`, `&a`, `§x§0§0§D§4§3§1`, `&x&0&0&D&4&3&1`)

MiniMessage example:

```yml
motd:
  - "<gradient:#00ffcc:#0099ff>Better Server</gradient>"
  - "<gray>Online players: <green>%online%</green></gray>"
```

Animated MOTD can be defined using `motdFrames` with a configurable `frameIntervalMillis`.

Supported placeholders:

* `%online%`, `%max%`, `%version%`
* `%preset%`, `%profile%`
* `%motd_frame%`, `%time%` (server local time, HH:mm)

---

## 🎛️ Presets & Selection Modes

BetterMOTD supports multiple presets with configurable weights.

### Selection modes:

* **RANDOM** - random preset on each ping
* **STICKY_PER_IP** - same preset for a client during a short time window
* **HASHED_PER_IP** - stable preset per IP
* **ROTATE** - cycles through presets in order

---

## ⚙️ Configuration

All settings are located in `config.yml`.

Main configuration areas:

* profiles and preset definitions
* MOTD frames and animation speed
* player count settings (fake players, hide counts, max override)
* selection mode and sticky TTL

---


## 📋 Config Reference

| Key | Default | Description |
|---|---|---|
| `activeProfile` | `default` | Profile ID used for ping handling. |
| `placeholders.enabled` | `true` | Enables `%online%`, `%max%`, `%preset%`, `%profile%`, `%motd_frame%`, `%time%`. |
| `colorFormat` | `AUTO` | Text parser mode (`AUTO_STRICT` avoids false MiniMessage detection like `1 < 2 > 0`). |
| `profiles.<id>.selectionMode` | `STICKY_PER_IP` | Preset strategy: `RANDOM`, `STICKY_PER_IP`, `HASHED_PER_IP`, `ROTATE`. |
| `profiles.<id>.stickyTtlSeconds` | `10` | Sticky lifetime for STICKY mode. |
| `profiles.<id>.stickyMaxEntriesPerProfile` | `10000` | Hard cap for sticky entries per profile. |
| `profiles.<id>.stickyCleanupEveryNPings` | `500` | Cleanup cadence. |
| `profiles.<id>.animation.frameIntervalMillis` | `450` | MOTD animation frame interval. |

A machine-readable schema is provided at `src/main/resources/config.schema.json`.

## ⚡ Performance Notes

* Non-placeholder MOTD frames are parsed once and reused on each ping.
* Placeholder replacement runs in a single pass and skips work when no tokens are present.

---

## 📌 Compatibility Notes

* Paper features are used directly via the Paper API when available.
* Fully compatible with Spigot fallback.

---

## 🧾 CHANGELOG

### 1.4.0

* Added a real whitelist gate that enforces Bukkit whitelist status on join.
* Clarified whitelist MOTD behavior as public-only with a dedicated whitelist profile key.
* Improved snapshot caching and placeholder handling for better ping performance.
* Cleaned up legacy configuration paths and documented new whitelist gate settings.

* Added sticky-per-IP cleanup safeguards and bounded eviction.
* Added whitelist MOTD mode, hostname routing, and verbose debug logging.
* Improved icon handling with guaranteed default icon caching.
* Optimized placeholder handling and cached components for static frames.

## 📄 License

This project is licensed under the **MIT License**.
You are free to use, modify, and distribute this plugin.
