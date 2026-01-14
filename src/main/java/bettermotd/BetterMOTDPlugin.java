package bettermotd;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

public final class BetterMOTDPlugin extends JavaPlugin {

    private MotdService motdService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.motdService = new MotdService(this);
        MotdService.ReloadResult result = this.motdService.reload();

        getServer().getPluginManager().registerEvents(
                new ServerPingListener(motdService),
                this);

        getLogger().info(
                "BetterMOTD enabled. Presets loaded: " + result.presetsLoaded() + ", warnings: " + result.warnings());
    }

    @Override
    public void onDisable() {
        if (motdService != null) {
            motdService.shutdown();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("bettermotd")) {
            return false;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "reload" -> handleReload(sender);
            case "test" -> handleTest(sender);
            case "stats" -> handleStats(sender);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!hasPermission(sender, "bettermotd.reload")) {
            sender.sendMessage("You do not have permission to reload BetterMOTD.");
            return true;
        }
        reloadConfig();
        MotdService.ReloadResult result = motdService.reload();
        sender.sendMessage(
                "BetterMOTD reloaded. Presets loaded: " + result.presetsLoaded() + ", warnings: " + result.warnings());
        return true;
    }

    private boolean handleTest(CommandSender sender) {
        if (!hasPermission(sender, "bettermotd.test")) {
            sender.sendMessage("You do not have permission to test BetterMOTD.");
            return true;
        }

        InetAddress address = null;
        if (sender instanceof Player player && player.getAddress() != null) {
            address = player.getAddress().getAddress();
        }

        MotdService.TestResult result = motdService.test(address);
        if (address == null) {
            sender.sendMessage("Preset: N/A");
        } else {
            sender.sendMessage("Preset: " + result.presetId());
        }
        sender.sendMessage(result.motdPlain());
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!hasPermission(sender, "bettermotd.stats")) {
            sender.sendMessage("You do not have permission to view BetterMOTD stats.");
            return true;
        }

        List<MotdService.StatEntry> stats = motdService.getStats();
        if (stats.isEmpty()) {
            sender.sendMessage("No stats recorded yet.");
            return true;
        }

        sender.sendMessage("BetterMOTD preset picks:");
        for (MotdService.StatEntry entry : stats) {
            sender.sendMessage("- " + entry.presetId() + ": " + entry.count());
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /bettermotd <reload|test|stats>");
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        return sender.hasPermission("bettermotd.admin") || sender.hasPermission(permission);
    }
}
