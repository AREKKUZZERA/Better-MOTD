package bettermotd;

import java.util.Locale;

public enum ColorFormat {
    MINI_MESSAGE,
    HEX_AMPERSAND,
    JSON,
    LEGACY_SECTION,
    LEGACY_AMPERSAND,
    AUTO;

    public static ColorFormat from(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ColorFormat format : values()) {
            if (format.name().equals(normalized)) {
                return format;
            }
        }
        return null;
    }
}
