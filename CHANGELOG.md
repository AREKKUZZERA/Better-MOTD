# Changelog

## 1.7.0 - 2026-04-27

### Added

- MiniMOTD config import command: `/bettermotd import minimotd [path]`.
- Maintenance mode with separate server-list profile and join gate.
- Maintenance bypass permission: `bettermotd.maintenance.bypass`.
- Custom Paper hover/sample player lines via `playerCount.hoverLines`.
- Preset conditions by hostname, protocol version, and online player count.
- Random icon pool per preset via `icons: [...]`.
- Optional PlaceholderAPI support through `placeholderAPI.enabled`.
- Config parsing tests for new production options.
- MiniMOTD importer tests.
- Icon path safety regression tests.

### Changed

- Version is controlled from `pom.xml`; `plugin.yml` uses Maven resource filtering with `${project.version}`.
- Default `config.yml` now documents the new features while keeping risky options disabled by default.
- `config.schema.json` now covers maintenance, PlaceholderAPI, hover lines, random icons, and preset conditions.
- README updated with current commands and production notes.

### Fixed

- Rejected unsafe icon paths such as absolute paths, parent-directory traversal, and non-PNG files.
- Removed static-analysis warning around optional `maxPlayers` config parsing.
