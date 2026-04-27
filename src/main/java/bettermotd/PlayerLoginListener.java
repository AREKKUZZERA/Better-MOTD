package bettermotd;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public final class PlayerLoginListener implements Listener {

    private final MotdService service;

    public PlayerLoginListener(MotdService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onLogin(PlayerLoginEvent event) {
        if (!service.maintenanceEnabled()) {
            return;
        }
        String permission = service.maintenanceBypassPermission();
        if (permission != null && !permission.isBlank() && event.getPlayer().hasPermission(permission)) {
            return;
        }
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, service.maintenanceKickMessage());
    }
}
