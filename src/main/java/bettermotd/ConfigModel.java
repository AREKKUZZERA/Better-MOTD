package bettermotd;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public record ConfigModel(
        String activeProfile,
        boolean placeholdersEnabled,
        String fallbackIconPath,
        ColorFormat colorFormat,
        boolean debugSelfTest,
        boolean debugVerbose,
        PlaceholderApiSettings placeholderApi,
        MaintenanceSettings maintenance,
        Map<String, Profile> profiles) {

    public static final List<String> FALLBACK_MOTD_LINES = List.of("BetterMOTD", "1.21.x");

    public static ConfigModel empty() {
        return new ConfigModel(
                "default",
                true,
                null,
                ColorFormat.AUTO,
                false,
                false,
                new PlaceholderApiSettings(false),
                MaintenanceSettings.disabled(),
                Collections.emptyMap());
    }

    public static LoadResult load(FileConfiguration cfg, File dataFolder, Logger logger) {
        if (cfg == null || logger == null) {
            return new LoadResult(empty(), 0, false, Collections.emptyMap(), Collections.emptySet());
        }

        AtomicInteger warnings = new AtomicInteger();
        if (dataFolder == null) {
            warn(logger, warnings, "Data folder is not available for BetterMOTD.");
        }

        boolean placeholdersEnabled = cfg.getBoolean("placeholders.enabled", true);
        String colorFormatRaw = cfg.getString("colorFormat", ColorFormat.AUTO.name());
        ColorFormat colorFormat = ColorFormat.from(colorFormatRaw);
        if (colorFormat == null) {
            warn(logger, warnings, "Unknown colorFormat '" + colorFormatRaw + "'. Using AUTO.");
            colorFormat = ColorFormat.AUTO;
        }
        boolean debugSelfTest = cfg.getBoolean("debug.selfTest", false);
        boolean debugVerbose = cfg.getBoolean("debug.verbose", false);
        PlaceholderApiSettings placeholderApi =
                new PlaceholderApiSettings(cfg.getBoolean("placeholderAPI.enabled", false));
        MaintenanceSettings maintenance = parseMaintenance(cfg.getConfigurationSection("maintenance"));
        String activeProfile = str(cfg.getString("activeProfile"), "default");
        String fallbackIconPath = dataFolder != null ? "icons/default.png" : null;

        if (debugVerbose) {
            logDeprecatedSections(cfg, logger);
        }

        Map<String, Profile> profiles = new LinkedHashMap<>();
        Map<String, Integer> presetCounts = new LinkedHashMap<>();
        Set<String> fallbackProfiles = ConcurrentHashMap.newKeySet();
        boolean legacy = false;

        ConfigurationSection profilesSection = cfg.getConfigurationSection("profiles");
        if (profilesSection == null) {
            legacy = true;
            warn(logger, warnings, "Legacy config detected (root presets). Please migrate to the new profiles format.");
            addProfile(
                    profiles,
                    presetCounts,
                    fallbackProfiles,
                    parseProfile(cfg, "default", dataFolder, logger, fallbackIconPath, warnings));
        } else {
            for (String profileId : profilesSection.getKeys(false)) {
                ConfigurationSection section = profilesSection.getConfigurationSection(profileId);
                if (section == null) continue;
                addProfile(
                        profiles,
                        presetCounts,
                        fallbackProfiles,
                        parseProfile(section, profileId, dataFolder, logger, fallbackIconPath, warnings));
            }
        }

        if (profiles.isEmpty()) {
            warn(logger, warnings, "No profiles found. Using built-in fallback profile.");
            Profile fallback = fallbackProfile("default", fallbackIconPath);
            addProfile(profiles, presetCounts, fallbackProfiles, fallback);
        }

        if (!profiles.containsKey(activeProfile)) {
            String fallbackId = profiles.keySet().iterator().next();
            warn(logger, warnings, "Active profile '" + activeProfile + "' not found. Using '" + fallbackId + "'.");
            activeProfile = fallbackId;
        }

        ConfigModel model = new ConfigModel(
                activeProfile,
                placeholdersEnabled,
                fallbackIconPath,
                colorFormat,
                debugSelfTest,
                debugVerbose,
                placeholderApi,
                maintenance,
                Collections.unmodifiableMap(profiles));

        return new LoadResult(model, warnings.get(), legacy, presetCounts, fallbackProfiles);
    }

    private static void addProfile(
            Map<String, Profile> profiles,
            Map<String, Integer> presetCounts,
            Set<String> fallbackProfiles,
            Profile profile) {
        profiles.put(profile.id(), profile);
        presetCounts.put(profile.id(), profile.presets().size());
        if (profile.presets().size() == 1
                && "default".equals(profile.presets().get(0).id())) {
            fallbackProfiles.add(profile.id());
        }
    }

    private static Profile parseProfile(
            ConfigurationSection section,
            String profileId,
            File dataFolder,
            Logger logger,
            String fallbackIconPath,
            AtomicInteger warnings) {

        String selectionModeRaw = section.getString("selectionMode", SelectionMode.STICKY_PER_IP.name());
        SelectionMode selectionMode = SelectionMode.from(selectionModeRaw);
        if (selectionMode == null) {
            warn(
                    logger,
                    warnings,
                    "Unknown selectionMode '" + selectionModeRaw + "' in profile '" + profileId
                            + "'. Using STICKY_PER_IP.");
            selectionMode = SelectionMode.STICKY_PER_IP;
        }

        int stickyTtlSeconds = clamp(
                section.getInt("stickyTtlSeconds", 10),
                1,
                Integer.MAX_VALUE,
                "stickyTtlSeconds",
                profileId,
                logger,
                warnings);
        int stickyMaxEntries = clamp(
                section.getInt("stickyMaxEntriesPerProfile", 10000),
                1,
                Integer.MAX_VALUE,
                "stickyMaxEntriesPerProfile",
                profileId,
                logger,
                warnings);
        int stickyCleanupEvery = clamp(
                section.getInt("stickyCleanupEveryNPings", 500),
                1,
                Integer.MAX_VALUE,
                "stickyCleanupEveryNPings",
                profileId,
                logger,
                warnings);

        Profile.PlayerCountSettings playerCount =
                parsePlayerCount(section.getConfigurationSection("playerCount"), profileId, logger, warnings);

        List<Preset> presets = parsePresetList(
                section.getMapList("presets"), dataFolder, logger, fallbackIconPath, profileId, warnings);
        if (presets.isEmpty()) {
            warn(logger, warnings, "Profile '" + profileId + "' has no valid presets. Using fallback preset.");
            presets = List.of(Preset.fallback(fallbackIconPath));
        }

        return new Profile(
                profileId,
                selectionMode,
                stickyTtlSeconds,
                stickyMaxEntries,
                stickyCleanupEvery,
                playerCount,
                List.copyOf(presets));
    }

    private static Profile.PlayerCountSettings parsePlayerCount(
            ConfigurationSection section, String profileId, Logger logger, AtomicInteger warnings) {
        if (section == null) return defaultPlayerCount();

        boolean disableHover = section.getBoolean("disableHover", false);
        boolean hidePlayerCount = section.getBoolean("hidePlayerCount", false);
        List<String> hoverLines = strList(section.get("hoverLines"));
        Profile.FakePlayersSettings fakePlayers =
                parseFakePlayers(section.getConfigurationSection("fakePlayers"), profileId, logger, warnings);

        ConfigurationSection justXSec = section.getConfigurationSection("justXMore");
        boolean justXEnabled = justXSec != null && justXSec.getBoolean("enabled", false);
        int justXValue = clamp(
                justXSec != null ? justXSec.getInt("x", 0) : 0,
                0,
                Integer.MAX_VALUE,
                "justXMore.x",
                profileId,
                logger,
                warnings);

        ConfigurationSection maxSec = section.getConfigurationSection("maxPlayers");
        boolean maxEnabled = maxSec != null && maxSec.getBoolean("enabled", false);
        int maxValue = 0;
        if (maxSec != null && maxEnabled) {
            maxValue = clamp(
                    maxSec.getInt("value", 0), 1, Integer.MAX_VALUE, "maxPlayers.value", profileId, logger, warnings);
        }

        return new Profile.PlayerCountSettings(
                disableHover,
                hidePlayerCount,
                List.copyOf(hoverLines),
                fakePlayers,
                new Profile.JustXMoreSettings(justXEnabled, justXValue),
                new Profile.MaxPlayersSettings(maxEnabled, maxValue));
    }

    private static Profile.PlayerCountSettings defaultPlayerCount() {
        return new Profile.PlayerCountSettings(
                false,
                false,
                List.of(),
                new Profile.FakePlayersSettings(false, Profile.FakePlayersMode.STATIC, 0, 0, 0.0),
                new Profile.JustXMoreSettings(false, 0),
                new Profile.MaxPlayersSettings(false, 0));
    }

    private static Profile.FakePlayersSettings parseFakePlayers(
            ConfigurationSection section, String profileId, Logger logger, AtomicInteger warnings) {
        if (section == null) {
            return new Profile.FakePlayersSettings(false, Profile.FakePlayersMode.STATIC, 0, 0, 0.0);
        }
        boolean enabled = section.getBoolean("enabled", false);
        String modeRaw = section.getString("mode", "static");
        Profile.FakePlayersMode mode;
        try {
            mode = Profile.FakePlayersMode.valueOf(modeRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn(
                    logger,
                    warnings,
                    "Unknown fakePlayers.mode '" + modeRaw + "' in profile '" + profileId + "'. Using static.");
            mode = Profile.FakePlayersMode.STATIC;
        }

        String valueRaw = section.getString("value", "0");
        int min = 0, max = 0;
        double percent = 0.0;

        switch (mode) {
            case PERCENT -> {
                percent = parsePercent(valueRaw);
                if (percent < 0) {
                    warn(logger, warnings, "fakePlayers.value in profile '" + profileId + "' must be >= 0. Using 0.");
                    percent = 0.0;
                }
            }
            case RANDOM -> {
                int[] range = parseRange(valueRaw);
                min = Math.max(0, range[0]);
                max = Math.max(min, range[1]);
            }
            default -> {
                min = Math.max(0, parseInt(valueRaw));
                max = min;
            }
        }
        return new Profile.FakePlayersSettings(enabled, mode, min, max, percent);
    }

    private static int[] parseRange(String raw) {
        if (raw == null) return new int[] {0, 0};
        String[] parts = raw.trim().split("[:\\-]", 2);
        if (parts.length == 2) {
            return new int[] {parseInt(parts[0]), parseInt(parts[1])};
        }
        int v = parseInt(raw.trim());
        return new int[] {v, v};
    }

    private static double parsePercent(String raw) {
        if (raw == null) return 0.0;
        String cleaned = raw.trim();
        if (cleaned.endsWith("%")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static List<Preset> parsePresetList(
            List<?> list,
            File dataFolder,
            Logger logger,
            String fallbackIconPath,
            String profileId,
            AtomicInteger warnings) {
        if (list == null || list.isEmpty()) return Collections.emptyList();

        List<Preset> presets = new ArrayList<>(list.size());
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) {
                warn(logger, warnings, "Invalid preset entry in profile '" + profileId + "'. Skipping.");
                continue;
            }
            String id = str(map.get("id"), null);
            if (id == null) {
                warn(logger, warnings, "Preset entry missing id in profile '" + profileId + "'. Skipping.");
                continue;
            }
            int weight = intv(map.get("weight"), 1);
            if (weight < 1) {
                warn(logger, warnings, "Preset '" + id + "' in profile '" + profileId + "' has weight < 1. Using 1.");
                weight = 1;
            }
            String icon = resolveIcon(map.get("icon"), dataFolder, logger, fallbackIconPath, id, profileId, warnings);
            List<String> motd = normalizeMotdLines(strList(map.get("motd")), profileId, id, logger, warnings);
            List<String> motdFrames = normalizeFrames(strList(map.get("motdFrames")), profileId, id, logger, warnings);
            List<String> icons =
                    resolveIcons(map.get("icons"), dataFolder, logger, fallbackIconPath, id, profileId, warnings);
            Preset.Conditions conditions = parseConditions(map.get("conditions"));

            if (motd.isEmpty() && motdFrames.isEmpty()) {
                warn(
                        logger,
                        warnings,
                        "Preset '" + id + "' in profile '" + profileId + "' has no motd or motdFrames. Skipping.");
                continue;
            }
            if (!motd.isEmpty()) {
                presets.add(new Preset(id, weight, icon, icons, motd, conditions));
                if (!motdFrames.isEmpty()) {
                    warn(
                            logger,
                            warnings,
                            "Preset '" + id + "' in profile '" + profileId
                                    + "' uses legacy motdFrames together with motd. Ignoring motdFrames.");
                }
                continue;
            }

            warn(
                    logger,
                    warnings,
                    "Preset '" + id + "' in profile '" + profileId
                            + "' uses legacy motdFrames. Loading frames as separate presets.");
            for (int i = 0; i < motdFrames.size(); i++) {
                presets.add(new Preset(
                        id + "-" + (i + 1), weight, icon, icons, frameToMotd(motdFrames.get(i)), conditions));
            }
        }
        return presets;
    }

    private static List<String> frameToMotd(String frame) {
        if (frame == null || frame.isEmpty()) {
            return FALLBACK_MOTD_LINES;
        }
        int nl = frame.indexOf('\n');
        if (nl < 0) {
            return List.of(frame, "");
        }
        return List.of(frame.substring(0, nl), frame.substring(nl + 1));
    }

    private static List<String> resolveIcons(
            Object raw,
            File dataFolder,
            Logger logger,
            String fallbackIconPath,
            String presetId,
            String profileId,
            AtomicInteger warnings) {
        List<String> rawIcons = strList(raw);
        if (rawIcons.isEmpty()) return List.of();
        List<String> icons = new ArrayList<>(rawIcons.size());
        for (String rawIcon : rawIcons) {
            String resolved = resolveIcon(rawIcon, dataFolder, logger, fallbackIconPath, presetId, profileId, warnings);
            if (resolved != null && !icons.contains(resolved)) {
                icons.add(resolved);
            }
        }
        return List.copyOf(icons);
    }

    private static Preset.Conditions parseConditions(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Preset.Conditions.any();
        }
        return new Preset.Conditions(
                strList(map.get("hostnames")),
                strList(map.get("hostnameContains")),
                nullableInt(map.get("minProtocol")),
                nullableInt(map.get("maxProtocol")),
                nullableInt(map.get("minOnline")),
                nullableInt(map.get("maxOnline")));
    }

    private static MaintenanceSettings parseMaintenance(ConfigurationSection section) {
        if (section == null) {
            return MaintenanceSettings.disabled();
        }
        return new MaintenanceSettings(
                section.getBoolean("enabled", false),
                str(section.getString("profile"), null),
                str(section.getString("bypassPermission"), "bettermotd.maintenance.bypass"),
                str(section.getString("kickMessage"), "Server is in maintenance mode."));
    }

    /**
     * Normalizes a static motd field (1-2 lines) into exactly [line1, line2].
     * Empty second line is added when only one line is provided.
     */
    private static List<String> normalizeMotdLines(
            List<String> lines, String profileId, String presetId, Logger logger, AtomicInteger warnings) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        if (lines.size() > 2) {
            warn(
                    logger,
                    warnings,
                    "Preset '" + presetId + "' in profile '" + profileId
                            + "' motd has more than 2 lines. Using first two.");
        }
        String line1 = lines.get(0);
        if (lines.size() == 1) {
            int nl = line1.indexOf('\n');
            if (nl >= 0) {
                return List.of(line1.substring(0, nl), line1.substring(nl + 1));
            }
        }
        String line2 = lines.size() >= 2 ? lines.get(1) : "";
        return List.of(line1, line2);
    }

    /**
     * Normalizes legacy motdFrames entries. Each frame is a string with at most 2 lines.
     */
    private static List<String> normalizeFrames(
            List<String> frames, String profileId, String presetId, Logger logger, AtomicInteger warnings) {
        if (frames == null || frames.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            String raw = frames.get(i) == null ? "" : frames.get(i);
            int nl = raw.indexOf('\n');
            if (nl < 0) {
                out.add(raw + "\n");
            } else {
                int second = raw.indexOf('\n', nl + 1);
                if (second >= 0) {
                    warn(
                            logger,
                            warnings,
                            "Preset '" + presetId + "' in profile '" + profileId + "' frame " + i
                                    + " has more than 2 lines. Using first two.");
                    out.add(raw.substring(0, second));
                } else {
                    out.add(raw);
                }
            }
        }
        return out;
    }

    private static Profile fallbackProfile(String id, String fallbackIconPath) {
        return new Profile(
                id,
                SelectionMode.STICKY_PER_IP,
                10,
                10000,
                500,
                defaultPlayerCount(),
                List.of(Preset.fallback(fallbackIconPath)));
    }

    private static String resolveIcon(
            Object raw,
            File dataFolder,
            Logger logger,
            String fallbackIconPath,
            String presetId,
            String profileId,
            AtomicInteger warnings) {
        String icon = str(raw, null);
        if (icon == null || icon.isBlank()) {
            return fallbackIconPath;
        }

        String normalized = IconCache.normalizeIconPath(icon);
        if (normalized == null) {
            if (fallbackIconPath != null) {
                warn(
                        logger,
                        warnings,
                        "Preset '" + presetId + "' in profile '" + profileId + "' has invalid icon path '" + icon
                                + "'. Using " + fallbackIconPath + ".");
                return fallbackIconPath;
            }
            warn(
                    logger,
                    warnings,
                    "Preset '" + presetId + "' in profile '" + profileId + "' has invalid icon path '" + icon + "'.");
            return null;
        }
        if (dataFolder == null) return normalized;

        File iconFile = new File(dataFolder, normalized);
        if (!iconFile.isFile()) {
            if (fallbackIconPath != null) {
                warn(
                        logger,
                        warnings,
                        "Preset '" + presetId + "' in profile '" + profileId + "' icon not found: " + iconFile.getPath()
                                + ". Using " + fallbackIconPath + ".");
                return fallbackIconPath;
            }
            warn(
                    logger,
                    warnings,
                    "Preset '" + presetId + "' in profile '" + profileId + "' icon not found: " + iconFile.getPath()
                            + ".");
            return null;
        }
        return normalized;
    }

    private static void logDeprecatedSections(FileConfiguration cfg, Logger logger) {
        List<String> deprecated = new ArrayList<>(2);
        if (cfg.getConfigurationSection("whitelist") != null) deprecated.add("whitelist");
        if (cfg.getConfigurationSection("routing") != null) deprecated.add("routing");
        if (!deprecated.isEmpty()) {
            logger.info("Ignoring deprecated sections: " + String.join(", ", deprecated));
        }
    }

    // Helpers

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = o.toString();
        return s.isBlank() ? def : s;
    }

    private static int parseInt(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int intv(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static Integer nullableInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> strList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object it : list) out.add(it == null ? "" : it.toString());
            return out;
        }
        return Collections.emptyList();
    }

    private static void warn(Logger logger, AtomicInteger warnings, String message) {
        logger.warning(message);
        warnings.incrementAndGet();
    }

    private static int clamp(
            int value, int min, int max, String field, String profileId, Logger logger, AtomicInteger warnings) {
        if (value < min) {
            warn(
                    logger,
                    warnings,
                    field + " in profile '" + profileId + "' must be >= " + min + ". Using " + min + ".");
            return min;
        }
        return Math.min(value, max);
    }

    // Nested types

    public record LoadResult(
            ConfigModel config,
            int warnings,
            boolean legacy,
            Map<String, Integer> presetCounts,
            Set<String> fallbackProfiles) {}

    public record PlaceholderApiSettings(boolean enabled) {}

    public record MaintenanceSettings(boolean enabled, String profile, String bypassPermission, String kickMessage) {
        public static MaintenanceSettings disabled() {
            return new MaintenanceSettings(
                    false, null, "bettermotd.maintenance.bypass", "Server is in maintenance mode.");
        }
    }

    public enum SelectionMode {
        RANDOM,
        STICKY_PER_IP,
        HASHED_PER_IP,
        ROTATE;

        public static SelectionMode from(String value) {
            if (value == null) return null;
            for (SelectionMode m : values()) {
                if (m.name().equalsIgnoreCase(value)) return m;
            }
            return null;
        }
    }
}
