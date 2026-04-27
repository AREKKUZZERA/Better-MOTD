# BetterMOTD

Lightweight Paper plugin for server-list MOTD, icons, formatting and player-count display.

## Requirements

- Java 21+
- Paper 1.21.x
- Minecraft 1.21 - 1.21.11

The plugin uses Paper ping APIs for component MOTD, fake online count, hidden player count and hover control.
Bukkit fallback is kept for basic MOTD text, but production use should be Paper.

## Features

- MiniMessage, legacy color codes, JSON components and `&#RRGGBB` hex colors
- Multiple presets with weights
- Random, sticky-per-IP, hashed-per-IP and rotating preset selection
- Animated MOTD frames
- Per-preset server icons from `plugins/BetterMOTD/icons/`
- Placeholders: `%online%`, `%max%`, `%version%`, `%profile%`, `%preset%`, `%motd_frame%`, `%time%`
- Player-count options: fake players, fixed max players, "online + X", hidden count, disabled hover list
- Custom hover/sample player lines
- Maintenance profile and join gate with bypass permission
- Preset conditions by hostname, protocol and online player count
- Random icons per preset through `icons: [...]`
- Optional PlaceholderAPI expansion
- MiniMOTD config import command
- `/bettermotd reload` without server restart

## Installation

1. Download `BetterMOTD-<version>.jar`.
2. Put it into `plugins/`.
3. Start the server once.
4. Edit `plugins/BetterMOTD/config.yml`.
5. Run `/bettermotd reload`.

## Commands

All commands require `bettermotd.admin` permission.

- `/bettermotd help`
- `/bettermotd reload` or `/bm r`
- `/bettermotd profile [profileId]` or `/bm p [profileId]`
- `/bettermotd preview <profileId|presetId>`
- `/bettermotd diagnostics` or `/bm d`
- `/bettermotd import minimotd [path]`

Command aliases: `/bm`, `/bmotd`, `/motd`.

MiniMOTD import reads common `line1`/`line2` or `motd-first-line`/`motd-second-line` pairs and creates profile `imported`.

## Config Basics

Default config path:

```txt
plugins/BetterMOTD/config.yml
```

Minimal preset:

```yml
profiles:
  default:
    presets:
      - id: "main"
        icon: "default.png"
        motd:
          - "<green><bold>My Server</bold></green>"
          - "<gray>Online: <white>%online%</white>/<white>%max%</white></gray>"
```

Animated preset:

```yml
motdFrames:
  - "<green><bold>My Server</bold></green>\n<gray>Welcome</gray>"
  - "<aqua><bold>My Server</bold></aqua>\n<gray>Version: %version%</gray>"
```

Icons must be PNG files inside:

```txt
plugins/BetterMOTD/icons/
```

Simple names like `default.png` are resolved as `icons/default.png`. Absolute paths, parent-directory paths and non-PNG files are rejected.

Maintenance mode:

```yml
maintenance:
  enabled: true
  profile: "maintenance"
  bypassPermission: "bettermotd.maintenance.bypass"
  kickMessage: "<red>Server is in maintenance mode.</red>"
```

Conditional preset example:

```yml
conditions:
  hostnames: ["play.example.net"]
  minProtocol: 767
  maxOnline: 100
```

## Production Notes

- Keep `debug.selfTest` and `debug.verbose` disabled unless diagnosing a config issue.
- Prefer `AUTO_STRICT` if your MOTD contains literal `<` or `>` symbols.
- Use `STICKY_PER_IP` for random presets to avoid flickering on repeated refreshes.
- Keep icon files 64x64 PNG.

## Competitor-Inspired Ideas

Useful future features seen in MiniMOTD, AdvancedServerList, ServerListMOTD and similar plugins:

- Maintenance MOTD with optional join blocking for non-whitelisted users
- Custom hover/sample player lines instead of only disabling hover
- Conditions by hostname, protocol version, player count or permission source
- Multiple random icons independent from MOTD presets
- PlaceholderAPI integration
- Velocity/BungeeCord support for network setups
- Import command for MiniMOTD-style configs

## Build

```bash
mvn verify
```

## License

MIT
