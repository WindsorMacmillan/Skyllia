package fr.euphyllia.skyllia.listeners.bukkitevents.portal;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import fr.euphyllia.skyllia.api.event.players.PlayerPrepareChangeWorldSkyblockEvent;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.Vector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PortalOverrideListener implements Listener {

    private static final Logger logger = LoggerFactory.getLogger(PortalOverrideListener.class);
    // 改为 ConcurrentHashMap，存储 UUID 和添加时间戳
    private final ConcurrentHashMap<UUID, Long> processingPlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> processingEntities = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        Entity entity = event.getEntity();
        Location from = entity.getLocation();
        World fromWorld = from.getWorld();

        if (fromWorld == null) return;
        if (event.getPortalType() != PortalType.ENDER) return;

        // 取消原版传送，防止冲突
        event.setCancelled(true);

        // 玩家去重：防止同一 tick 多次触发
        if (entity instanceof Player player) {
            if (!addWithTimeout(processingPlayers, player.getUniqueId())) {
                logger.debug("[传送门覆写] 玩家 {} 已在处理中，跳过重复触发", player.getName());
                return;
            }
        }

        // 权限检查等
        if (entity instanceof Player player) {
            if (!SkylliaAPI.isWorldSkyblock(fromWorld)) {
                logger.info("[传送门覆写] 玩家 {} 不在空岛世界中", player.getName());
                processingPlayers.remove(player.getUniqueId());
                return;
            }
            WorldConfig worldConfig = ConfigLoader.worldManager.getWorldConfig(fromWorld.getName());
            if (worldConfig != null && !worldConfig.getPortalEnd().equalsIgnoreCase(fromWorld.getName())) {
                ListenersUtils.callPlayerPrepareChangeWorldSkyblockEvent(
                        player, worldConfig,
                        PlayerPrepareChangeWorldSkyblockEvent.PortalType.END, event);
            }
        }

        // 实际传送逻辑
        if (entity instanceof Player player) {
            handlePlayerPortalEnter(player, from, fromWorld);
        } else {
            handleNonPlayerPortalEnter(entity, from, fromWorld);
        }
    }

    // 修改 addWithTimeout：只记录时间戳，不再提交延迟任务
    private boolean addWithTimeout(ConcurrentHashMap<UUID, Long> map, UUID uuid) {
        long now = System.currentTimeMillis();
        Long prev = map.putIfAbsent(uuid, now);
        return prev == null; // 返回 true 表示添加成功（之前不存在）
    }

    /**
     * 启动周期清理任务（每 2 秒扫描一次，移除超时未处理的条目）
     */
    public void startCleanupTask() {
        Bukkit.getAsyncScheduler().runAtFixedRate(
                SkylliaAPI.getPlugin(),
                unused1 -> {
                    long cutoff = System.currentTimeMillis() - 1000; // 1 秒超时
                    processingPlayers.entrySet().removeIf(entry -> entry.getValue() < cutoff);
                    processingEntities.entrySet().removeIf(entry -> entry.getValue() < cutoff);
                },
                2, 2, TimeUnit.SECONDS
        );
    }

    private void handleNonPlayerPortalEnter(Entity entity, Location from, World fromWorld) {
        if (fromWorld.getEnvironment() == World.Environment.THE_END) return;

        if (!addWithTimeout(processingEntities, entity.getUniqueId())) {
            logger.debug("[传送门覆写] 实体 {} 已在处理中，跳过重复触发", entity.getType());
            return;
        }

        World endWorld = getEndWorld();
        if (endWorld == null) {
            processingEntities.remove(entity.getUniqueId());
            logger.warn("[传送门覆写] 找不到末地世界，无法传送实体 {}", entity.getType());
            return;
        }

        Location target = from.clone();
        target.setWorld(endWorld);
        Vector velocity = entity.getVelocity();

        //logger.info("[传送门覆写] 传送实体 {} 到末地坐标 {}，保留速度 {}", entity.getType(), target.toVector(), velocity);

        // 下落方块特殊处理：在原世界移除，在新世界区域重建
        if (entity instanceof org.bukkit.entity.FallingBlock fallingBlock) {
            // 在移除前提取所有需要的属性，防止跨线程访问
            org.bukkit.block.data.BlockData blockData = fallingBlock.getBlockData().clone();
            boolean dropItem = fallingBlock.getDropItem();
            boolean hurtEntities = fallingBlock.canHurtEntities();
            int ticksLived = fallingBlock.getTicksLived();  // 现在仍在原线程，安全

            // 立即移除原实体（当前线程安全）
            fallingBlock.remove();
            processingEntities.remove(entity.getUniqueId()); // 原实体已移除，清除标记

            // 在末地对应区域线程中生成新的下落方块
            int chunkX = target.getBlockX() >> 4;
            int chunkZ = target.getBlockZ() >> 4;
            SkylliaAPI.getPlugin().getServer().getRegionScheduler().execute(
                    SkylliaAPI.getPlugin(),
                    endWorld,
                    chunkX,
                    chunkZ,
                    () -> {
                        // 使用保存的局部变量生成新实体
                        org.bukkit.entity.FallingBlock newFalling = endWorld.spawnFallingBlock(target, blockData);
                        newFalling.setVelocity(velocity);
                        newFalling.setDropItem(dropItem);
                        newFalling.setHurtEntities(hurtEntities);
                        newFalling.setTicksLived(ticksLived);     // 传递已保存的 ticksLived
                        //logger.info("[传送门覆写] 已在末地重建下落方块，并设置速度");
                    }
            );
            return;
        }

        // 其他非玩家实体保持异步传送
        Bukkit.getGlobalRegionScheduler().execute(SkylliaAPI.getPlugin(), () -> entity.teleportAsync(target, TeleportCause.END_PORTAL)
                .whenComplete((unused, throwable) -> {
                    processingEntities.remove(entity.getUniqueId());
                    if (throwable != null) {
                        logger.error("[传送门覆写] 实体 {} 异步传送失败", entity.getType(), throwable);
                    } else {
                        entity.getScheduler().runDelayed(SkylliaAPI.getPlugin(),
                                unused2 -> {
                                    entity.setVelocity(velocity);
                                    //logger.info("[传送门覆写] 已恢复实体 {} 的速度", entity.getType());
                                }, null, 1);
                    }
                }));
    }

    private void handlePlayerPortalEnter(Player player, Location from, World fromWorld) {
        UUID uuid = player.getUniqueId();

        if (fromWorld.getEnvironment() != World.Environment.THE_END) {
            // 主世界 / 地狱 → 末地
            World endWorld = getEndWorld();
            if (endWorld == null) {
                processingPlayers.remove(uuid);
                logger.warn("[传送门覆写] 找不到末地世界");
                return;
            }

            Location target = from.clone();
            target.setWorld(endWorld);
            Vector velocity = player.getVelocity();

            logger.info("[传送门覆写] 传送玩家 {} 到末地 {}，保留速度 {}", player.getName(), target.toVector(), velocity);

            Bukkit.getGlobalRegionScheduler().execute(SkylliaAPI.getPlugin(), () -> player.teleportAsync(target, TeleportCause.END_PORTAL).thenRun(() -> player.getScheduler().runDelayed(SkylliaAPI.getPlugin(),
                    ignored2 -> {
                        player.setVelocity(velocity);
                        processingPlayers.remove(uuid);
                        logger.info("[传送门覆写] 已恢复玩家 {} 的速度", player.getName());
                    }, null, 1)));
        } else {
            // 末地 → 家
            logger.info("[传送门覆写] 玩家 {} 使用末地返回传送门，传送回家", player.getName());

            int chunkX = player.getLocation().getBlockX() >> 4;
            int chunkZ = player.getLocation().getBlockZ() >> 4;
            Island currentIsland = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
            Island playerIsland = SkylliaAPI.getIslandByPlayerId(uuid);
            Island targetIsland = currentIsland != null ? currentIsland : playerIsland;
            World mainWorld = getMainWorld();

            if (targetIsland != null && mainWorld != null) {
                Location home = getIslandHome(targetIsland, mainWorld); // 未加高

                // 出生点已经是主世界 → 直接传送
                if (home.getWorld() != null && home.getWorld().getEnvironment() == World.Environment.NORMAL) {
                    home.add(0, 0.5, 0);
                    Bukkit.getGlobalRegionScheduler().execute(SkylliaAPI.getPlugin(),
                            () -> player.teleportAsync(home, TeleportCause.END_PORTAL).thenRun(() -> {
                        player.setVelocity(new Vector(0, 0, 0));
                        player.setFallDistance(0);
                        processingPlayers.remove(uuid);
                        logger.debug("[传送门覆写] 玩家 {} 已到家", player.getName());
                    }));
                } else {
                    // 出生点不是主世界 → 强制传送到主世界的岛屿中心 Y=64
                    RegionCoordinate islandRegion = targetIsland.getRegionCoordinate();
                    Location center = RegionHelper.getCenterRegion(mainWorld, islandRegion.x(), islandRegion.z());
                    center.setY(64);

                    // 在目标区域线程检查并放置圆石，然后传送
                    SkylliaAPI.getPlugin().getServer().getRegionScheduler().execute(
                            SkylliaAPI.getPlugin(),
                            mainWorld,
                            center.getBlockX() >> 4,
                            center.getBlockZ() >> 4,
                            () -> {
                                Location below = center.clone().subtract(0, 1, 0);
                                if (below.getBlock().getType().isAir()) {
                                    below.getBlock().setType(Material.COBBLESTONE);
                                }
                                Location teleportDest = center.clone().add(0, 0.5, 0);
                                player.teleportAsync(teleportDest, TeleportCause.END_PORTAL).thenRun(() -> {
                                    player.setVelocity(new Vector(0, 0, 0));
                                    player.setFallDistance(0);
                                    processingPlayers.remove(uuid);
                                    logger.debug("[传送门覆写] 玩家 {} 已送到主世界中心", player.getName());
                                });
                            }
                    );
                }
            } else {
                // 安全处理：主世界出生点
                Bukkit.getGlobalRegionScheduler().execute(SkylliaAPI.getPlugin(), () -> {
                    if (mainWorld != null) {
                        player.teleportAsync(mainWorld.getSpawnLocation(), TeleportCause.END_PORTAL)
                                .thenRun(() -> processingPlayers.remove(uuid));
                    } else {
                        player.teleportAsync(new Location(fromWorld, 0, 64, 0), TeleportCause.END_PORTAL)
                                .thenRun(() -> processingPlayers.remove(uuid));
                    }
                });
            }
        }
    }


    private Location getIslandHome(Island island, World mainWorld) {
        WarpIsland homeWarp = island.getWarpByName("home");
        if (homeWarp != null && homeWarp.location() != null) {
            return homeWarp.location().clone();
        }
        return island.getCenterLocation(mainWorld);
    }

    private World getEndWorld() {
        return SkylliaAPI.getPlugin().getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.THE_END)
                .findFirst().orElse(null);
    }

    private World getMainWorld() {
        return SkylliaAPI.getPlugin().getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null);
    }
}