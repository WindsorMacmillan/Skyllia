package fr.euphyllia.skyllia.listeners.bukkitevents.blocks;

import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/**
 * 监听羊吃草（草方块 → 土方块），1 秒后若该位置仍为土方块则恢复为草方块。
 * 防止羊群过度啃食导致空岛草地退化。
 */
public class SheepEatGrassListener implements Listener {

    private final InterneAPI api;

    public SheepEatGrassListener(InterneAPI interneAPI) {
        this.api = interneAPI;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSheepEatGrass(final EntityChangeBlockEvent event) {
        // 只处理羊吃草：羊使草方块变为土方块
        if (event.getEntityType() != EntityType.SHEEP) return;
        if (event.getBlock().getType() != Material.GRASS_BLOCK) return;
        if (event.getTo() != Material.DIRT) return;

        final Location location = event.getBlock().getLocation();

        // 1 秒（20 ticks）后检查并恢复
        Bukkit.getRegionScheduler().runDelayed(
                SkylliaAPI.getPlugin(),
                location.getWorld(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4,
                unused1 -> {
                    // 仅在该位置仍是土方块时恢复
                    if (location.getBlock().getType() == Material.DIRT) {
                        location.getBlock().setType(Material.GRASS_BLOCK, false);
                    }
                    // 若已被玩家改造成其他方块或已自然生长回草，则不干涉
                },
                20L
        );
    }

    public InterneAPI getApi() {
        return api;
    }
}
