package bettermotd;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

public final class ServerPingListener implements Listener {

    private final MotdService service;

    public ServerPingListener(MotdService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPing(ServerListPingEvent event) {
        service.apply(event);
    }
}
