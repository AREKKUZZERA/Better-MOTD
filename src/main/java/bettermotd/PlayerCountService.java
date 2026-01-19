package bettermotd;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.event.server.ServerListPingEvent;

public final class PlayerCountService {

    private final Logger logger;
    private final AtomicBoolean warnedHidePlayers = new AtomicBoolean();
    private final AtomicBoolean warnedHover = new AtomicBoolean();

    public PlayerCountService(Logger logger) {
        this.logger = logger;
    }

    public PlayerCountResult compute(Profile profile, String ip, int online, int max, long nowMs) {
        int safeOnline = Math.max(0, online);
        int safeMax = Math.max(0, max);

        Profile.PlayerCountSettings settings = profile.playerCount();
        int fakeDelta = computeFakePlayers(profile, settings.fakePlayers(), ip, safeOnline, nowMs);
        int displayOnline = safeOnline + fakeDelta;
        int displayMax = safeMax;

        if (settings.justXMore().enabled()) {
            displayMax = Math.max(0, displayOnline + Math.max(0, settings.justXMore().x()));
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
        if (event == null || result == null) {
            return;
        }

        try {
            // Use setNumPlayers for online count; setMaxPlayers is max slots.
            event.setNumPlayers(result.displayOnline());
            event.setMaxPlayers(result.displayMax());
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("Failed to set player counts: " + e.getMessage());
            }
        }

        if (result.hidePlayerCount()) {
            if (!(paper != null && paper.applyHidePlayers(event, true))) {
                warnOnce(warnedHidePlayers,
                        "hidePlayerCount is enabled but this server does not support hiding player counts.");
            }
        }

        if (result.disableHover()) {
            if (!(paper != null && paper.applyDisableHover(event))) {
                warnOnce(warnedHover,
                        "disableHover is enabled but this server does not support disabling hover samples.");
            }
        }
    }

    private int computeFakePlayers(Profile profile, Profile.FakePlayersSettings fakePlayers, String ip, int online,
            long nowMs) {
        if (fakePlayers == null || !fakePlayers.enabled()) {
            return 0;
        }

        return switch (fakePlayers.mode()) {
            case STATIC -> Math.max(0, fakePlayers.min());
            case RANDOM -> randomBetween(fakePlayers.min(), fakePlayers.max(), profile.selectionMode(), ip, nowMs);
            case PERCENT -> (int) Math.ceil(online * Math.max(0.0, fakePlayers.percent()) / 100.0);
        };
    }

    private int randomBetween(int min, int max, ConfigModel.SelectionMode selectionMode, String ip, long nowMs) {
        int low = Math.max(0, Math.min(min, max));
        int high = Math.max(low, Math.max(min, max));
        if (low == high) {
            return low;
        }
        if (selectionMode == ConfigModel.SelectionMode.STICKY_PER_IP && ip != null) {
            long bucket = nowMs / 60000L;
            long seed = Objects.hash(ip, bucket);
            java.util.Random random = new java.util.Random(seed);
            return random.nextInt(high - low + 1) + low;
        }
        return ThreadLocalRandom.current().nextInt(low, high + 1);
    }

    private void warnOnce(AtomicBoolean flag, String message) {
        if (logger == null) {
            return;
        }
        if (flag.compareAndSet(false, true)) {
            logger.warning(message);
        }
    }

    public record PlayerCountResult(
            int baseOnline,
            int baseMax,
            int displayOnline,
            int displayMax,
            int fakeDelta,
            boolean hidePlayerCount,
            boolean disableHover) {
    }
}
