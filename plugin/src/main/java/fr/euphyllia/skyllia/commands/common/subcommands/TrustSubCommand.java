package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TrustSubCommand implements SubCommandInterface {

    // 本服务端项目自定义修改：/is trust 的用法与信任列表以 & 颜色代码输出
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);

    private final Logger logger = LogManager.getLogger(TrustSubCommand.class);

    private final PermissionId ISLAND_MANAGE_TRUST_PERMISSION;

    public TrustSubCommand() {
        this.ISLAND_MANAGE_TRUST_PERMISSION = SkylliaAPI.getPermissionRegistry().register(
                new PermissionNode(
                        new NamespacedKey(Skyllia.getInstance(), "command.island.manage_trust"),
                        "island.permission.command.manage_trust.name",
                        "island.permission.command.manage_trust.description"
                )
        );
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }

        // 本服务端项目自定义修改：无参数时先显示用法，再换行显示当前岛屿的信任列表
        if (args.length < 1) {
            showTrustUsage(player);
            return;
        }

        try {
            String targetName = args[0];

            UUID targetId = Bukkit.getPlayerUniqueId(targetName);
            if (targetId == null) {
                ConfigLoader.language.sendMessage(player, "island.player.not-found");
                return;
            }

            Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
            if (island == null) {
                ConfigLoader.language.sendMessage(player, "island.player.no-island");
                return;
            }

            boolean allowed = SkylliaAPI.getPermissionsManager()
                    .hasPermission(player, island, ISLAND_MANAGE_TRUST_PERMISSION, null, ConfigLoader.general.getDebugSettings().permission());

            if (!allowed) {
                ConfigLoader.language.sendMessage(player, "island.player.permission-denied");
                return;
            }

            boolean stored = Skyllia.getInstance().getInterneAPI().getIslandQuery()
                    .getIslandDataQuery().addTrustedPlayer(island.getId(), targetId);
            if (!stored && !Skyllia.getInstance().getInterneAPI().getTrustService()
                    .isTrusted(island.getId(), targetId)) {
                ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
                return;
            }
            boolean added = Skyllia.getInstance().getInterneAPI().getTrustService()
                    .addTrusted(island.getId(), targetId);

            if (added) {
                ConfigLoader.language.sendMessage(player, "island.trust.success", Map.of("%trusted_name%", targetName));
            } else {
                ConfigLoader.language.sendMessage(player, "island.trust.already-trusted", Map.of("%trusted_name%", targetName));
            }

        } catch (Exception e) {
            logger.log(Level.FATAL, e.getMessage(), e);
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        }
    }

    // 本服务端项目自定义修改：/is trust 无参数时展示用法与当前信任列表（含各人离线时长）
    private void showTrustUsage(@NotNull Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(player, "island.player.no-island");
            return;
        }
        // 第一行：命令用法
        player.sendMessage(LEGACY.deserialize("&7用法：&e/is trust <玩家名>"));
        Set<UUID> trusted = Skyllia.getInstance().getInterneAPI().getTrustService().getTrusted(island.getId());
        int count = trusted == null ? 0 : trusted.size();
        player.sendMessage(LEGACY.deserialize("&7当前信任玩家数：&e" + count));
        if (trusted == null || trusted.isEmpty()) {
            return;
        }
        for (UUID trustedId : trusted) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(trustedId);
            String name = offlinePlayer.getName();
            if (name == null || name.isBlank()) {
                name = trustedId.toString();
            }
            player.sendMessage(LEGACY.deserialize(
                    "&7- &e" + name + " &7离线时长：&e" + offlineDuration(offlinePlayer)));
        }
    }

    // 本服务端项目自定义修改：离线时长文本，在线玩家显示“在线”
    private static @NotNull String offlineDuration(@NotNull OfflinePlayer offlinePlayer) {
        if (offlinePlayer.isOnline()) {
            return "在线";
        }
        long lastSeen = offlinePlayer.getLastSeen();
        if (lastSeen <= 0L) {
            return "未知";
        }
        long millis = Math.max(0L, System.currentTimeMillis() - lastSeen);
        long days = millis / MILLIS_PER_DAY;
        long hours = (millis % MILLIS_PER_DAY) / TimeUnit.HOURS.toMillis(1);
        long minutes = (millis % TimeUnit.HOURS.toMillis(1)) / TimeUnit.MINUTES.toMillis(1);
        if (days > 0L) {
            return days + "天" + hours + "小时";
        }
        if (hours > 0L) {
            return hours + "小时" + minutes + "分";
        }
        return minutes + "分钟";
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            var onlinePlayers = Bukkit.getOnlinePlayers();
            return onlinePlayers.stream()
                    .map(CommandSender::getName)
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
