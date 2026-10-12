package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class HomeSubCommand implements SubCommandInterface {

    private final Logger logger = LogManager.getLogger(HomeSubCommand.class);

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }

        try {
            Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
            if (island == null) {
                ConfigLoader.language.sendMessage(player, "island.player.no-island");
                return;
            }

            WarpIsland warpIsland = island.getWarpByName("home");
            World islandWorld = WorldUtils.getWorldConfigs().getFirst().getWorld();

            Location loc;
            if (island.hasCustomSpawn()) {
                loc = island.getSpawnLocation(islandWorld);
            } else if (warpIsland != null && warpIsland.location() != null) {
                loc = warpIsland.location().clone();
            } else {
                loc = island.getSpawnLocation(islandWorld);
            }
            // 本服务端项目自定义修改：家坐标现按玩家浮点坐标原样存储，
            // 传送时不再叠加 +0.5 方块居中偏移，使落点与设置点完全一致
            // （避免在不完整方块上设置时被偏移卡进方块）。
            player.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN).thenRun(() -> {
                player.setVelocity(new Vector(0, 0, 0));
                player.setFallDistance(0);

                ConfigLoader.language.sendMessage(player, "island.home.success");
            });
        } catch (Exception exception) {
            logger.log(Level.FATAL, exception.getMessage(), exception);
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
