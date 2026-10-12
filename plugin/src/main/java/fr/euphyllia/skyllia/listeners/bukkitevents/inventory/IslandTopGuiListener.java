package fr.euphyllia.skyllia.listeners.bukkitevents.inventory;

import fr.euphyllia.skyllia.gui.IslandTopGui;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class IslandTopGuiListener implements Listener {

    private static final String LEGACY_HOLDER_CLASS =
            "fr.euphyllia.skyllia.gui.IslandTopGui$Holder";

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (isTopInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isTopInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    private boolean isTopInventory(Inventory inventory) {
        if (IslandTopGui.isIslandTopInventory(inventory)) {
            return true;
        }
        InventoryHolder holder = inventory.getHolder(false);
        return holder != null && holder.getClass().getName().equals(LEGACY_HOLDER_CLASS);
    }
}
