# BetterMOTD

![Paper](https://img.shields.io/badge/Paper-1.21.x-ffffff?style=flat-square&logo=papermc&logoColor=blue)
![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A?style=flat-square&logo=gradle&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square&logo=opensourceinitiative&logoColor=white)
![Release](https://img.shields.io/github/v/release/AREKKUZZERA/better-motd?style=flat-square&logo=github)

**BetterMOTD** is a lightweight and flexible plugin for **Paper** Minecraft servers that provides
dynamic **server MOTD** and **server icon** customization with support for animations, HEX colors,
and gradients.

The plugin is designed to be minimal, fast, and easy to configure.  
No NMS. No performance overhead.

---

## ✨ Features

- 🎨 Dynamic MOTD with **MiniMessage** support
- 🌈 HEX colors, gradients, and formatting
- 🔁 Animated MOTD (frame-based)
- 🖼️ Server icon switching and animation
- 🎯 Weighted random or sticky-per-IP preset selection
- 📁 Automatic `icons/` directory creation
- 🧩 Default icon (`default.png`) generated on first startup
- ⚡ Lightweight, async-safe implementation

---

## 📦 Compatibility

- **Minecraft:** 1.21 - 1.21.11  
- **Server:** Paper  
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
````

You can immediately customize:

* MOTD text and animation frames
* Server icons
* Preset weights and selection mode

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

## 📝 MOTD Format (MiniMessage)

BetterMOTD uses **MiniMessage**, allowing modern formatting:

```yml
motd:
  - "<gradient:#00ffcc:#0099ff>Better Server</gradient>"
  - "<gray>Online players: <green>%online%</green></gray>"
```

Animated MOTD can be defined using frame lists with a configurable interval.

---

## 🎛️ Presets & Selection Modes

BetterMOTD supports multiple presets with configurable weights.

### Selection modes:

* **RANDOM** - random preset on each ping
* **STICKY_PER_IP** - same preset for a client during a short time window

This allows consistent visuals for players while still keeping variety.

---

## ⚙️ Configuration

All settings are located in `config.yml`.

Main configuration areas:

* preset definitions and weights
* MOTD frames and animation speed
* server icon frames
* selection mode and sticky TTL

The configuration is human-readable and safe to edit without restarts (restart recommended).

---

## 🧠 Performance & Safety

* No NMS usage
* No packet interception
* No impact on gameplay or tick performance
* Uses Paper API and Adventure components only
* Safe for large public servers

---

## 📄 License

This project is licensed under the **MIT License**.
You are free to use, modify, and distribute this plugin.
