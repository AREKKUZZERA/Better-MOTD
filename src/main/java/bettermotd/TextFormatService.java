package bettermotd;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class TextFormatService {

    private static final Pattern AMPERSAND_HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");

    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySectionSerializer;
    private final LegacyComponentSerializer legacyAmpersandSerializer;

    public TextFormatService() {
        this.miniMessage = MiniMessage.miniMessage();
        this.legacySectionSerializer = LegacyComponentSerializer.builder()
                .character('§')
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();
        this.legacyAmpersandSerializer = LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();
    }

    public Component parseToComponent(String input, ColorFormat format) {
        return parseToComponentDetailed(input, format).component();
    }

    public Component parseLinesToComponent(List<String> lines, ColorFormat format) {
        return parseLinesToComponentDetailed(lines, format).component();
    }

    public ParseResult parseToComponentDetailed(String input, ColorFormat format) {
        if (input == null) {
            return new ParseResult(Component.empty(), format, false);
        }
        if (input.contains("\n")) {
            List<String> lines = splitLines(input);
            return parseLinesToComponentDetailed(lines, format);
        }
        return parseSingleLine(input, format);
    }

    public ParseResult parseLinesToComponentDetailed(List<String> lines, ColorFormat format) {
        if (lines == null || lines.isEmpty()) {
            return new ParseResult(Component.empty(), format, false);
        }
        String combined = String.join("\n", lines);
        ColorFormat resolved = resolveFormat(combined, format);
        List<Component> components = new ArrayList<>(lines.size());
        boolean fallback = false;
        for (String line : lines) {
            ParseResult lineResult = parseSingleLine(line, resolved);
            components.add(lineResult.component());
            fallback = fallback || lineResult.fallbackUsed();
        }
        Component joined = Component.join(JoinConfiguration.newlines(), components);
        return new ParseResult(joined, resolved, fallback);
    }

    public String serializeToLegacy(Component component) {
        if (component == null) {
            return "";
        }
        return legacySectionSerializer.serialize(component);
    }

    public String convertAmpersandHexToMiniMessage(String input) {
        if (input == null || input.indexOf('&') < 0) {
            return input;
        }
        Matcher matcher = AMPERSAND_HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer(input.length());
        while (matcher.find()) {
            String hex = matcher.group(1);
            String replacement = "<#" + hex + ">";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private ParseResult parseSingleLine(String input, ColorFormat format) {
        ColorFormat resolved = resolveFormat(input, format);
        try {
            Component component = switch (resolved) {
                case MINI_MESSAGE -> miniMessage.deserialize(input);
                case HEX_AMPERSAND -> miniMessage.deserialize(convertAmpersandHexToMiniMessage(input));
                case JSON -> GsonComponentSerializer.gson().deserialize(input);
                case LEGACY_SECTION -> legacySectionSerializer.deserialize(input);
                case LEGACY_AMPERSAND -> legacyAmpersandSerializer.deserialize(input);
                case AUTO -> Component.text(input);
            };
            return new ParseResult(component, resolved, false);
        } catch (Exception e) {
            return new ParseResult(Component.text(input), resolved, true);
        }
    }

    private ColorFormat resolveFormat(String input, ColorFormat format) {
        if (format == null || format == ColorFormat.AUTO) {
            return detectFormat(input);
        }
        return format;
    }

    private ColorFormat detectFormat(String input) {
        if (input == null || input.isEmpty()) {
            return ColorFormat.AUTO;
        }
        String trimmed = input.trim();
        if (looksLikeJson(trimmed)) {
            return ColorFormat.JSON;
        }
        if (trimmed.contains("<") && trimmed.contains(">")) {
            return ColorFormat.MINI_MESSAGE;
        }
        if (AMPERSAND_HEX_PATTERN.matcher(trimmed).find()) {
            return ColorFormat.HEX_AMPERSAND;
        }
        if (trimmed.contains("§x§")) {
            return ColorFormat.LEGACY_SECTION;
        }
        if (trimmed.contains("&x&")) {
            return ColorFormat.LEGACY_AMPERSAND;
        }
        if (trimmed.indexOf('§') >= 0) {
            return ColorFormat.LEGACY_SECTION;
        }
        if (trimmed.indexOf('&') >= 0) {
            return ColorFormat.LEGACY_AMPERSAND;
        }
        return ColorFormat.AUTO;
    }

    private boolean looksLikeJson(String trimmed) {
        if (!trimmed.startsWith("{")) {
            return false;
        }
        if (!trimmed.endsWith("}")) {
            return false;
        }
        return trimmed.contains("\"text\"") || trimmed.contains("\"extra\"") || trimmed.contains("\"color\"");
    }

    private List<String> splitLines(String input) {
        String[] parts = input.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            lines.add(part);
        }
        return lines;
    }

    public record ParseResult(Component component, ColorFormat usedFormat, boolean fallbackUsed) {
    }
}
