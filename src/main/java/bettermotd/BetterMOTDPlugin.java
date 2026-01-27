package bettermotd;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class BetterMOTDPlugin extends JavaPlugin {

    private MotdService motdService;
    private WhitelistGate whitelistGate;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ActiveProfileStore profileStore = new ActiveProfileStore(this);
        this.motdService = new MotdService(this, profileStore);
        MotdService.ReloadResult result = this.motdService.reload();
        this.whitelistGate = new WhitelistGate(this);
        this.whitelistGate.applyConfig(motdService.getWhitelistSettings());

        getServer().getPluginManager().registerEvents(
                new ServerPingListener(motdService),
                this);
        getServer().getPluginManager().registerEvents(whitelistGate, this);

        PluginCommand command = getCommand("bettermotd");
        if (command != null) {
            CommandHandler handler = new CommandHandler(this, motdService, whitelistGate);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("Command 'bettermotd' is not registered in plugin.yml.");
        }

        getLogger().info(
                "BetterMOTD enabled. Reload success: " + result.success() + ", warnings: " + result.warnings());
    }

    @Override
    public void onDisable() {
        if (motdService != null) {
            motdService.shutdown();
        }
        if (whitelistGate != null) {
            whitelistGate.shutdown();
        }
    }

    public MotdService.ReloadResult reloadAll() {
        reloadConfig();
        MotdService.ReloadResult result = motdService.reload();
        whitelistGate.applyConfig(motdService.getWhitelistSettings());
        return result;
    }
}
