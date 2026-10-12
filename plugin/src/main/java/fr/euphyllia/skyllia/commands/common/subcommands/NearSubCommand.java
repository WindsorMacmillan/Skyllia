package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 本服务端项目自定义修改：{@code /is near} 显示玩家所在岛屿四个方向邻接岛屿的岛主。
 *
 * <p>一个岛屿恰好占据一个 MC 区域，因此四个方向即相邻的区域坐标。北为 -Z、南为 +Z、
 * 东为 +X、西为 -X；某方向无岛屿时显示为无人区。</p>
 */
public class NearSubCommand implements SubCommandInterface {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    // 本服务端项目自定义修改：展示顺序为 北、东、西、南
    private static final String[] DIRECTIONS = {"北", "东", "西", "南"};
    private static final int[] OFFSET_X = {0, 1, -1, 0};
    private static final int[] OFFSET_Z = {-1, 0, 0, 1};

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }

        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(player, "island.player.no-island");
            return;
        }

        RegionCoordinate region = island.getRegionCoordinate();
        for (int index = 0; index < DIRECTIONS.length; index++) {
            RegionCoordinate neighbor = new RegionCoordinate(
                    region.x() + OFFSET_X[index], region.z() + OFFSET_Z[index]);
            Island neighborIsland = SkylliaAPI.getIslandByRegion(neighbor);

            String message;
            if (neighborIsland == null) {
                message = "&6无人区";
            } else {
                Players owner = neighborIsland.getOwner();
                String ownerName = (owner == null || owner.getLastKnowName() == null)
                        ? "未知" : owner.getLastKnowName();
                message = "&7" + DIRECTIONS[index] + "：&e" + ownerName + "&a的岛屿";
            }
            player.sendMessage(LEGACY.deserialize(message));
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender,
                                                @NotNull String[] args) {
        return List.of();
    }
}
