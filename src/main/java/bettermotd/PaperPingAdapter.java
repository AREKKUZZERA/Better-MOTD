package bettermotd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.event.server.ServerListPingEvent;

/**
 * Adapter for Paper-specific ping event features.
 * Uses direct Paper API instead of reflection for reliability and performance.
 */
public final class PaperPingAdapter {

    private static final boolean PAPER_AVAILABLE;

    static {
        boolean available = false;
        try {
            Class.forName("com.destroystokyo.paper.event.server.PaperServerListPingEvent");
            available = true;
        } catch (ClassNotFoundException ignored) {
        }
        PAPER_AVAILABLE = available;
    }

    private final Logger logger;
    private final AtomicBoolean warnedOnline = new AtomicBoolean();
    private final AtomicBoolean warnedHidePlayers = new AtomicBoolean();
    private final AtomicBoolean warnedHover = new AtomicBoolean();

    public PaperPingAdapter(Logger logger) {
        this.logger = logger;
        if (!PAPER_AVAILABLE && logger != null) {
            logger.info("Paper API not detected. Using Bukkit ping handling.");
        }
    }

    public boolean isPaperEvent(ServerListPingEvent event) {
        return PAPER_AVAILABLE && event instanceof PaperServerListPingEvent;
    }

    public boolean applyMotd(ServerListPingEvent event, Component component) {
        if (!PAPER_AVAILABLE || !(event instanceof PaperServerListPingEvent paper) || component == null) {
            return false;
        }
        paper.motd(component);
        return true;
    }

    public boolean applyOnlinePlayers(ServerListPingEvent event, int online) {
        if (!PAPER_AVAILABLE || !(event instanceof PaperServerListPingEvent paper)) {
            return false;
        }
        try {
            paper.setNumPlayers(Math.max(0, online));
            return true;
        } catch (Exception e) {
            warnOnce(warnedOnline, "Failed to apply online player count via Paper API: " + e.getMessage());
            return false;
        }
    }

    public boolean applyHidePlayers(ServerListPingEvent event, boolean hide) {
        if (!PAPER_AVAILABLE || !(event instanceof PaperServerListPingEvent paper)) {
            return false;
        }
        try {
            paper.setHidePlayers(hide);
            return true;
        } catch (Exception e) {
            warnOnce(warnedHidePlayers, "Failed to hide players via Paper API: " + e.getMessage());
            return false;
        }
    }

    public boolean applyDisableHover(ServerListPingEvent event) {
        if (!PAPER_AVAILABLE || !(event instanceof PaperServerListPingEvent paper)) {
            return false;
        }
        try {
            paper.getListedPlayers().clear();
            return true;
        } catch (Exception e) {
            warnOnce(warnedHover, "Failed to disable hover via Paper API: " + e.getMessage());
            return false;
        }
    }

    public boolean applyHoverLines(ServerListPingEvent event, List<String> lines) {
        if (!PAPER_AVAILABLE || !(event instanceof PaperServerListPingEvent paper) || lines == null) {
            return false;
        }
        try {
            List<ListedPlayerInfo> listedPlayers = paper.getListedPlayers();
            listedPlayers.clear();
            int index = 0;
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                listedPlayers.add(new ListedPlayerInfo(
                        line, UUID.nameUUIDFromBytes(("BetterMOTD:" + index++).getBytes(StandardCharsets.UTF_8))));
            }
            return true;
        } catch (Exception e) {
            warnOnce(warnedHover, "Failed to apply hover sample lines via Paper API: " + e.getMessage());
            return false;
        }
    }

    public int protocolVersion(ServerListPingEvent event) {
        if (PAPER_AVAILABLE && event instanceof PaperServerListPingEvent paper) {
            try {
                return paper.getClient().getProtocolVersion();
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private void warnOnce(AtomicBoolean flag, String message) {
        if (logger != null && flag.compareAndSet(false, true)) {
            logger.warning(message);
        }
    }
}
