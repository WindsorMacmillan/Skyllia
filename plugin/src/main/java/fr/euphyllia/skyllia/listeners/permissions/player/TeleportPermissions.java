package fr.euphyllia.skyllia.listeners.permissions.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import me.earthme.luminol.api.entity.EntityTeleportAsyncEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportPermissions implements PermissionModule {

    private PermissionId TELEPORT;
    private final Set<UUID> redirecting = ConcurrentHashMap.newKeySet();

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.TELEPORT = registry.register(new PermissionNode(
                new NamespacedKey(owner, "player.teleport"),
                "island.permission.player_teleport.name",
                "island.permission.player_teleport.description"
        ));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncTeleport(final EntityTeleportAsyncEvent event) {
        // 只处理玩家
        if (!(event.getEntity() instanceof Player player) || player.isOp()) return;

        // 防止纠正传送导致的递归
        if (redirecting.contains(player.getUniqueId())) return;

        Location to = event.getDestination();
        if (!SkylliaAPI.isWorldSkyblock(to.getWorld())) return;

        final int chunkX = to.getBlockX() >> 4;
        final int chunkZ = to.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) {
            return;
        }

        // 岛主直接放行
        if (island.getOwner() != null && island.getOwner().getMojangId().equals(player.getUniqueId())) {
            return;
        }

        // 被拉黑的玩家立即遣返（无论信任状态）
        Players member = island.getMember(player.getUniqueId());
        if (member != null && member.getRoleType() == RoleType.BAN) {
            ConfigLoader.language.sendMessage(player, "island.visit.banned");
            notifyIslandOfBlockedTeleport(player, island);
            redirectToStart(player);
            return;
        }

        // 检查 player.teleport 权限（内部权限 + Bukkit bypass 节点）
        boolean hasTeleport = SkylliaAPI.getPermissionsManager().hasPermission(
                player, island, TELEPORT,
                "skyllia.player.teleport.bypass",
                ConfigLoader.general.getDebugSettings().permission());
        if (!hasTeleport && !player.isOp()) {
            ConfigLoader.language.sendMessage(player, "island.player.permission-denied");
            notifyIslandOfBlockedTeleport(player, island);
            redirectToStart(player);
            return;
        }

        // 对于私有岛屿，额外检查 command.island.visit.bypass 权限
        PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        PermissionId bypassPid = registry.getIfPresent(new NamespacedKey("skyllia", "command.island.visit.bypass"));
        boolean bypass = player.isOp()
                || (bypassPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                    player, island, bypassPid, null, ConfigLoader.general.getDebugSettings().permission()));
        if (island.isPrivateIsland() && !bypass) {
            ConfigLoader.language.sendMessage(player, "island.visit.island-closed");
            notifyIslandOfBlockedTeleport(player, island);
            redirectToStart(player);
        }
    }

    /**
     * 通知目标空岛的在线岛主和成员：某人尝试传送但被遣返。
     */
    private void notifyIslandOfBlockedTeleport(Player visitor, Island island) {
        String message;
        if(visitor.isOp()) return;
        if (visitor.hasPermission("group.fakeplayers")) {
            message = ChatColor.translateAlternateColorCodes('&',
                    "&f[&7空岛&f] &c假人 &e" + visitor.getName() + " &c无法召唤到你的空岛，请检查空岛权限设置！");
        } else {
            message = ChatColor.translateAlternateColorCodes('&',
                    "&f[&7空岛&f] &e" + visitor.getName() + " &c尝试传送到你的空岛，但被遣返了。");
        }

        Players owner = island.getOwner();
        if (owner != null) {
            Player ownerPlayer = Bukkit.getPlayer(owner.getMojangId());
            if (ownerPlayer != null && !ownerPlayer.equals(visitor)) {
                ownerPlayer.sendMessage(message);
            }
        }

        for (Players member : island.getMembers()) {
            if (member.getMojangId().equals(visitor.getUniqueId())) continue;
            if (owner != null && member.getMojangId().equals(owner.getMojangId())) continue;
            Player memberPlayer = Bukkit.getPlayer(member.getMojangId());
            if (memberPlayer != null) {
                memberPlayer.sendMessage(message);
            }
        }
    }

    /**
     * 延迟 2 tick 将玩家传送回原位置，以模拟取消传送。
     */
    private void redirectToStart(Player player) {
        redirecting.add(player.getUniqueId());
        Location from = player.getLocation(); // 此时尚未完成传送，即起点
        Plugin plugin = SkylliaAPI.getPlugin();
        // 延迟 2 tick，确保原始传送已完成再纠正
        player.getScheduler().runDelayed(plugin, unused1 -> player.teleportAsync(from, PlayerTeleportEvent.TeleportCause.PLUGIN).thenRun(() -> redirecting.remove(player.getUniqueId())), null, 2L);
    }
}