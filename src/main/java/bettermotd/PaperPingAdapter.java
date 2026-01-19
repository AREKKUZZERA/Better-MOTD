package bettermotd;

import org.bukkit.event.server.ServerListPingEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import net.kyori.adventure.text.Component;

public final class PaperPingAdapter {

    private final Logger logger;
    private final Class<?> paperEventClass;
    private final Method motdMethod;
    private final Method hidePlayersMethod;
    private final Method setPlayerSampleMethod;
    private final AtomicBoolean warnedMotd = new AtomicBoolean();

    public PaperPingAdapter(Logger logger) {
        this.logger = logger;
        Class<?> paperClass = null;
        Method motd = null;
        Method hidePlayers = null;
        Method sample = null;
        try {
            paperClass = Class.forName("com.destroystokyo.paper.event.server.PaperServerListPingEvent");
            motd = paperClass.getMethod("motd", Component.class);
            try {
                hidePlayers = paperClass.getMethod("setHidePlayers", boolean.class);
            } catch (NoSuchMethodException ignored) {
                // older Paper
            }
            sample = findPlayerSampleMethod(paperClass);
        } catch (Exception e) {
            if (logger != null) {
                logger.info("Paper API not detected. Using Bukkit ping handling.");
            }
        }
        this.paperEventClass = paperClass;
        this.motdMethod = motd;
        this.hidePlayersMethod = hidePlayers;
        this.setPlayerSampleMethod = sample;
    }

    public boolean isPaperEvent(ServerListPingEvent event) {
        return paperEventClass != null && paperEventClass.isInstance(event);
    }

    public boolean applyMotd(ServerListPingEvent event, Component component) {
        if (!isPaperEvent(event) || motdMethod == null || component == null) {
            return false;
        }
        try {
            motdMethod.invoke(event, component);
            return true;
        } catch (Exception e) {
            warnMotd("Failed to apply component MOTD: " + e.getMessage());
            return false;
        }
    }

    public boolean applyHidePlayers(ServerListPingEvent event, boolean hide) {
        if (!isPaperEvent(event) || hidePlayersMethod == null) {
            return false;
        }
        try {
            hidePlayersMethod.invoke(event, hide);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean applyDisableHover(ServerListPingEvent event) {
        if (!isPaperEvent(event) || setPlayerSampleMethod == null) {
            return false;
        }
        try {
            setPlayerSampleMethod.invoke(event, List.of());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Method findPlayerSampleMethod(Class<?> paperClass) {
        for (Method method : paperClass.getMethods()) {
            if (!method.getName().equals("setPlayerSample")) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && java.util.Collection.class.isAssignableFrom(params[0])) {
                return method;
            }
        }
        return null;
    }

    private void warnMotd(String message) {
        if (logger == null) {
            return;
        }
        if (warnedMotd.compareAndSet(false, true)) {
            logger.warning(message);
        }
    }
}
