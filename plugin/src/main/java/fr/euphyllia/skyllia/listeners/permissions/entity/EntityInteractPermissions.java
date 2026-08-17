package fr.euphyllia.skyllia.listeners.permissions.entity;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.Plugin;

import static fr.euphyllia.skyllia.api.commands.SubCommandInterface.log;

public class EntityInteractPermissions implements PermissionModule {

    private static final int MAIN_CITY_MIN_X = 0;
    private static final int MAIN_CITY_MAX_X = 511;
    private static final int MAIN_CITY_MIN_Y = 63;
    private static final int MAIN_CITY_MAX_Y = 319;
    private static final int MAIN_CITY_MIN_Z = 0;
    private static final int MAIN_CITY_MAX_Z = 511;

    private PermissionId ENTITY_INTERACT;

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final Player player = event.getPlayer();
        final Entity target = event.getRightClicked();

        // 村民/流浪商人的交互已拆分至 EntityTradePermissions(entity.trade)
        if (target.getType() == EntityType.VILLAGER || target.getType() == EntityType.WANDERING_TRADER) return;

        final World world = target.getWorld();

        final int bx = Location.locToBlock(target.getX());
        final int by = Location.locToBlock(target.getY());
        final int bz = Location.locToBlock(target.getZ());
        if (!SkylliaAPI.isWorldSkyblock(world) || player.isOp()) return;

        // 主城范围内允许手持打火石右键盔甲架（点燃自定义烟花）
        if (isWithinMainCity(bx, by, bz)
                && target.getType() == EntityType.ARMOR_STAND
                && player.getInventory().getItemInMainHand().getType() == Material.FLINT_AND_STEEL) {
            return;
        }

        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) return;

        final boolean hasBypass = PlayerUtils.hasPermission(player, "skyllia.player.entity.interact.bypass");
        final boolean hasPermission = hasBypass || SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, ENTITY_INTERACT, null, ConfigLoader.general.getDebugSettings().permission());
        if (!hasPermission) {
            event.setCancelled(true);
            return;
        }
        if (!hasBypass) {
            ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, event);
        }
    }

    private boolean isWithinMainCity(int x, int y, int z) {
        return x >= MAIN_CITY_MIN_X && x <= MAIN_CITY_MAX_X
                && y >= MAIN_CITY_MIN_Y && y <= MAIN_CITY_MAX_Y
                && z >= MAIN_CITY_MIN_Z && z <= MAIN_CITY_MAX_Z;
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.ENTITY_INTERACT = registry.register(new PermissionNode(
                new NamespacedKey(owner, "entity.interact"),
                "island.permission.entity_interact.name",
                "island.permission.entity_interact.description"
        ));
    }
}
