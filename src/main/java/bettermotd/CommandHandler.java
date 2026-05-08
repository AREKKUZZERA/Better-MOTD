package bettermotd;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("help", "reload", "profile", "profiles", "preview", "diagnostics", "import");

    private final JavaPlugin plugin;
    private final MotdService motdService;

    public CommandHandler(JavaPlugin plugin, MotdService motdService) {
        this.plugin = plugin;
        this.motdService = motdService;
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
                case "help", "?" -> {
                    sendUsage(sender);
                    yield true;
                }
                case "reload", "r" -> handleReload(sender);
                case "profile", "profiles", "setprofile", "p" -> handleProfile(sender, args);
                case "preview", "show" -> handlePreview(sender, args);
                case "diagnostics", "diag", "d" -> handleDiagnostics(sender);
                case "import" -> handleImport(sender, args);
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
        if (sender instanceof Player player) {
            InetSocketAddress socketAddress = player.getAddress();
            if (socketAddress != null) {
                address = socketAddress.getAddress();
            }
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
        sender.sendMessage("- player counts: " + online + "/" + max + " (base "
                + counts.baseOnline() + "/" + counts.baseMax() + ", fake +"
                + counts.fakeDelta() + ")");
        if (counts.hidePlayerCount()) {
            sender.sendMessage("- player counts are hidden (??? in server list)");
        }
        if (counts.disableHover()) {
            sender.sendMessage("- player hover list disabled (Paper only)");
        }

        sender.sendMessage("- format: configured=" + result.configuredFormat() + ", detected=" + result.usedFormat());
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

    private boolean handleDiagnostics(CommandSender sender) {
        MotdService.Diagnostics diagnostics = motdService.diagnostics();
        sender.sendMessage("BetterMOTD diagnostics:");
        sender.sendMessage("- active profile: " + diagnostics.activeProfile());
        sender.sendMessage("- sticky entries by profile: " + diagnostics.stickyEntriesByProfile());
        sender.sendMessage("- rotate counters: " + diagnostics.rotateCounterProfiles());
        sender.sendMessage("- preset cache size: " + diagnostics.presetCacheSize());
        sender.sendMessage("- formatter warnings cached: " + diagnostics.formatWarnings());
        return true;
    }

    private boolean handleImport(CommandSender sender, String[] args) {
        if (args.length < 2 || !"minimotd".equalsIgnoreCase(args[1])) {
            sender.sendMessage("Usage: /bettermotd import minimotd [path]");
            return true;
        }
        File source = args.length >= 3
                ? new File(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)))
                : new File(plugin.getDataFolder().getParentFile(), "MiniMOTD/main.conf");
        MiniMotdImporter.ImportResult result = MiniMotdImporter.importInto(source, plugin.getConfig());
        if (!result.success()) {
            sender.sendMessage(result.message());
            return true;
        }
        plugin.saveConfig();
        reloadAll();
        sender.sendMessage(result.message() + " Presets: " + result.presets() + ".");
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
        sender.sendMessage("BetterMOTD commands:");
        sender.sendMessage("- /bettermotd reload");
        sender.sendMessage("- /bettermotd profile [profileId]");
        sender.sendMessage("- /bettermotd preview <profileId|presetId>");
        sender.sendMessage("- /bettermotd diagnostics");
        sender.sendMessage("- /bettermotd import minimotd [path]");
    }

    private MotdService.ReloadResult reloadAll() {
        if (plugin instanceof BetterMOTDPlugin betterPlugin) {
            return betterPlugin.reloadAll();
        }
        plugin.reloadConfig();
        return motdService.reload();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("bettermotd")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterStartsWith(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2
                && (isProfileCommand(args[0])
                        || "preview".equalsIgnoreCase(args[0])
                        || "show".equalsIgnoreCase(args[0]))) {
            List<String> entries = new ArrayList<>(motdService.getProfileIds());
            if ("preview".equalsIgnoreCase(args[0]) || "show".equalsIgnoreCase(args[0])) {
                entries.addAll(motdService.getPresetIds(motdService.getActiveProfileId()));
            }
            return filterStartsWith(entries, args[1]);
        }
        if (args.length == 2 && "import".equalsIgnoreCase(args[0])) {
            return filterStartsWith(List.of("minimotd"), args[1]);
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

    private boolean isProfileCommand(String value) {
        return "profile".equalsIgnoreCase(value)
                || "profiles".equalsIgnoreCase(value)
                || "setprofile".equalsIgnoreCase(value)
                || "p".equalsIgnoreCase(value);
    }
}
