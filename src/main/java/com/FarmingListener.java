package com;

import java.util.Collection;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class FarmingListener implements Listener {
    private final FarmerBobService farmerBobService;

    public FarmingListener(FarmerBobService farmerBobService) {
        this.farmerBobService = farmerBobService;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCropRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        Material replantItem = FarmerBobService.getReplantItem(block.getType());
        if (replantItem == null || !(block.getBlockData() instanceof Ageable ageable)) {
            return;
        }

        if (ageable.getAge() != ageable.getMaximumAge()) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();
        Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand(), player);

        boolean replanted = false;
        for (ItemStack drop : drops) {
            ItemStack toGive = drop.clone();
            if (!replanted && toGive.getType() == replantItem && toGive.getAmount() > 0) {
                toGive.setAmount(toGive.getAmount() - 1);
                replanted = true;
            }

            if (toGive.getAmount() > 0) {
                player.getInventory().addItem(toGive).values().forEach(leftover ->
                    block.getWorld().dropItemNaturally(block.getLocation(), leftover)
                );
            }
        }

        ageable.setAge(0);
        block.setBlockData(ageable);
        farmerBobService.recordHarvest(player, block.getType(), 1);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFarmlandTrample(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        Material currentType = block.getType();

        if (currentType == Material.FARMLAND && event.getTo() == Material.DIRT) {
            event.setCancelled(true);
            return;
        }

        if (FarmerBobService.isSupportedCrop(currentType) && event.getTo() == Material.AIR) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCropBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Ageable ageable)) {
            return;
        }

        if (!FarmerBobService.isSupportedCrop(block.getType()) || ageable.getAge() != ageable.getMaximumAge()) {
            return;
        }

        farmerBobService.recordHarvest(event.getPlayer(), block.getType(), 1);
    }
}
