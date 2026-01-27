package bettermotd;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class WhitelistGate implements Listener {

    private final Plugin plugin;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.disabled());

    private GateSettings settings = GateSettings.disabled();
    private BukkitTask refreshTask;

    public WhitelistGate(Plugin plugin) {
        this.plugin = plugin;
    }

    public void applyConfig(ConfigModel.WhitelistSettings whitelist) {
        if (whitelist == null) {
            this.settings = GateSettings.disabled();
        } else {
            int refreshSeconds = Math.max(1, whitelist.gateRefreshSeconds());
            String kickMessage = whitelist.gateKickMessage();
            if (kickMessage == null || kickMessage.isBlank()) {
                kickMessage = "You are not whitelisted.";
            }
            this.settings = new GateSettings(whitelist.gateEnabled(), refreshSeconds, kickMessage);
        }

        refreshSnapshot();
        rescheduleTask();
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        snapshot.set(Snapshot.disabled());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event == null || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Snapshot current = snapshot.get();
        if (!current.enforced()) {
            return;
        }
        UUID uuid = event.getUniqueId();
        if (uuid != null && current.allowedUuids().contains(uuid)) {
            return;
        }
        String name = event.getName();
        if (name != null && current.allowedNamesLower().contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, current.kickMessage());
    }

    private void rescheduleTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (!settings.enabled()) {
            return;
        }
        long intervalTicks = Math.max(1L, settings.refreshSeconds()) * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshSnapshot, intervalTicks, intervalTicks);
    }

    private void refreshSnapshot() {
        Snapshot updated = buildSnapshot(settings);
        snapshot.set(updated);
    }

    private Snapshot buildSnapshot(GateSettings gateSettings) {
        boolean enforced = gateSettings.enabled() && Bukkit.hasWhitelist();
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        if (enforced) {
            for (OfflinePlayer player : Bukkit.getWhitelistedPlayers()) {
                if (player == null) {
                    continue;
                }
                UUID uuid = player.getUniqueId();
                if (uuid != null) {
                    uuids.add(uuid);
                }
                String name = player.getName();
                if (name != null && !name.isBlank()) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new Snapshot(Set.copyOf(uuids), Set.copyOf(names), enforced, gateSettings.kickMessage());
    }

    private record GateSettings(boolean enabled, int refreshSeconds, String kickMessage) {
        private static GateSettings disabled() {
            return new GateSettings(false, 5, "You are not whitelisted.");
        }
    }

    private record Snapshot(Set<UUID> allowedUuids, Set<String> allowedNamesLower, boolean enforced,
                            String kickMessage) {
        private static Snapshot disabled() {
            return new Snapshot(Set.of(), Set.of(), false, "You are not whitelisted.");
        }
    }
}
