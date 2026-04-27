package bettermotd;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.FileConfiguration;

public final class MiniMotdImporter {

    private static final Pattern LINE_PATTERN =
            Pattern.compile("(?i)\\b(line1|line2|motd-first-line|motd-second-line)\\b\\s*[:=]\\s*[\"'](.*?)[\"']");
    private static final Pattern ICON_PATTERN = Pattern.compile("(?i)\\bicon\\b\\s*[:=]\\s*[\"'](.*?)[\"']");

    private MiniMotdImporter() {}

    public static ImportResult importInto(File source, FileConfiguration target) {
        if (source == null || !source.isFile()) {
            return new ImportResult(false, 0, "MiniMOTD config file not found.");
        }
        try {
            String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
            List<Map<String, Object>> presets = parsePresets(text);
            if (presets.isEmpty()) {
                return new ImportResult(false, 0, "No MiniMOTD line1/line2 entries found.");
            }
            target.set("profiles.imported.selectionMode", "RANDOM");
            target.set("profiles.imported.animation.enabled", false);
            target.set("profiles.imported.playerCount.disableHover", false);
            target.set("profiles.imported.playerCount.hidePlayerCount", false);
            target.set("profiles.imported.presets", presets);
            target.set("activeProfile", "imported");
            return new ImportResult(true, presets.size(), "Imported profile 'imported'.");
        } catch (Exception e) {
            return new ImportResult(false, 0, "MiniMOTD import failed: " + e.getMessage());
        }
    }

    private static List<Map<String, Object>> parsePresets(String text) {
        List<String[]> linePairs = new ArrayList<>();
        String currentLine1 = null;
        Matcher lineMatcher = LINE_PATTERN.matcher(text);
        while (lineMatcher.find()) {
            String key = lineMatcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String value = lineMatcher.group(2);
            if (key.equals("line1") || key.equals("motd-first-line")) {
                currentLine1 = value;
            } else if (currentLine1 != null) {
                linePairs.add(new String[] {currentLine1, value});
                currentLine1 = null;
            }
        }

        List<String> icons = new ArrayList<>();
        Matcher iconMatcher = ICON_PATTERN.matcher(text);
        while (iconMatcher.find()) {
            String icon = iconMatcher.group(1);
            if (!icon.equalsIgnoreCase("random") && IconCache.normalizeIconPath(icon) != null) {
                icons.add(icon);
            }
        }

        List<Map<String, Object>> presets = new ArrayList<>(linePairs.size());
        for (int i = 0; i < linePairs.size(); i++) {
            Map<String, Object> preset = new LinkedHashMap<>();
            preset.put("id", "minimotd-" + (i + 1));
            preset.put("weight", 1);
            if (!icons.isEmpty()) {
                preset.put("icon", icons.get(Math.min(i, icons.size() - 1)));
            } else {
                preset.put("icon", "default.png");
            }
            preset.put("motd", List.of(linePairs.get(i)[0], linePairs.get(i)[1]));
            presets.add(preset);
        }
        return presets;
    }

    public record ImportResult(boolean success, int presets, String message) {}
}
