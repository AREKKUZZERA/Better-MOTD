package bettermotd;

import java.util.List;

public record Preset(
        String id,
        int weight,
        String icon,
        List<String> motd,
        List<String> motdFrames
) {
    public static Preset fallback(String iconPath) {
        return new Preset("fallback", 1, iconPath, ConfigModel.FALLBACK_MOTD_LINES, List.of());
    }
}
