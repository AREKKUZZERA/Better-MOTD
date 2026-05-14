package bettermotd;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.event.server.ServerListPingEvent;

public final class PlayerCountService {

    private final Logger logger;
    private final AtomicBoolean warnedOnlineUnsupported = new AtomicBoolean();
    private final AtomicBoolean warnedHidePlayers = new AtomicBoolean();
    private final AtomicBoolean warnedHover = new AtomicBoolean();

    public PlayerCountService(Logger logger) {
        this.logger = logger;
    }

    public PlayerCountResult compute(Profile profile, int online, int max) {
        int safeOnline = Math.max(0, online);
        int safeMax = Math.max(0, max);

        Profile.PlayerCountSettings settings = profile.playerCount();

        int fakeDelta = computeFakePlayers(settings.fakePlayers(), safeOnline);
        int displayOnline = safeOnline + fakeDelta;

        int displayMax = safeMax;
        if (settings.justXMore().enabled()) {
            displayMax =
                    Math.max(0, displayOnline + Math.max(0, settings.justXMore().x()));
        }
        if (settings.maxPlayers().enabled()) {
            displayMax = Math.max(1, settings.maxPlayers().value());
        }

        return new PlayerCountResult(
                safeOnline,
                safeMax,
                displayOnline,
                displayMax,
                fakeDelta,
                settings.hidePlayerCount(),
                settings.disableHover());
    }

    public void apply(ServerListPingEvent event, PlayerCountResult result, PaperPingAdapter paper) {
        if (event == null || result == null) return;

        boolean onlineApplied = false;
        if (paper != null) {
            onlineApplied = paper.applyOnlinePlayers(event, result.displayOnline());
        }

        try {
            event.setMaxPlayers(result.displayMax());
        } catch (Exception e) {
            warn("Failed to set max players: " + e.getMessage());
        }

        if (!onlineApplied) {
            int realOnline = Math.max(0, event.getNumPlayers());
            if (result.displayMax() < realOnline) {
                try {
                    event.setMaxPlayers(realOnline);
                } catch (Exception ignored) {
                }
            }

            if (result.fakeDelta() != 0) {
                warnOnce(
                        warnedOnlineUnsupported,
                        "fakePlayers is enabled, but this server does not support setting online player count via Bukkit. "
                                + "Install/use Paper to enable fake online count.");
            }
        }

        if (result.hidePlayerCount()) {
            if (!(paper != null && paper.applyHidePlayers(event, true))) {
                warnOnce(
                        warnedHidePlayers,
                        "hidePlayerCount is enabled but this server does not support hiding player counts.");
            }
        }

        if (result.disableHover()) {
            if (!(paper != null && paper.applyDisableHover(event))) {
                warnOnce(
                        warnedHover,
                        "disableHover is enabled but this server does not support disabling hover samples.");
            }
        }
    }

    private int computeFakePlayers(Profile.FakePlayersSettings fakePlayers, int online) {
        if (fakePlayers == null || !fakePlayers.enabled()) return 0;

        return switch (fakePlayers.mode()) {
            case STATIC -> Math.max(0, fakePlayers.min());
            case RANDOM -> randomBetween(fakePlayers.min(), fakePlayers.max());
            case PERCENT -> (int) Math.ceil(online * Math.max(0.0, fakePlayers.percent()) / 100.0);
        };
    }

    private int randomBetween(int min, int max) {
        int low = Math.max(0, Math.min(min, max));
        int high = Math.max(low, Math.max(min, max));
        if (low == high) return low;

        return ThreadLocalRandom.current().nextInt(low, high + 1);
    }

    private void warnOnce(AtomicBoolean flag, String message) {
        if (logger == null) return;
        if (flag.compareAndSet(false, true)) {
            logger.warning(message);
        }
    }

    private void warn(String message) {
        if (logger != null) logger.warning(message);
    }

    public record PlayerCountResult(
            int baseOnline,
            int baseMax,
            int displayOnline,
            int displayMax,
            int fakeDelta,
            boolean hidePlayerCount,
            boolean disableHover) {}
}
