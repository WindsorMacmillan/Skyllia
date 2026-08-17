package fr.euphyllia.skyllia.listeners.permissions.block;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import static fr.euphyllia.skyllia.api.commands.SubCommandInterface.log;

public class BlockPlacePermissions implements PermissionModule {

    private static final int MAIN_CITY_MIN_X = 0;
    private static final int MAIN_CITY_MAX_X = 511;
    private static final int MAIN_CITY_MIN_Y = -63;
    private static final int MAIN_CITY_MAX_Y = 319;
    private static final int MAIN_CITY_MIN_Z = 0;
    private static final int MAIN_CITY_MAX_Z = 511;
    private static final String FIREWORK_LORE_KEYWORD = "烟花";

    private PermissionId BLOCK_PLACE;

    @EventHandler(ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final Block placed = event.getBlockPlaced();
        final World world = placed.getWorld();

        final int bx = placed.getX();
        final int by = placed.getY();
        final int bz = placed.getZ();
        final Player player = event.getPlayer();

        if (!SkylliaAPI.isWorldSkyblock(world) || player.isOp()) return;

        // 主城范围内允许放置自定义烟花头颅（lore 含"烟花"）
        //log.info("检测到放置"+isWithinMainCity(bx, by, bz) + isFireworkHead(event.getItemInHand()));
        if (isWithinMainCity(bx, by, bz) && isFireworkHead(event.getItemInHand())) {
            return;
        }

        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) {
            //log.warn("玩家{}在{}位置岛屿无效，无法进行{}", player.getName(), player.getLocation(), event.getEventName());
            event.setCancelled(true);
            return;
        }

        final boolean hasBypass = PlayerUtils.hasPermission(player, "skyllia.player.place.bypass");
        final boolean hasPermission = hasBypass || SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, BLOCK_PLACE, null, ConfigLoader.general.getDebugSettings().permission());
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

    private boolean isFireworkHead(ItemStack item) {
        //log.info("检查头颅物品");
        Material type = item.getType();
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return false;
        for (Component loreLine : meta.lore()) {
            //log.info("物品lore:"+PlainTextComponentSerializer.plainText().serialize(loreLine));
            if (PlainTextComponentSerializer.plainText().serialize(loreLine).contains(FIREWORK_LORE_KEYWORD)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.BLOCK_PLACE = registry.register(new PermissionNode(
                new NamespacedKey(owner, "block.place"),
                "island.permission.block_place.name",
                "island.permission.block_place.description"
        ));
    }
}
