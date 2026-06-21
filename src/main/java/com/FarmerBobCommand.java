package com;

import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class FarmerBobCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("spawn", "move", "respawn", "remove", "balance");
    private final FarmerBobService farmerBobService;

    public FarmerBobCommand(FarmerBobService farmerBobService) {
        this.farmerBobService = farmerBobService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use Farmer Bob commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> farmerBobService.spawnBob(player, player.getLocation());
            case "move" -> farmerBobService.moveBob(player, player.getLocation());
            case "respawn" -> farmerBobService.respawnBob(player);
            case "remove" -> farmerBobService.removeBob(player);
            case "balance" -> farmerBobService.sendBalance(player);
            default -> sendHelp(player);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        String partial = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
            .filter(option -> option.startsWith(partial))
            .toList();
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "Farmer Bob commands:");
        player.sendMessage(ChatColor.YELLOW + "/farmerbob spawn " + ChatColor.GRAY + "- place Bob at your current location");
        player.sendMessage(ChatColor.YELLOW + "/farmerbob move " + ChatColor.GRAY + "- move Bob to your current location");
        player.sendMessage(ChatColor.YELLOW + "/farmerbob respawn " + ChatColor.GRAY + "- rebuild Bob at his saved location");
        player.sendMessage(ChatColor.YELLOW + "/farmerbob remove " + ChatColor.GRAY + "- remove Bob and clear his saved spot");
        player.sendMessage(ChatColor.YELLOW + "/farmerbob balance " + ChatColor.GRAY + "- show your Farm Coins");
        farmerBobService.sendBalance(player);
    }
}
