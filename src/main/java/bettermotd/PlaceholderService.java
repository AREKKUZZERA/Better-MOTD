package bettermotd;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public final class PlaceholderService {

    private final Logger logger;
    private final AtomicBoolean warnedUnavailable = new AtomicBoolean();
    private volatile Method setPlaceholders;

    public PlaceholderService(Logger logger) {
        this.logger = logger;
    }

    public String apply(String input, boolean enabled) {
        if (!enabled || input == null || input.indexOf('%') < 0) {
            return input;
        }
        Method method = resolveMethod();
        if (method == null) {
            return input;
        }
        try {
            Object result = method.invoke(null, (OfflinePlayer) null, input);
            return result instanceof String value ? value : input;
        } catch (Exception e) {
            warnOnce("PlaceholderAPI failed: " + e.getMessage());
            return input;
        }
    }

    private Method resolveMethod() {
        Method cached = setPlaceholders;
        if (cached != null) {
            return cached;
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            warnOnce("PlaceholderAPI support is enabled, but PlaceholderAPI is not installed.");
            return null;
        }
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method method = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            setPlaceholders = method;
            return method;
        } catch (Exception e) {
            warnOnce("PlaceholderAPI support is enabled, but API lookup failed: " + e.getMessage());
            return null;
        }
    }

    private void warnOnce(String message) {
        if (logger != null && warnedUnavailable.compareAndSet(false, true)) {
            logger.warning(message);
        }
    }
}
