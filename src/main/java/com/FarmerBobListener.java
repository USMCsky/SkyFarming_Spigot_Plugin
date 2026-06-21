package com;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class FarmerBobListener implements Listener {
    private final FarmerBobService farmerBobService;

    public FarmerBobListener(FarmerBobService farmerBobService) {
        this.farmerBobService = farmerBobService;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBobInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!farmerBobService.isManagedBobEntity(clicked)) {
            return;
        }

        event.setCancelled(true);
        farmerBobService.handleInteraction(event.getPlayer(), clicked);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onChestLinkInteract(PlayerInteractEvent event) {
        if (!farmerBobService.isAwaitingChestLink(event.getPlayer())) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }

        if (farmerBobService.handleChestLinkClick(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBobDamage(EntityDamageEvent event) {
        if (farmerBobService.isManagedBobEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBobTransform(EntityTransformEvent event) {
        if (farmerBobService.isBobVillager(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!farmerBobService.isBobMenu(event.getView().getTopInventory())) {
            return;
        }

        event.setCancelled(true);
        farmerBobService.handleMenuClick(event);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (farmerBobService.isBobMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onLinkedChestBreak(BlockBreakEvent event) {
        farmerBobService.handleLinkedChestBreak(event.getBlock());
    }
}
