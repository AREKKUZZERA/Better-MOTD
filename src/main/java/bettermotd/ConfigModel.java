package bettermotd;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.Map;

public record ConfigModel(
        String selectionMode,
        int stickyTtlSeconds,
        boolean animationEnabled,
        long frameIntervalMillis,
        String motdAnimationMode,
        boolean placeholdersEnabled,
        String fallbackIconPath,
        List<Preset> presets) {
    public static final long DEFAULT_FRAME_INTERVAL_MILLIS = 450L;
    public static final List<String> FALLBACK_MOTD_LINES = List.of("BetterMOTD", "1.21.x");

    public static ConfigModel empty() {
        return new ConfigModel(
                "STICKY_PER_IP",
                10,
                true,
                DEFAULT_FRAME_INTERVAL_MILLIS,
                "GLOBAL",
                true,
                null,
                Collections.emptyList());
    }

    public static LoadResult load(FileConfiguration cfg, File dataFolder, Logger logger) {
        if (cfg == null || logger == null) {
            return new LoadResult(empty(), 0, 0);
        }

        int warnings = 0;
        warnings += validateDataFolder(dataFolder, logger);

        String selectionMode = cfg.getString("selectionMode", "STICKY_PER_IP");
        if (!SelectionMode.isSupported(selectionMode)) {
            logger.warning("Unknown selectionMode '" + selectionMode + "', defaulting to STICKY_PER_IP.");
            selectionMode = "STICKY_PER_IP";
            warnings++;
        }

        int stickyTtlSeconds = cfg.getInt("stickyTtlSeconds", 10);
        if (stickyTtlSeconds <= 0) {
            logger.warning("stickyTtlSeconds must be > 0. Using default 10.");
            stickyTtlSeconds = 10;
            warnings++;
        }

        boolean animEnabled = cfg.getBoolean("animation.enabled", true);
        long interval = cfg.getLong("animation.frameIntervalMillis", DEFAULT_FRAME_INTERVAL_MILLIS);
        if (interval <= 0) {
            logger.warning(
                    "animation.frameIntervalMillis must be > 0. Using default " + DEFAULT_FRAME_INTERVAL_MILLIS + ".");
            interval = DEFAULT_FRAME_INTERVAL_MILLIS;
            warnings++;
        }

        String animMode = cfg.getString("animation.motdAnimationMode", "GLOBAL");
        if (!AnimationMode.isSupported(animMode)) {
            logger.warning("Unknown animation.motdAnimationMode '" + animMode + "', defaulting to GLOBAL.");
            animMode = "GLOBAL";
            warnings++;
        }

        boolean placeholdersEnabled = cfg.getBoolean("placeholders.enabled", true);

        String fallbackIconPath = resolveFallbackIconPath(dataFolder);

        List<Preset> presets = parsePresetList(cfg.getMapList("presets"), dataFolder, logger, fallbackIconPath);
        int presetsLoaded = presets.size();
        if (presets.isEmpty()) {
            logger.warning("No presets found in root 'presets'. Falling back to built-in preset at runtime.");
            warnings++;
        }

        ConfigModel model = new ConfigModel(
                selectionMode,
                stickyTtlSeconds,
                animEnabled,
                interval,
                animMode,
                placeholdersEnabled,
                fallbackIconPath,
                presets);

        return new LoadResult(model, presetsLoaded, warnings);
    }

    private static int validateDataFolder(File dataFolder, Logger logger) {
        if (dataFolder == null) {
            logger.warning("Data folder is not available for BetterMOTD.");
            return 1;
        }
        return 0;
    }

    private static String resolveFallbackIconPath(File dataFolder) {
        if (dataFolder == null) {
            return null;
        }
        File defaultIcon = new File(dataFolder, "icons/default.png");
        if (defaultIcon.isFile()) {
            return "icons/default.png";
        }
        return null;
    }

    private static List<Preset> parsePresetList(List<?> list, File dataFolder, Logger logger, String fallbackIconPath) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

        List<Preset> presets = new ArrayList<>(list.size());
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }

            String id = str(map.get("id"), "preset");
            int weight = intv(map.get("weight"), 1);

            String icon = resolveIcon(map.get("icon"), dataFolder, logger, fallbackIconPath, id);

            List<String> motd = strList(map.get("motd"));
            List<String> motdFrames = strList(map.get("motdFrames"));

            if (motd.isEmpty() && motdFrames.isEmpty()) {
                logger.warning("Preset '" + id + "' has no motd or motdFrames. Using fallback MOTD lines.");
                motd = FALLBACK_MOTD_LINES;
            }

            presets.add(new Preset(id, weight, icon, motd, motdFrames));
        }

        return presets;
    }

    private static String resolveIcon(Object raw, File dataFolder, Logger logger, String fallbackIconPath,
            String presetId) {
        String icon = str(raw, null);
        if (icon == null || icon.isBlank()) {
            if (fallbackIconPath != null) {
                logger.warning("Preset '" + presetId + "' has no icon set. Using " + fallbackIconPath + ".");
                return fallbackIconPath;
            }
            logger.warning("Preset '" + presetId + "' has no icon set and no default icon exists.");
            return null;
        }

        String normalized = IconCache.normalizeIconPath(icon);
        if (normalized == null || dataFolder == null) {
            return normalized;
        }

        File iconFile = new File(dataFolder, normalized);
        if (!iconFile.isFile()) {
            if (fallbackIconPath != null) {
                logger.warning("Preset '" + presetId + "' icon not found: " + iconFile.getPath() + ". Using "
                        + fallbackIconPath + ".");
                return fallbackIconPath;
            }
            logger.warning("Preset '" + presetId + "' icon not found: " + iconFile.getPath() + ".");
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

    public record LoadResult(ConfigModel config, int presetsLoaded, int warnings) {
    }

    public enum SelectionMode {
        RANDOM,
        STICKY_PER_IP,
        HASHED_PER_IP,
        ROTATE;

        public static boolean isSupported(String value) {
            if (value == null) {
                return false;
            }
            for (SelectionMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    public enum AnimationMode {
        GLOBAL,
        PER_IP_STICKY;

        public static boolean isSupported(String value) {
            if (value == null) {
                return false;
            }
            for (AnimationMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
