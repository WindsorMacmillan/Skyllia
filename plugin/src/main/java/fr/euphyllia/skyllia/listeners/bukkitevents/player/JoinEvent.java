package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.enums.RemovalCause;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.cache.commands.CacheCommands;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.managers.skyblock.SkyblockManager;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.UpdateCheckerTask;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

public class JoinEvent implements Listener {

    private final InterneAPI api;
    private final Logger logger = LogManager.getLogger(JoinEvent.class);

    public JoinEvent(InterneAPI interneAPI) {
        this.api = interneAPI;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoinNotifUpdate(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();

        player.getScheduler().execute(api.getPlugin(),
                () -> UpdateCheckerTask.notifyIfUpdateAvailable(player),
                null, 40L);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        final String worldName = player.getWorld().getName();
        // 在事件线程中捕获坐标，避免跨线程读取 location
        final int joinChunkX = player.getLocation().getBlockX() >> 4;
        final int joinChunkZ = player.getLocation().getBlockZ() >> 4;

        Bukkit.getAsyncScheduler().runNow(api.getPlugin(), unused1 -> {
            try {
                CacheCommands.refreshFor(playerId);

                final SkyblockManager skyblockManager = api.getSkyblockManager();
                final Island island = SkylliaAPI.getIslandByPlayerId(playerId);

                if (island != null) {
                    Players member = island.getMember(playerId);
                    if (member != null) {
                        String currentName = player.getName();
                        String last = member.getLastKnowName();
                        if (last == null || !last.equals(currentName)) {
                            member.setLastKnowName(currentName);
                            skyblockManager.updateMember(island, member);
                        }
                    }
                }

                // ── 登录安全检查：如果玩家当前位于不应访问的空岛上，强制传送 ──
                if (WorldUtils.isWorldSkyblock(worldName)) {
                    Island currentIsland = SkylliaAPI.getIslandByChunk(joinChunkX, joinChunkZ);
                    if (currentIsland != null && !isPlayerAllowedOnIsland(player, currentIsland)) {
                        teleportToSafeLocation(player, island);
                        return;  // 跳过后续的 shouldTeleportSpawn
                    }
                }

                boolean shouldTeleportSpawn = island == null ||
                        (ConfigLoader.playerManager.isTeleportOwnIslandOnJoin() && !WorldUtils.isWorldSkyblock(worldName));

                if (shouldTeleportSpawn) {
                    if (ConfigLoader.playerManager.isTeleportSpawnIfNoIsland()) {
                        PlayerUtils.teleportPlayerSpawn(player);
                    }
                }

                checkAndClearPlayerStuffOnJoin(player);

            } catch (Exception e) {
                logger.error("Error during JoinEvent async task for {}", playerId, e);
            }
        });
    }


    /**
     * 判断玩家是否有权访问当前所在的岛屿。
     * 被拉黑、岛屿不开放且无 bypass、或缺少 player.teleport 权限 → 无权限。
     */
    private boolean isPlayerAllowedOnIsland(Player player, Island currentIsland) {
        UUID playerId = player.getUniqueId();

        // 岛主永远允许
        if (currentIsland.getOwner() != null && currentIsland.getOwner().getMojangId().equals(playerId)) {
            return true;
        }

        // 被拉黑 → 不允许
        Players member = currentIsland.getMember(playerId);
        if (member != null && member.getRoleType() == RoleType.BAN && !player.isOp()) {
            ConfigLoader.language.sendMessage(player, "island.visit.banned");
            return false;
        }

        // 成员/访客：检查 player.teleport 权限
        PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        PermissionId teleportPid = registry.getIfPresent(new NamespacedKey("skyllia", "player.teleport"));
        boolean hasTeleport = teleportPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                player, currentIsland, teleportPid, null, ConfigLoader.general.getDebugSettings().permission());
        if (!player.isOp() && !hasTeleport) {
            ConfigLoader.language.sendMessage(player, "island.player.permission-denied");
            return false;
        }

        // 私有岛屿额外检查 bypass
        if (currentIsland.isPrivateIsland()) {
            PermissionId bypassPid = registry.getIfPresent(new NamespacedKey("skyllia", "command.island.visit.bypass"));
            boolean bypass = bypassPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                    player, currentIsland, bypassPid, null, ConfigLoader.general.getDebugSettings().permission());
            if(player.isOp() || bypass) {
                return true;
            }
            ConfigLoader.language.sendMessage(player, "island.visit.island-closed");
            return false;
        }

        return true;
    }

    /**
     * 将玩家传送到安全位置：若有空岛则传回自己空岛，否则传送到主世界 (256.5, 64, 256.5)。
     */
    private void teleportToSafeLocation(Player player, Island ownIsland) {
        if (ownIsland != null) {
            // 传送到玩家自己的空岛中心
            player.getScheduler().run(api.getPlugin(), unused2 -> {
                if (!player.isOnline()) return;
                World world = player.getWorld();
                RegionCoordinate region = ownIsland.getRegionCoordinate();
                Location center = RegionHelper.getCenterRegion(world, region.x(), region.z());
                center.setY(64);
                player.teleportAsync(center, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }, null);
        } else {
            // 没有空岛 → 传送到主世界固定坐标
            player.getScheduler().run(api.getPlugin(), unused3 -> {
                if (!player.isOnline()) return;
                World world = Bukkit.getWorld("world");
                if (world != null) {
                    Location spawn = new Location(world, 256.5, 64, 256.5);
                    player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
            }, null);
        }
    }

    private void checkAndClearPlayerStuffOnJoin(Player player) {
        UUID uuid = player.getUniqueId();

        for (RemovalCause cause : RemovalCause.values()) {
            boolean deleted = api.getSkyblockManager().deleteClearMember(uuid, cause);
            if (!deleted) continue;

            player.getScheduler().execute(api.getPlugin(), () -> {
                clearPlayerData(player, cause);
                player.setGameMode(GameMode.SURVIVAL);
            }, null, 1L);
        }
    }


    private void clearPlayerData(Player player, RemovalCause cause) {
        switch (cause) {
            case KICKED -> {
                if (ConfigLoader.playerManager.isClearInventoryWhenKicked()) player.getInventory().clear();
                if (ConfigLoader.playerManager.isClearEnderChestWhenKicked()) player.getEnderChest().clear();
                if (ConfigLoader.playerManager.isResetExperienceWhenKicked()) {
                    player.setLevel(0);
                    player.setExp(0);
                    player.setTotalExperience(0);
                    player.sendExperienceChange(0, 0);
                }
            }
            case ISLAND_DELETED -> {
                if (ConfigLoader.playerManager.isClearInventoryWhenDelete()) player.getInventory().clear();
                if (ConfigLoader.playerManager.isClearEnderChestWhenDelete()) player.getEnderChest().clear();
                if (ConfigLoader.playerManager.isResetExperienceWhenDelete()) {
                    player.setLevel(0);
                    player.setExp(0);
                    player.setTotalExperience(0);
                    player.sendExperienceChange(0, 0);
                }
            }
            case LEAVE -> {
                if (ConfigLoader.playerManager.isClearInventoryWhenLeave()) player.getInventory().clear();
                if (ConfigLoader.playerManager.isClearEnderChestWhenLeave()) player.getEnderChest().clear();
                if (ConfigLoader.playerManager.isResetExperienceWhenLeave()) {
                    player.setLevel(0);
                    player.setExp(0);
                    player.setTotalExperience(0);
                    player.sendExperienceChange(0, 0);
                }
            }
        }
    }
}
