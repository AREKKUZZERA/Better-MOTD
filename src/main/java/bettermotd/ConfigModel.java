package bettermotd;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

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

public record ConfigModel(
        String activeProfile,
        boolean placeholdersEnabled,
        String fallbackIconPath,
        ColorFormat colorFormat,
        boolean debugSelfTest,
        boolean debugVerbose,
        Map<String, Profile> profiles) {
    public static final long DEFAULT_FRAME_INTERVAL_MILLIS = 450L;
    public static final List<String> FALLBACK_MOTD_LINES = List.of("BetterMOTD", "1.21.x");

    public static ConfigModel empty() {
        return new ConfigModel(
                "default",
                true,
                null,
                ColorFormat.AUTO,
                false,
                false,
                Collections.emptyMap());
    }

    public static LoadResult load(FileConfiguration cfg, File dataFolder, Logger logger) {
        if (cfg == null || logger == null) {
            return new LoadResult(empty(), 0, false, Collections.emptyMap(), Collections.emptySet());
        }

        AtomicInteger warnings = new AtomicInteger();
        validateDataFolder(dataFolder, logger, warnings);

        boolean placeholdersEnabled = cfg.getBoolean("placeholders.enabled", true);
        String colorFormatRaw = cfg.getString("colorFormat", ColorFormat.AUTO.name());
        ColorFormat colorFormat = ColorFormat.from(colorFormatRaw);
        if (colorFormat == null) {
            warnings.incrementAndGet();
            logger.warning("Unknown colorFormat '" + colorFormatRaw + "'. Using AUTO.");
            colorFormat = ColorFormat.AUTO;
        }
        boolean debugSelfTest = cfg.getBoolean("debug.selfTest", false);
        boolean debugVerbose = cfg.getBoolean("debug.verbose", false);
        String activeProfile = str(cfg.getString("activeProfile"), "default");
        String fallbackIconPath = resolveFallbackIconPath(dataFolder);
        logDeprecatedSections(cfg, logger, debugVerbose);

        Map<String, Profile> profiles = new LinkedHashMap<>();
        Map<String, Integer> presetCounts = new LinkedHashMap<>();
        Set<String> fallbackProfiles = ConcurrentHashMap.newKeySet();

        ConfigurationSection profilesSection = cfg.getConfigurationSection("profiles");
        boolean legacy = false;
        if (profilesSection == null) {
            legacy = true;
            warnings.incrementAndGet();
            logger.warning(
                    "Legacy config detected (root presets). Please migrate to the new profiles format.");

            Profile profile = parseProfile(cfg, "default", dataFolder, logger, fallbackIconPath, warnings);
            profiles.put("default", profile);
            presetCounts.put("default", profile.presets().size());
            if (profile.presets().size() == 1 && "default".equals(profile.presets().get(0).id())) {
                fallbackProfiles.add("default");
            }
        } else {
            for (String profileId : profilesSection.getKeys(false)) {
                ConfigurationSection section = profilesSection.getConfigurationSection(profileId);
                if (section == null) {
                    continue;
                }
                Profile profile = parseProfile(section, profileId, dataFolder, logger, fallbackIconPath, warnings);
                profiles.put(profileId, profile);
                presetCounts.put(profileId, profile.presets().size());
                if (profile.presets().size() == 1 && "default".equals(profile.presets().get(0).id())) {
                    fallbackProfiles.add(profileId);
                }
            }
        }

        if (profiles.isEmpty()) {
            warnings.incrementAndGet();
            logger.warning("No profiles found. Using built-in fallback profile.");
            Profile fallback = fallbackProfile("default", fallbackIconPath);
            profiles.put("default", fallback);
            presetCounts.put("default", fallback.presets().size());
            fallbackProfiles.add("default");
        }

        if (!profiles.containsKey(activeProfile)) {
            warnings.incrementAndGet();
            String fallbackId = profiles.keySet().iterator().next();
            logger.warning("Active profile '" + activeProfile + "' not found. Using '" + fallbackId + "'.");
            activeProfile = fallbackId;
        }

        ConfigModel model = new ConfigModel(
                activeProfile,
                placeholdersEnabled,
                fallbackIconPath,
                colorFormat,
                debugSelfTest,
                debugVerbose,
                Collections.unmodifiableMap(profiles));

        return new LoadResult(model, warnings.get(), legacy, presetCounts, fallbackProfiles);
    }

    private static Profile parseProfile(ConfigurationSection section, String profileId, File dataFolder, Logger logger,
            String fallbackIconPath, AtomicInteger warnings) {
        String selectionModeRaw = section.getString("selectionMode", SelectionMode.STICKY_PER_IP.name());
        SelectionMode selectionMode = SelectionMode.from(selectionModeRaw);
        if (selectionMode == null) {
            warn(logger, warnings,
                    "Unknown selectionMode '" + selectionModeRaw + "' in profile '" + profileId
                            + "'. Using STICKY_PER_IP.");
            selectionMode = SelectionMode.STICKY_PER_IP;
        }

        int stickyTtlSeconds = clampInt(section.getInt("stickyTtlSeconds", 10), 1, Integer.MAX_VALUE,
                "stickyTtlSeconds", profileId, logger, warnings);
        int stickyMaxEntries = clampInt(section.getInt("stickyMaxEntriesPerProfile", 10000), 1, Integer.MAX_VALUE,
                "stickyMaxEntriesPerProfile", profileId, logger, warnings);
        int stickyCleanupEvery = clampInt(section.getInt("stickyCleanupEveryNPings", 500), 1, Integer.MAX_VALUE,
                "stickyCleanupEveryNPings", profileId, logger, warnings);

        boolean animEnabled = section.getBoolean("animation.enabled", true);
        long interval = section.getLong("animation.frameIntervalMillis", DEFAULT_FRAME_INTERVAL_MILLIS);
        if (interval < 100L) {
            warn(logger, warnings,
                    "animation.frameIntervalMillis in profile '" + profileId + "' must be >= 100. Using 100.");
            interval = 100L;
        }

        String animModeRaw = section.getString("animation.motdAnimationMode", AnimationMode.GLOBAL.name());
        AnimationMode animMode = AnimationMode.from(animModeRaw);
        if (animMode == null) {
            warn(logger, warnings,
                    "Unknown animation.motdAnimationMode '" + animModeRaw + "' in profile '" + profileId
                            + "'. Using GLOBAL.");
            animMode = AnimationMode.GLOBAL;
        }

        Profile.AnimationSettings animation = new Profile.AnimationSettings(animEnabled, interval, animMode);
        Profile.PlayerCountSettings playerCount = parsePlayerCount(section.getConfigurationSection("playerCount"),
                profileId, logger, warnings);

        List<Preset> presets = parsePresetList(section.getMapList("presets"), dataFolder, logger, fallbackIconPath,
                profileId, warnings);
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
                animation,
                playerCount,
                List.copyOf(presets));
    }

    private static Profile.PlayerCountSettings parsePlayerCount(ConfigurationSection section, String profileId,
            Logger logger, AtomicInteger warnings) {
        if (section == null) {
            return defaultPlayerCount();
        }

        boolean disableHover = section.getBoolean("disableHover", false);
        boolean hidePlayerCount = section.getBoolean("hidePlayerCount", false);

        Profile.FakePlayersSettings fakePlayers = parseFakePlayers(
                section.getConfigurationSection("fakePlayers"), profileId, logger, warnings);

        ConfigurationSection justX = section.getConfigurationSection("justXMore");
        boolean justXEnabled = justX != null && justX.getBoolean("enabled", false);
        int justXValue = clampInt(justX != null ? justX.getInt("x", 0) : 0, 0, Integer.MAX_VALUE, "justXMore.x",
                profileId, logger, warnings);

        ConfigurationSection maxPlayers = section.getConfigurationSection("maxPlayers");
        boolean maxPlayersEnabled = maxPlayers != null && maxPlayers.getBoolean("enabled", false);
        int maxPlayersValue = clampInt(maxPlayers != null ? maxPlayers.getInt("value", 0) : 0, 1,
                Integer.MAX_VALUE, "maxPlayers.value", profileId, logger, warnings);

        Profile.JustXMoreSettings justXMore = new Profile.JustXMoreSettings(justXEnabled, justXValue);
        Profile.MaxPlayersSettings maxPlayersSettings = new Profile.MaxPlayersSettings(maxPlayersEnabled,
                maxPlayersValue);

        return new Profile.PlayerCountSettings(disableHover, hidePlayerCount, fakePlayers, justXMore,
                maxPlayersSettings);
    }

    private static Profile.PlayerCountSettings defaultPlayerCount() {
        return new Profile.PlayerCountSettings(false, false,
                new Profile.FakePlayersSettings(false, Profile.FakePlayersMode.STATIC, 0, 0, 0.0),
                new Profile.JustXMoreSettings(false, 0),
                new Profile.MaxPlayersSettings(false, 0));
    }

    private static Profile.FakePlayersSettings parseFakePlayers(ConfigurationSection section, String profileId,
            Logger logger, AtomicInteger warnings) {
        if (section == null) {
            return new Profile.FakePlayersSettings(false, Profile.FakePlayersMode.STATIC, 0, 0, 0.0);
        }

        boolean enabled = section.getBoolean("enabled", false);
        String modeRaw = section.getString("mode", "static");
        Profile.FakePlayersMode mode = parseFakePlayersMode(modeRaw);
        if (mode == null) {
            warn(logger, warnings,
                    "Unknown fakePlayers.mode '" + modeRaw + "' in profile '" + profileId + "'. Using static.");
            mode = Profile.FakePlayersMode.STATIC;
        }

        String valueRaw = section.getString("value", "0");
        int min = 0;
        int max = 0;
        double percent = 0.0;

        if (mode == Profile.FakePlayersMode.PERCENT) {
            percent = parsePercent(valueRaw);
            if (percent < 0) {
                warn(logger, warnings,
                        "fakePlayers.value in profile '" + profileId + "' must be >= 0. Using 0.");
                percent = 0.0;
            }
        } else if (mode == Profile.FakePlayersMode.RANDOM) {
            int[] range = parseRange(valueRaw);
            min = Math.max(0, range[0]);
            max = Math.max(min, range[1]);
        } else {
            min = Math.max(0, parseInt(valueRaw));
            max = min;
        }

        return new Profile.FakePlayersSettings(enabled, mode, min, max, percent);
    }

    private static Profile.FakePlayersMode parseFakePlayersMode(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Profile.FakePlayersMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int[] parseRange(String raw) {
        if (raw == null) {
            return new int[] { 0, 0 };
        }
        String cleaned = raw.trim();
        String[] parts = cleaned.split("[:\\-]");
        if (parts.length == 2) {
            return new int[] { parseInt(parts[0]), parseInt(parts[1]) };
        }
        int value = parseInt(cleaned);
        return new int[] { value, value };
    }

    private static double parsePercent(String raw) {
        if (raw == null) {
            return 0.0;
        }
        String cleaned = raw.trim();
        if (cleaned.endsWith("%")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static void validateDataFolder(File dataFolder, Logger logger, AtomicInteger warnings) {
        if (dataFolder == null) {
            warn(logger, warnings, "Data folder is not available for BetterMOTD.");
        }
    }

    private static String resolveFallbackIconPath(File dataFolder) {
        if (dataFolder == null) {
            return null;
        }
        return "icons/default.png";
    }

    private static List<Preset> parsePresetList(List<?> list, File dataFolder, Logger logger, String fallbackIconPath,
            String profileId, AtomicInteger warnings) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

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
                warn(logger, warnings,
                        "Preset '" + id + "' in profile '" + profileId + "' has weight < 1. Using 1.");
                weight = 1;
            }

            String icon = resolveIcon(map.get("icon"), dataFolder, logger, fallbackIconPath, id, profileId, warnings);

            List<String> motd = normalizeMotdLines(strList(map.get("motd")), profileId, id, logger, warnings);
            List<String> motdFrames = normalizeFrames(strList(map.get("motdFrames")), profileId, id, logger,
                    warnings);

            if (motd.isEmpty() && motdFrames.isEmpty()) {
                warn(logger, warnings,
                        "Preset '" + id + "' in profile '" + profileId + "' has no motd or motdFrames. Skipping.");
                continue;
            }

            presets.add(new Preset(id, weight, icon, motd, motdFrames));
        }

        return presets;
    }

    private static List<String> normalizeMotdLines(List<String> lines, String profileId, String presetId,
            Logger logger, AtomicInteger warnings) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }
        String raw;
        if (lines.size() == 1) {
            raw = String.valueOf(lines.get(0));
        } else {
            if (lines.size() > 2) {
                warn(logger, warnings,
                        "Preset '" + presetId + "' in profile '" + profileId
                                + "' motd has more than 2 lines. Using first two.");
            }
            raw = String.valueOf(lines.get(0)) + "\n" + String.valueOf(lines.get(1));
        }
        String normalized = normalizeFrameString(raw, profileId, presetId, -1, logger, warnings);
        String[] parts = normalized.split("\n", -1);
        List<String> out = new ArrayList<>(2);
        out.add(parts[0]);
        out.add(parts[1]);
        return out;
    }

    private static List<String> normalizeFrames(List<String> frames, String profileId, String presetId,
            Logger logger, AtomicInteger warnings) {
        if (frames == null || frames.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(frames.size());
        int index = 0;
        for (String frame : frames) {
            String normalized = normalizeFrameString(frame, profileId, presetId, index, logger, warnings);
            out.add(normalized);
            index++;
        }
        return out;
    }

    private static String normalizeFrameString(String raw, String profileId, String presetId, int frameIndex,
            Logger logger, AtomicInteger warnings) {
        String safe = raw == null ? "" : raw;
        String[] parts = safe.split("\n", -1);
        if (parts.length <= 1) {
            return safe + "\n";
        }
        if (parts.length > 2) {
            String location = frameIndex >= 0 ? "frame " + frameIndex : "motd";
            warn(logger, warnings,
                    "Preset '" + presetId + "' in profile '" + profileId + "' " + location
                            + " has more than 2 lines. Using first two.");
        }
        return parts[0] + "\n" + parts[1];
    }

    private static Profile fallbackProfile(String id, String fallbackIconPath) {
        Profile.AnimationSettings animation = new Profile.AnimationSettings(true, DEFAULT_FRAME_INTERVAL_MILLIS,
                AnimationMode.GLOBAL);
        Profile.PlayerCountSettings playerCount = defaultPlayerCount();
        return new Profile(id, SelectionMode.STICKY_PER_IP, 10, 10000, 500, animation, playerCount,
                List.of(Preset.fallback(fallbackIconPath)));
    }

    private static String resolveIcon(Object raw, File dataFolder, Logger logger, String fallbackIconPath,
            String presetId, String profileId, AtomicInteger warnings) {
        String icon = str(raw, null);
        if (icon == null || icon.isBlank()) {
            if (fallbackIconPath != null) {
                warn(logger, warnings,
                        "Preset '" + presetId + "' in profile '" + profileId + "' has no icon. Using "
                                + fallbackIconPath + ".");
                return fallbackIconPath;
            }
            warn(logger, warnings,
                    "Preset '" + presetId + "' in profile '" + profileId + "' has no icon and no default icon.");
            return null;
        }

        String normalized = IconCache.normalizeIconPath(icon);
        if (normalized == null || dataFolder == null) {
            return normalized;
        }

        File iconFile = new File(dataFolder, normalized);
        if (!iconFile.isFile()) {
            if (fallbackIconPath != null) {
                warn(logger, warnings,
                        "Preset '" + presetId + "' in profile '" + profileId + "' icon not found: "
                                + iconFile.getPath() + ". Using " + fallbackIconPath + ".");
                return fallbackIconPath;
            }
            warn(logger, warnings,
                    "Preset '" + presetId + "' in profile '" + profileId + "' icon not found: "
                            + iconFile.getPath() + ".");
            return null;
        }

        return normalized;
    }

    private static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    private static int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int intv(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static List<String> strList(Object o) {
        if (o == null) {
            return Collections.emptyList();
        }
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object it : list) {
                out.add(String.valueOf(it));
            }
            return out;
        }
        return Collections.emptyList();
    }

    private static void warn(Logger logger, AtomicInteger warnings, String message) {
        logger.warning(message);
        warnings.incrementAndGet();
    }

    private static void logDeprecatedSections(FileConfiguration cfg, Logger logger, boolean debugVerbose) {
        if (!debugVerbose) {
            return;
        }
        List<String> deprecated = new ArrayList<>();
        if (cfg.getConfigurationSection("whitelist") != null) {
            deprecated.add("whitelist");
        }
        if (cfg.getConfigurationSection("routing") != null) {
            deprecated.add("routing");
        }
        if (!deprecated.isEmpty()) {
            logger.info("Ignoring deprecated sections: " + String.join(", ", deprecated));
        }
    }

    private static int clampInt(int value, int min, int max, String field, String profileId, Logger logger,
            AtomicInteger warnings) {
        if (value < min) {
            warn(logger, warnings,
                    field + " in profile '" + profileId + "' must be >= " + min + ". Using " + min + ".");
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    public record LoadResult(ConfigModel config, int warnings, boolean legacy, Map<String, Integer> presetCounts,
            Set<String> fallbackProfiles) {
    }

    public enum SelectionMode {
        RANDOM,
        STICKY_PER_IP,
        HASHED_PER_IP,
        ROTATE;

        public static SelectionMode from(String value) {
            if (value == null) {
                return null;
            }
            for (SelectionMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    public enum AnimationMode {
        GLOBAL,
        PER_IP_STICKY;

        public static AnimationMode from(String value) {
            if (value == null) {
                return null;
            }
            for (AnimationMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return null;
        }
    }
}
