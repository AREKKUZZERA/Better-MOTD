package bettermotd;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TextFormatService {

    private static final Pattern AMPERSAND_HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern MINIMESSAGE_TAG_PATTERN =
            Pattern.compile("<(?:/?[a-z][a-z0-9_:-]*(?::[^>]+)?|#[0-9a-fA-F]{6})>", Pattern.CASE_INSENSITIVE);

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySectionSerializer = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private final LegacyComponentSerializer legacyAmpersandSerializer = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    public ParseResult parseToComponentDetailed(String input, ColorFormat format) {
        if (input == null) {
            return new ParseResult(Component.empty(), format, false);
        }
        int nl = input.indexOf('\n');
        if (nl < 0) {
            return parseSingleLine(input, format);
        }
        // Multi-line: split and join
        String line1 = input.substring(0, nl);
        String line2 = input.substring(nl + 1);
        ColorFormat resolved = resolveFormat(input, format);
        ParseResult r1 = parseSingleLine(line1, resolved);
        ParseResult r2 = parseSingleLine(line2, resolved);
        Component joined = Component.join(JoinConfiguration.newlines(), r1.component(), r2.component());
        return new ParseResult(joined, resolved, r1.fallbackUsed() || r2.fallbackUsed());
    }

    public String serializeToLegacy(Component component) {
        return component == null ? "" : legacySectionSerializer.serialize(component);
    }

    /** Converts &#RRGGBB hex codes to MiniMessage <#RRGGBB> format. */
    public String convertAmpersandHexToMiniMessage(String input) {
        if (input == null || input.indexOf('&') < 0) {
            return input;
        }
        Matcher matcher = AMPERSAND_HEX_PATTERN.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuffer buffer = new StringBuffer(input.length() + 16);
        do {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("<#" + matcher.group(1) + ">"));
        } while (matcher.find());
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private ParseResult parseSingleLine(String input, ColorFormat format) {
        ColorFormat resolved = resolveFormat(input, format);
        try {
            Component component =
                    switch (resolved) {
                        case MINI_MESSAGE -> miniMessage.deserialize(input);
                        case HEX_AMPERSAND -> miniMessage.deserialize(convertAmpersandHexToMiniMessage(input));
                        case JSON -> GsonComponentSerializer.gson().deserialize(input);
                        case LEGACY_SECTION -> legacySectionSerializer.deserialize(input);
                        case LEGACY_AMPERSAND -> legacyAmpersandSerializer.deserialize(input);
                        case AUTO, AUTO_STRICT -> Component.text(input);
                    };
            return new ParseResult(component, resolved, false);
        } catch (Exception e) {
            return new ParseResult(Component.text(input), resolved, true);
        }
    }

    private ColorFormat resolveFormat(String input, ColorFormat format) {
        if (format == null || format == ColorFormat.AUTO || format == ColorFormat.AUTO_STRICT) {
            return detectFormat(input, format == ColorFormat.AUTO_STRICT);
        }
        return format;
    }

    private ColorFormat detectFormat(String input, boolean strictMiniMessage) {
        if (input == null || input.isEmpty()) {
            return ColorFormat.AUTO;
        }
        String trimmed = input.trim();
        if (looksLikeJson(trimmed)) return ColorFormat.JSON;
        if (looksLikeMiniMessage(trimmed)) return ColorFormat.MINI_MESSAGE;
        if (AMPERSAND_HEX_PATTERN.matcher(trimmed).find()) return ColorFormat.HEX_AMPERSAND;
        if (trimmed.contains("§x§") || trimmed.indexOf('§') >= 0) return ColorFormat.LEGACY_SECTION;
        if (trimmed.contains("&x&") || trimmed.indexOf('&') >= 0) return ColorFormat.LEGACY_AMPERSAND;
        return strictMiniMessage ? ColorFormat.AUTO_STRICT : ColorFormat.AUTO;
    }

    private boolean looksLikeMiniMessage(String input) {
        return MINIMESSAGE_TAG_PATTERN.matcher(input).find();
    }

    private boolean looksLikeJson(String trimmed) {
        return trimmed.length() > 2
                && trimmed.charAt(0) == '{'
                && trimmed.charAt(trimmed.length() - 1) == '}'
                && (trimmed.contains("\"text\"") || trimmed.contains("\"extra\"") || trimmed.contains("\"color\""));
    }

    public record ParseResult(Component component, ColorFormat usedFormat, boolean fallbackUsed) {}
}
