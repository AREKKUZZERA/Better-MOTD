package bettermotd;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "profile", "preview");

    private final JavaPlugin plugin;
    private final MotdService motdService;
    private final WhitelistGate whitelistGate;

    public CommandHandler(JavaPlugin plugin, MotdService motdService, WhitelistGate whitelistGate) {
        this.plugin = plugin;
        this.motdService = motdService;
        this.whitelistGate = whitelistGate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("bettermotd")) {
            return false;
        }

        if (!sender.hasPermission("bettermotd.admin")) {
            sender.sendMessage("You do not have permission to use BetterMOTD commands.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "reload" -> handleReload(sender);
                case "profile" -> handleProfile(sender, args);
                case "preview" -> handlePreview(sender, args);
                default -> {
                    sendUsage(sender);
                    yield true;
                }
            };
        } catch (Exception e) {
            plugin.getLogger().warning("Command failed: " + e.getMessage());
            sender.sendMessage("BetterMOTD command failed. Check server logs for details.");
            return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        MotdService.ReloadResult result = reloadAll();
        if (result.success()) {
            sender.sendMessage("BetterMOTD reloaded successfully (warnings: " + result.warnings() + ").");
        } else {
            sender.sendMessage("BetterMOTD reload failed. Check server logs.");
        }
        return true;
    }

    private boolean handleProfile(CommandSender sender, String[] args) {
        if (args.length < 2) {
            listProfiles(sender);
            return true;
        }
        String profileId = args[1];
        boolean ok = motdService.setActiveProfile(profileId);
        if (ok) {
            sender.sendMessage("Active BetterMOTD profile set to '" + profileId + "'.");
        } else {
            sender.sendMessage("Unknown profile '" + profileId + "'. Available profiles:");
            listProfiles(sender);
        }
        return true;
    }

    private boolean handlePreview(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /bettermotd preview <profileId|presetId>");
            return true;
        }
        String id = args[1];
        InetAddress address = null;
        if (sender instanceof Player player && player.getAddress() != null) {
            address = player.getAddress().getAddress();
        }

        MotdService.PreviewResult result = motdService.preview(id, address);
        if (result == null) {
            sender.sendMessage("No profile or preset found for '" + id + "'.");
            listProfiles(sender);
            return true;
        }

        sender.sendMessage("BetterMOTD preview:");
        sender.sendMessage("- profile: " + result.profileId());
        sender.sendMessage("- preset: " + result.presetId() + " (" + result.reason() + ")");
        sender.sendMessage("- icon: " + result.iconPath());

        PlayerCountService.PlayerCountResult counts = result.playerCounts();
        String online = counts.hidePlayerCount() ? "???" : String.valueOf(counts.displayOnline());
        String max = counts.hidePlayerCount() ? "???" : String.valueOf(counts.displayMax());
        sender.sendMessage("- player counts: " + online + "/" + max +
                " (base " + counts.baseOnline() + "/" + counts.baseMax() +
                ", fake +" + counts.fakeDelta() + ")");
        if (counts.hidePlayerCount()) {
            sender.sendMessage("- player counts are hidden (??? in server list)");
        }
        if (counts.disableHover()) {
            sender.sendMessage("- player hover list disabled (Paper only)");
        }

        sender.sendMessage("- format: configured=" + result.configuredFormat()
                + ", detected=" + result.usedFormat());
        sender.sendMessage("- motd:");
        for (String line : result.motdLines()) {
            sender.sendMessage("  " + line);
        }
        if (!result.legacyLines().isEmpty()) {
            sender.sendMessage("- legacy preview (Spigot/Bukkit):");
            for (String line : result.legacyLines()) {
                sender.sendMessage("  " + line);
            }
        }

        return true;
    }

    private void listProfiles(CommandSender sender) {
        Set<String> profiles = motdService.getProfileIds();
        if (profiles.isEmpty()) {
            sender.sendMessage("No profiles available.");
            return;
        }
        sender.sendMessage("Profiles: " + String.join(", ", profiles));
        sender.sendMessage("Active profile: " + motdService.getActiveProfileId());
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /bettermotd <reload|profile|preview>");
    }

    private MotdService.ReloadResult reloadAll() {
        if (plugin instanceof BetterMOTDPlugin betterPlugin) {
            return betterPlugin.reloadAll();
        }
        plugin.reloadConfig();
        MotdService.ReloadResult result = motdService.reload();
        if (whitelistGate != null) {
            whitelistGate.applyConfig(motdService.getWhitelistSettings());
        }
        return result;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("bettermotd")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterStartsWith(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && ("profile".equalsIgnoreCase(args[0]) || "preview".equalsIgnoreCase(args[0]))) {
            List<String> entries = new ArrayList<>(motdService.getProfileIds());
            if ("preview".equalsIgnoreCase(args[0])) {
                entries.addAll(motdService.getPresetIds(motdService.getActiveProfileId()));
            }
            return filterStartsWith(entries, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterStartsWith(List<String> options, String token) {
        String lower = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                filtered.add(option);
            }
        }
        Collections.sort(filtered);
        return filtered;
    }
}
