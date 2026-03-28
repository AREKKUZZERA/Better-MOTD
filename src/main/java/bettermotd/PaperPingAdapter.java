package bettermotd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.util.List;
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
            paper.setPlayerSample(List.of());
            return true;
        } catch (Exception e) {
            warnOnce(warnedHover, "Failed to disable hover via Paper API: " + e.getMessage());
            return false;
        }
    }

    private void warnOnce(AtomicBoolean flag, String message) {
        if (logger != null && flag.compareAndSet(false, true)) {
            logger.warning(message);
        }
    }
}
