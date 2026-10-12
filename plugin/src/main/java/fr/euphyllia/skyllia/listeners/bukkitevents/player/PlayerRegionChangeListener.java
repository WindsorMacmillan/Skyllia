package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.restore.IslandRestoreService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import io.papermc.paper.entity.Leashable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlayerRegionChangeListener implements Listener {

    // 本服务端项目自定义修改：进入/离开他人岛屿时打印到控制台
    private static final Logger LOGGER = LogManager.getLogger(PlayerRegionChangeListener.class);

    private final Map<UUID, Position> lastRegion = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBlockTime = new ConcurrentHashMap<>();

    // 本服务端项目自定义修改：注意——Lophine 不提供可取消的传送事件
    // （PlayerTeleportEvent 不可用，替代的异步传送事件亦不可取消），因此无法在此拦截传送。
    // 对传送/末影珍珠/传送门等闯入路径，改由 IslandRestoreService 的持续执法任务兜底
    // （周期性弹出闯入者并重新卸载区块）。

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        // 本服务端项目自定义修改：恢复期间的区块锁——只要目标区块落在被锁范围内就拒绝
        // （含 OP、含范围内移动与首次记录），以避免恢复范围内出现/保留玩家加载票据而加载区块。
        // 按「区块」精确判定：整岛恢复锁整区，区块恢复只锁目标区块，不会冻结同岛其它位置。
        // 必须放在「同方块早退」与「区域未变早退」之前才完整。
        IslandRestoreService restoreService = Skyllia.getInstance().getIslandRestoreService();
        if (restoreService != null && to.getWorld() != null
                && restoreService.isChunkLocked(to.getWorld(), to.getBlockX() >> 4, to.getBlockZ() >> 4)) {
            cancelAndNotifyRestore(event.getPlayer(), event);
            return;
        }

        // 忽略同一方块坐标内的移动
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) return;

        Player player = event.getPlayer();
        int chunkX = to.getBlockX() >> 4;
        int chunkZ = to.getBlockZ() >> 4;

        Position currentRegion = RegionHelper.getRegionFromChunk(chunkX, chunkZ);

        Position previousRegion = lastRegion.get(player.getUniqueId());

        // 首次记录，只保存不提示
        if (previousRegion == null) {
            lastRegion.put(player.getUniqueId(), currentRegion);
            return;
        }

        // 区域未改变
        if (previousRegion.equals(currentRegion)) return;

        // ── 进入新区域前的访问权限检查（主城 [0,0] 除外）──
        if ((currentRegion.x() != 0 || currentRegion.z() != 0) && !player.isOp()) {
            Island targetIsland = SkylliaAPI.getIslandByRegion(currentRegion);
            if (targetIsland != null) {
                UUID playerId = player.getUniqueId();
                Players owner = targetIsland.getOwner();
                boolean isOwner = owner != null && owner.getMojangId().equals(playerId);
                boolean isMember = isPrivilegedMember(targetIsland, playerId);

                if (!isOwner && !isMember) {
                    PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();

                    // 检查 bypass 权限：Bukkit 节点 + 内部权限 command.island.visit.bypass
                    PermissionId bypassPid = registry.getIfPresent(new NamespacedKey("skyllia", "command.island.visit.bypass"));
                    boolean bypass = (bypassPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                            player, targetIsland, bypassPid, null, ConfigLoader.general.getDebugSettings().permission()));
                    if (targetIsland.isPrivateIsland() && !bypass) {
                        cancelAndNotify(player, event, owner, "&f[&7空岛&f] &c你不能进入 &e%s &c的空岛，该岛屿不对外开放。");
                        return;
                    }

                    // 非私有岛屿，检查 player.teleport 内部权限
                    if (!bypass) {
                        PermissionId teleportPid = registry.getIfPresent(new NamespacedKey("skyllia", "player.teleport"));
                        boolean hasTeleport = teleportPid != null && SkylliaAPI.getPermissionsManager().hasPermission(
                                player, targetIsland, teleportPid, null, ConfigLoader.general.getDebugSettings().permission());
                        if (!hasTeleport) {
                            cancelAndNotify(player, event, owner, "&f[&7空岛&f] &c你不能进入 &e%s &c的空岛，你没有权限访问空岛。");
                            return;
                        }
                    }
                }
            }
        }

        // ── 离开旧区域（原有逻辑）──
        if (previousRegion.x() == 0 && previousRegion.z() == 0) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&f[&7空岛&f] &b你已离开空岛主城~"));
        } else {
            Island oldIsland = SkylliaAPI.getIslandByRegion(previousRegion);
            if (oldIsland != null) {
                UUID playerId = player.getUniqueId();
                boolean isOwnIsland = isOwnIsland(oldIsland, playerId);
                if (isOwnIsland) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&f[&7空岛&f] &c你已离开自己的空岛"));
                } else {
                    Players oldOwner = oldIsland.getOwner();
                    String oldOwnerName = oldOwner != null ? oldOwner.getLastKnowName() : "未知";
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&f[&7空岛&f] &c你已离开 &e" + oldOwnerName + " &c的空岛~"));
                    // 本服务端项目自定义修改：离开他人岛屿时打印到控制台（使用方块坐标）
                    LOGGER.info("玩家 {} 离开了 {} 的岛屿（坐标 {}, {}, {}）",
                            player.getName(), oldOwnerName,
                            from.getBlockX(), from.getBlockY(), from.getBlockZ());
                    if(!player.isOp()) notifyIslandMembers(oldIsland, player, false);
                }
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&f[&7空岛&f] &6你已离开无人区~"));
            }
        }

        // ── 进入新区域（更新记录并发送消息）──
        lastRegion.put(player.getUniqueId(), currentRegion);

        if (currentRegion.x() == 0 && currentRegion.z() == 0) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&f[&7空岛&f] &b你已进入空岛主城~"));
            if (!player.isOp()) {
                killPlayerWithLeashedEntities(player);
            }
        } else {
            Island newIsland = SkylliaAPI.getIslandByRegion(currentRegion);
            if (newIsland != null) {
                UUID playerId = player.getUniqueId();
                boolean isOwnIsland = isOwnIsland(newIsland, playerId);
                if (isOwnIsland) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&f[&7空岛&f] &a你已回到自己的空岛~"));
                } else {
                    Players newOwner = newIsland.getOwner();
                    String newOwnerName = newOwner != null ? newOwner.getLastKnowName() : "未知";
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&f[&7空岛&f] &a你已进入 &e" + newOwnerName + " &a的空岛~"));
                    // 本服务端项目自定义修改：进入他人岛屿时打印到控制台（使用方块坐标）
                    LOGGER.info("玩家 {} 进入了 {} 的岛屿（坐标 {}, {}, {}）",
                            player.getName(), newOwnerName,
                            to.getBlockX(), to.getBlockY(), to.getBlockZ());
                    if(!player.isOp()) notifyIslandMembers(newIsland, player, true);
                }
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&f[&7空岛&f] &6你已进入无人区，下次再来探索吧~"));
            }
        }
    }

    /**
     * Main-city boundaries must not be crossed while transporting leashed entities.
     * A leash can reach ten blocks, so only nearby entities need to be inspected.
     */
    private void killPlayerWithLeashedEntities(Player player) {
        Set<UUID> visitedEntities = ConcurrentHashMap.newKeySet();
        AtomicBoolean playerDeathScheduled = new AtomicBoolean(false);
        for (Entity entity : player.getNearbyEntities(11.0D, 11.0D, 11.0D)) {
            scheduleConnectedEntity(player, entity, visitedEntities, playerDeathScheduled, true);
        }
    }

    private void scheduleConnectedEntity(Player player, Entity entity, Set<UUID> visitedEntities,
                                          AtomicBoolean playerDeathScheduled, boolean requireLeash) {
        if (entity.equals(player)) return;
        if (!visitedEntities.add(entity.getUniqueId())) return;

        entity.getScheduler().execute(SkylliaAPI.getPlugin(), () -> {
            if (requireLeash) {
                if (!(entity instanceof Leashable leashable) || !leashable.isLeashed()) return;

                Entity leashHolder;
                try {
                    leashHolder = leashable.getLeashHolder();
                } catch (IllegalStateException ignored) {
                    return;
                }
                if (leashHolder != player) return;
            }

            Entity vehicle = entity.getVehicle();
            if (vehicle != null) {
                scheduleConnectedEntity(player, vehicle, visitedEntities, playerDeathScheduled, false);
            }
            for (Entity passenger : entity.getPassengers()) {
                scheduleConnectedEntity(player, passenger, visitedEntities, playerDeathScheduled, false);
            }

            if (entity instanceof LivingEntity livingEntity) {
                livingEntity.setHealth(0.0D);
            } else {
                entity.remove();
            }

            if (playerDeathScheduled.compareAndSet(false, true)) {
                player.getScheduler().execute(SkylliaAPI.getPlugin(), () -> {
                    if (player.isOnline()) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                "&f[&7空岛&f] &c不要试图将生物带进主城，枪毙喵"));
                        if (!player.isDead()) {
                            player.setHealth(0.0D);
                        }
                    }
                }, null, 1L);
            }
        }, null, 1L);
    }

    /**
     * 取消移动事件并根据冷却发送拦截消息。
     *
     * @param player      玩家
     * @param event       移动事件
     * @param owner       目标岛屿岛主
     * @param messageFormat 消息格式，%s 将被替换为岛主名
     */
    private void cancelAndNotify(Player player, PlayerMoveEvent event, Players owner,
                                 String messageFormat) {
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long lastTime = lastBlockTime.get(uuid);

        // 冷却检查：距离上次拦截超过 1 秒才发送消息
        if (lastTime == null || now - lastTime >= 1000) {
            String ownerName = owner != null ? owner.getLastKnowName() : "未知";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    String.format(messageFormat, ownerName)));
            lastBlockTime.put(uuid, now);
        }
        // 无论是否发送消息，都不更新 lastRegion，玩家仍在旧区域
    }

    /**
     * 取消移动事件并提示区域正在恢复存档（用于恢复期间的区域锁）。
     */
    private void cancelAndNotifyRestore(Player player, PlayerMoveEvent event) {
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long lastTime = lastBlockTime.get(uuid);
        if (lastTime == null || now - lastTime >= 1000) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&f[&7空岛&f] &c该区域正在恢复存档，暂时无法进入，请稍后再试。"));
            lastBlockTime.put(uuid, now);
        }
        // 不更新 lastRegion，玩家仍停留在旧区域
    }

    /**
     * 判断玩家是否为岛屿的真正成员（非被禁足）。
     */
    private boolean isPrivilegedMember(Island island, UUID playerId) {
        Players member = island.getMember(playerId);
        return member != null && member.getRoleType() != RoleType.BAN;
    }

    /**
     * 判断该岛屿是否为玩家自己的岛屿（岛主或未被禁足的成员）。
     */
    private boolean isOwnIsland(Island island, UUID playerId) {
        Players owner = island.getOwner();
        if (owner != null && owner.getMojangId().equals(playerId)) return true;
        return isPrivilegedMember(island, playerId);
    }

    private void notifyIslandMembers(Island island, Player visitor, boolean entering) {
        String visitorName = visitor.getName();
        String action = entering ? "访问了" : "离开了";
        String message = "&f[&7空岛&f] &e" + visitorName + " &d" + action + "你的空岛~";
        String colored = ChatColor.translateAlternateColorCodes('&', message);

        Players owner = island.getOwner();
        if (owner != null) {
            Player ownerPlayer = Bukkit.getPlayer(owner.getMojangId());
            if (ownerPlayer != null && !ownerPlayer.equals(visitor)) {
                ownerPlayer.sendMessage(colored);
            }
        }

        for (Players member : island.getMembers()) {
            if (member.getMojangId().equals(visitor.getUniqueId())) continue;
            if (owner != null && member.getMojangId().equals(owner.getMojangId())) continue;
            Player memberPlayer = Bukkit.getPlayer(member.getMojangId());
            if (memberPlayer != null) {
                memberPlayer.sendMessage(colored);
            }
        }
    }
}
