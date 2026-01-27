package bettermotd;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class BetterMOTDPlugin extends JavaPlugin {

    private MotdService motdService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ActiveProfileStore profileStore = new ActiveProfileStore(this);
        this.motdService = new MotdService(this, profileStore);
        MotdService.ReloadResult result = this.motdService.reload();

        getServer().getPluginManager().registerEvents(
                new ServerPingListener(motdService),
                this);

        PluginCommand command = getCommand("bettermotd");
        if (command != null) {
            CommandHandler handler = new CommandHandler(this, motdService);
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
    }

    public MotdService.ReloadResult reloadAll() {
        reloadConfig();
        return motdService.reload();
    }
}
