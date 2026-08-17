package fr.euphyllia.skyllia.listeners.bukkitevents.blocks;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * 当新手玩家（在线时长 ≤ 5 小时）把冰放在没有固体方块下方的空中时，
 * 发送 ActionBar 提醒：挖掉冰块需要下方有方块才能变成水。
 */
public class IcePlaceWarningListener implements Listener {

    private static final String PLAYED_PLACEHOLDER = "%statistic_minutes_played%";
    private static final long MAX_PLAYED_MINUTES = 300L; // 5 小时 = 300 分钟
    private static final String WARNING_MESSAGE = "&c小心，&b冰下面要有方块才能挖成水！";

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        final Block placed = event.getBlockPlaced();
        if (placed.getType() != Material.ICE) return;
        if (!SkylliaAPI.isWorldSkyblock(placed.getWorld())) return;

        // 冰下方必须紧贴固体方块，否则挖掉不会变成水
        final Block below = placed.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid()) return;

        final Player player = event.getPlayer();
        if (player.isOp()) return;

        // 仅提示在线时长不超过 5 小时的新手玩家
        long playedMinutes = parsePlayedMinutes(player);
        if (playedMinutes > MAX_PLAYED_MINUTES) return;

        player.sendActionBar(Component.text(ChatColor.translateAlternateColorCodes('&', WARNING_MESSAGE)));
    }

    private long parsePlayedMinutes(Player player) {
        try {
            String raw = PlaceholderAPI.setPlaceholders(player, PLAYED_PLACEHOLDER);
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            // 无法解析时不做提示
            return Long.MAX_VALUE;
        }
    }
}
