package fr.euphyllia.skyllia.listeners.bukkitevents.blocks;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.service.TrustService;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.ChiseledBookshelf;
import org.bukkit.block.data.type.Shelf;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ChiseledBookshelfInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ShelfInventory;

public class ShelfAccessListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        final Block clicked = event.getClickedBlock();
        if (clicked == null || !isShelfBlock(clicked)) return;

        final Player player = event.getPlayer();
        final Location location = clicked.getLocation();
        if (canUseShelf(player, location)) return;

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        final Location location = shelfLocation(event.getView().getTopInventory());
        if (location == null || canUseShelf(player, location)) return;

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        final Location location = shelfLocation(event.getView().getTopInventory());
        if (location == null || canUseShelf(player, location)) return;

        event.setCancelled(true);
    }

    private boolean canUseShelf(final Player player, final Location location) {
        final World world = location.getWorld();
        if (world == null || !SkylliaAPI.isWorldSkyblock(world)) return true;

        final Island island = ListenersUtils.islandAtBlock(world, location.getBlockX(), location.getBlockZ());
        if (island == null) return false;

        final Players owner = island.getOwner();
        if (owner != null && owner.getMojangId().equals(player.getUniqueId())) return true;

        final Players member = island.getMember(player.getUniqueId());
        if (member != null && member.getRoleType() != RoleType.BAN) return true;

        final TrustService trustService = SkylliaAPI.getTrustService();
        return trustService != null && trustService.isTrusted(island.getId(), player.getUniqueId());
    }

    private boolean isShelfBlock(final Block block) {
        final BlockData blockData = block.getBlockData();
        return blockData instanceof Shelf || blockData instanceof ChiseledBookshelf;
    }

    private Location shelfLocation(final Inventory inventory) {
        if (!(inventory instanceof ShelfInventory) && !(inventory instanceof ChiseledBookshelfInventory)) return null;

        final InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockState blockState) return blockState.getLocation();
        return null;
    }
}
