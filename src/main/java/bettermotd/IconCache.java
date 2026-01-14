package bettermotd;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class IconCache {

    private static final String DEFAULT_ICON_RESOURCE = "icons/default.png";
    private static final String DEFAULT_ICON_TARGET = "icons/default.png";

    private final JavaPlugin plugin;
    private final Map<String, CachedServerIcon> cache = new ConcurrentHashMap<>();

    private File iconsDir;

    public IconCache(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigModel cfg) {
        cache.clear();

        ensureIconsDirectory();
        ensureDefaultIconIfNoPngs();
        warnIfIconsEmpty();
    }

    public void clear() {
        cache.clear();
    }

    public CachedServerIcon pickIcon(Preset preset) {
        String path = (preset != null) ? preset.icon() : null;

        if (path == null || path.isBlank()) {
            path = DEFAULT_ICON_TARGET;
        }

        return loadIcon(path);
    }

    public static String normalizeIconPath(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return null;
        }
        String normalized = relPath;
        if (!normalized.contains("/") && !normalized.contains("\\") && normalized.toLowerCase().endsWith(".png")) {
            normalized = "icons/" + normalized;
        }
        return normalized.replace("\\", "/");
    }

    /*
     * =========================
     * Internal helpers
     * =========================
     */

    private void ensureIconsDirectory() {
        this.iconsDir = new File(plugin.getDataFolder(), "icons");

        if (!iconsDir.exists()) {
            if (iconsDir.mkdirs()) {
                plugin.getLogger().info("Created icons directory: " + iconsDir.getPath());
            } else {
                plugin.getLogger().warning("Failed to create icons directory: " + iconsDir.getPath());
            }
        }
    }

    private void ensureDefaultIconIfNoPngs() {
        if (iconsDir == null || !iconsDir.isDirectory())
            return;

        File[] pngs = iconsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (pngs != null && pngs.length > 0) {
            return; // already has png files
        }

        File target = new File(plugin.getDataFolder(), DEFAULT_ICON_TARGET);
        if (target.exists()) {
            return; // placeholder already exists
        }

        boolean ok = copyResourceToFile(DEFAULT_ICON_RESOURCE, target);
        if (ok) {
            plugin.getLogger().info("Created default icon: " + target.getPath());
        } else {
            plugin.getLogger().warning(
                    "No icons found and default icon resource is missing. " +
                            "Add a 64x64 PNG at: " + target.getPath());
        }
    }

    private void warnIfIconsEmpty() {
        if (iconsDir == null || !iconsDir.isDirectory())
            return;

        File[] pngs = iconsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (pngs == null || pngs.length == 0) {
            plugin.getLogger().warning(
                    "Icons directory is empty. Server list icon will not be shown. " +
                            "Place a 64x64 PNG into: " + iconsDir.getPath());
        }
    }

    private boolean copyResourceToFile(String resourcePath, File target) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null)
                return false;

            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Failed to create directory: " + parent.getPath());
                return false;
            }

            try (FileOutputStream out = new FileOutputStream(target)) {
                in.transferTo(out);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to write default icon: " + e.getMessage());
            return false;
        }
    }

    private CachedServerIcon loadIcon(String relPath) {
        String normalized = normalizeIconPath(relPath);
        if (normalized == null)
            return null;

        return cache.computeIfAbsent(normalized, key -> {
            try {
                File file = new File(plugin.getDataFolder(), key);
                if (!file.exists()) {
                    plugin.getLogger().warning("Icon not found: " + file.getPath());
                    return null;
                }

                Server server = Bukkit.getServer();
                return server.loadServerIcon(file);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load icon '" + normalized + "': " + e.getMessage());
                return null;
            }
        });
    }
}
