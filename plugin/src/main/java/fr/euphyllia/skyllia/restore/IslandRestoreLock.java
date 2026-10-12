package fr.euphyllia.skyllia.restore;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本服务端项目自定义修改：岛屿存档恢复期间的「区域锁」。
 *
 * <p>锁以「世界 + 区域坐标」为键，并同时记录被锁定的区块范围。恢复期间：</p>
 * <ul>
 *   <li>{@link fr.euphyllia.skyllia.listeners.bukkitevents.player.PlayerRegionChangeListener}
 *       会拒绝玩家步行进入被锁区域；</li>
 *   <li>{@link IslandRestoreService} 的持续执法任务会周期性地把被锁范围内的玩家弹出、
 *       并重新卸载被锁范围内的区块——由于 Lophine 的传送事件不可取消，这一步是保证
 *       「即使玩家闯入也不产生方块更新、不覆盖恢复数据」的关键兜底。</li>
 * </ul>
 *
 * <p>实例级、线程安全，随恢复任务创建/销毁，可安全热重载。</p>
 */
public final class IslandRestoreLock {

    /**
     * 被锁定的区块范围（含端点）。
     */
    public record LockedArea(@NotNull UUID worldId, @NotNull World world,
                             int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

        public boolean covers(int chunkX, int chunkZ) {
            return chunkX >= minChunkX && chunkX <= maxChunkX
                    && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }
    }

    private final Set<String> regionKeys = ConcurrentHashMap.newKeySet();
    private final List<LockedArea> areas = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static @NotNull String key(@NotNull UUID worldId, int regionX, int regionZ) {
        return worldId + ":" + regionX + ":" + regionZ;
    }

    public void lock(@NotNull World world, int regionX, int regionZ) {
        regionKeys.add(key(world.getUID(), regionX, regionZ));
    }

    public void unlock(@NotNull World world, int regionX, int regionZ) {
        regionKeys.remove(key(world.getUID(), regionX, regionZ));
    }

    public boolean isLocked(@NotNull World world, int regionX, int regionZ) {
        // 本服务端项目自定义修改：无任何锁时直接返回，避免每次移动都拼装键字符串
        if (regionKeys.isEmpty()) {
            return false;
        }
        return regionKeys.contains(key(world.getUID(), regionX, regionZ));
    }

    /**
     * 本服务端项目自定义修改：判断某个区块是否落在任一被锁范围内（区块级精确判定）。
     * <p>整岛恢复时被锁范围等于整个区域；区块恢复时仅锁目标区块，
     * 从而不会冻结同一岛屿其它位置的玩家。</p>
     */
    public boolean isChunkLocked(@NotNull World world, int chunkX, int chunkZ) {
        if (areas.isEmpty()) {
            return false;
        }
        UUID worldId = world.getUID();
        for (LockedArea area : areas) {
            if (area.worldId().equals(worldId) && area.covers(chunkX, chunkZ)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 登记一个被锁定的区块范围（供持续执法任务枚举）。
     */
    public void addArea(@NotNull World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        areas.add(new LockedArea(world.getUID(), world, minChunkX, minChunkZ, maxChunkX, maxChunkZ));
    }

    public void removeArea(@NotNull World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        areas.removeIf(area -> area.worldId().equals(world.getUID())
                && area.minChunkX() == minChunkX && area.minChunkZ() == minChunkZ
                && area.maxChunkX() == maxChunkX && area.maxChunkZ() == maxChunkZ);
    }

    public @NotNull List<LockedArea> lockedAreas() {
        return new ArrayList<>(areas);
    }

    public boolean isEmpty() {
        return regionKeys.isEmpty() && areas.isEmpty();
    }

    /**
     * 清空全部锁。仅在插件禁用/恢复任务收尾时调用。
     */
    public void clear() {
        regionKeys.clear();
        areas.clear();
    }
}
