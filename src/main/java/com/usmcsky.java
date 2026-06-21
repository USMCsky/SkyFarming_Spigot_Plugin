package com;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class usmcsky extends JavaPlugin {
    private FarmerBobService farmerBobService;

    @Override
    public void onEnable() {
        farmerBobService = new FarmerBobService(this);
        farmerBobService.load();

        getServer().getPluginManager().registerEvents(new FarmingListener(farmerBobService), this);
        getServer().getPluginManager().registerEvents(new FarmerBobListener(farmerBobService), this);

        PluginCommand farmerBobCommand = getCommand("farmerbob");
        if (farmerBobCommand == null) {
            throw new IllegalStateException("farmerbob command is not defined in plugin.yml");
        }

        FarmerBobCommand commandHandler = new FarmerBobCommand(farmerBobService);
        farmerBobCommand.setExecutor(commandHandler);
        farmerBobCommand.setTabCompleter(commandHandler);
    }

    @Override
    public void onDisable() {
        if (farmerBobService != null) {
            farmerBobService.shutdown();
        }
    }
}
