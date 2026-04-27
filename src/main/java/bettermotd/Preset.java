package bettermotd;

import java.util.List;

public record Preset(
        String id,
        int weight,
        String icon,
        List<String> icons,
        List<String> motd,
        List<String> motdFrames,
        Conditions conditions) {
    public static Preset fallback(String iconPath) {
        return new Preset(
                "default", 1, iconPath, List.of(), ConfigModel.FALLBACK_MOTD_LINES, List.of(), Conditions.any());
    }

    public record Conditions(
            List<String> hostnames,
            List<String> hostnameContains,
            Integer minProtocol,
            Integer maxProtocol,
            Integer minOnline,
            Integer maxOnline) {

        public static Conditions any() {
            return new Conditions(List.of(), List.of(), null, null, null, null);
        }

        public boolean matches(RequestInfo request) {
            if (request == null) {
                return true;
            }
            String host = request.hostname() == null ? "" : request.hostname().toLowerCase(java.util.Locale.ROOT);
            if (!hostnames.isEmpty()) {
                boolean exact = false;
                for (String candidate : hostnames) {
                    if (host.equals(candidate.toLowerCase(java.util.Locale.ROOT))) {
                        exact = true;
                        break;
                    }
                }
                if (!exact) return false;
            }
            if (!hostnameContains.isEmpty()) {
                boolean contains = false;
                for (String candidate : hostnameContains) {
                    if (host.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
                        contains = true;
                        break;
                    }
                }
                if (!contains) return false;
            }
            int protocol = request.protocolVersion();
            if (minProtocol != null && protocol >= 0 && protocol < minProtocol) return false;
            if (maxProtocol != null && protocol >= 0 && protocol > maxProtocol) return false;
            if (minOnline != null && request.onlinePlayers() < minOnline) return false;
            if (maxOnline != null && request.onlinePlayers() > maxOnline) return false;
            return true;
        }
    }
}
