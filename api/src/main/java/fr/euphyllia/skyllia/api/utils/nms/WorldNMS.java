package fr.euphyllia.skyllia.api.utils.nms;

import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.ChunkCoordinate;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.world.WorldFeedback;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Provides methods for interacting with the Minecraft world using NMS (net.minecraft.server) classes.
 */
public abstract class WorldNMS {

    /**
     * Creates a new world using the specified WorldCreator.
     *
     * @param creator The WorldCreator to use for creating the world.
     * @return A FeedbackWorld object containing feedback about the world creation process.
     */
    public abstract WorldFeedback.FeedbackWorld createWorld(WorldCreator creator);

    /**
     * Creates a new world with optional custom height settings.
     * Implementations that support custom height should override this method.
     * The default implementation ignores the WorldConfig height settings and
     * falls back to {@link #createWorld(WorldCreator)}.
     *
     * @param creator     The WorldCreator to use for creating the world.
     * @param worldConfig The world configuration, potentially containing custom height settings.
     * @return A FeedbackWorld object containing feedback about the world creation process.
     */
    public WorldFeedback.FeedbackWorld createWorld(WorldCreator creator, WorldConfig worldConfig) {
        return createWorld(creator);
    }

    public abstract Map<Material, Integer> getCountAllBlocksInChunk(@NotNull World world, int chunkX, int chunkZ);

    /**
     * 本服务端项目自定义修改：统计单个区块内各类型方块数量，且<b>不加载区块</b>。
     * <p>与 {@link #getCountAllBlocksInChunk(World, int, int)} 不同，本方法直接读取区域文件中的
     * 原始区块 NBT（经服务端自身的存储层与 datafixer），仅解析方块调色板，不会把区块载入
     * 内存、不触发光照引擎 / POI / 实体重建，因此可在异步线程上安全地批量离线统计。</p>
     * <p>默认实现返回空表（表示当前 NMS 版本不支持离线读取），由支持该能力的 NMS 版本覆写。
     * 调用方（如光荣榜的方块类排名）据此对不支持的版本自动降级。</p>
     *
     * @param world  目标世界
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return 方块类型到数量的映射；区块未生成或读取失败时返回空表
     */
    public Map<Material, Integer> getCountAllBlocksInChunkFromDisk(@NotNull World world, int chunkX, int chunkZ) {
        return Map.of();
    }

    /**
     * 本服务端项目自定义修改：返回该世界维度目录的绝对路径。
     * <p>即包含 {@code region/}、{@code poi/}、{@code entities/} 子目录的那个目录
     * （现代布局为 {@code <世界根>/dimensions/<命名空间>/<维度>}）。用于备份/恢复时定位文件。
     * 默认实现返回 {@code null}（当前 NMS 版本不支持），由支持的版本覆写。</p>
     *
     * @param world 目标世界
     * @return 维度目录的绝对路径字符串，不可用时返回 {@code null}
     */
    public @Nullable String getDimensionFolder(@NotNull World world) {
        return null;
    }

    /**
     * 本服务端项目自定义修改：返回该世界「世界文件夹」的绝对路径（如 {@code <根>/world}）。
     * <p>注意：不要依赖 {@code World#getWorldFolder()}——本服务端在部分情况下返回相对路径，
     * 会导致相对化结果为空。此方法取自服务端的 {@code LevelStorageAccess}，为绝对路径，
     * 与 {@link #getDimensionFolder(World)} 同源，二者相对化结果可靠。</p>
     *
     * @return 世界文件夹绝对路径，不可用时返回 {@code null}
     */
    public @Nullable String getWorldFolder(@NotNull World world) {
        return null;
    }

    /**
     * 本服务端项目自定义修改：返回服务端根目录（世界文件夹的父目录）的绝对路径。
     * 用于解析配置中的相对备份根目录。
     *
     * @return 服务端根目录绝对路径，不可用时返回 {@code null}
     */
    public @Nullable String getServerRoot(@NotNull World world) {
        return null;
    }

    /**
     * 本服务端项目自定义修改：以「整体覆写」方式，用备份中的存档数据恢复指定的区块范围。
     * <p>直接经服务端自身的存储层写入：备份中存在的区块写入其 NBT，备份中缺失的区块删除其条目，
     * <b>不做任何合并</b>。全程不加载区块，因此不会触发任何方块更新。</p>
     * <p><b>调用前置条件</b>：目标区块必须已全部卸载，且恢复期间无玩家可进入，
     * 否则被加载的区块会用旧数据覆盖恢复结果。</p>
     * <p>默认实现返回 {@code false}（不支持），由支持的版本覆写。
     * 恢复整岛/整维度时传入整区范围即可（一个区域为 32×32 区块）。</p>
     *
     * @param world                 目标世界
     * @param backupDimensionFolder 备份中该维度目录的绝对路径（用于定位备份文件）
     * @param minChunkX             起始区块 X（含）
     * @param minChunkZ             起始区块 Z（含）
     * @param maxChunkX             结束区块 X（含）
     * @param maxChunkZ             结束区块 Z（含）
     * @param restoreChunk          是否恢复区块数据（region/）
     * @param restorePoi            是否恢复 POI 数据（poi/）
     * @param restoreEntities       是否恢复实体数据（entities/）
     * @param progress              每完成一个区块-类别单位的写入后回调一次（可为 {@code null}），
     *                              用于上层展示进度；回调在异步恢复线程上执行
     * @return 全部成功返回 {@code true}，任一环节失败返回 {@code false}
     */
    public boolean restoreChunksFromBackup(@NotNull World world, @NotNull String backupDimensionFolder,
                                           int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                           boolean restoreChunk, boolean restorePoi, boolean restoreEntities,
                                           @Nullable java.util.function.IntConsumer progress) {
        return false;
    }

    /**
     * 本服务端项目自定义修改：带「区域偏移」的覆盖式恢复。
     * <p>用于玩家岛屿被删除后重建（新岛屿分配了新的区域坐标）时，把备份中<b>旧岛屿</b>的数据
     * 映射到<b>新岛屿</b>区域。偏移以「区域」为单位：</p>
     * <pre>
     *   备份区域   = 目标区域   - 偏移
     *   备份区块   = 目标区块   - 偏移 × 32
     * </pre>
     * <p>其余语义与 {@link #restoreChunksFromBackup} 完全一致（整体覆写、不加载区块、不触发方块更新）。
     * 偏移为 0 时与不带偏移的版本等价。</p>
     *
     * @param regionOffsetX 区域偏移 X（新区域 - 旧区域）
     * @param regionOffsetZ 区域偏移 Z（新区域 - 旧区域）
     * @return 全部成功返回 {@code true}，任一环节失败返回 {@code false}
     */
    public boolean restoreChunksFromBackupOffset(@NotNull World world, @NotNull String backupDimensionFolder,
                                                 int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                                 int regionOffsetX, int regionOffsetZ,
                                                 boolean restoreChunk, boolean restorePoi, boolean restoreEntities,
                                                 @Nullable java.util.function.IntConsumer progress) {
        return false;
    }

    /**
     * 本服务端项目自定义修改：诊断用——在不做任何修改的前提下，报告恢复将使用的源与目标路径，
     * 以及备份中该区域各维度实际可读取到的区块数量。用于排查「恢复后为空」等问题
     * （通常是解析出的备份路径不存在区域文件，从而落入清空分支）。
     *
     * @param world                 目标世界
     * @param backupDimensionFolder 备份中该维度目录的绝对路径
     * @param regionX               区域 X 坐标
     * @param regionZ               区域 Z 坐标
     * @return 每行一条诊断信息；默认实现返回“不支持”
     */
    public @NotNull java.util.List<String> checkRestoreSource(@NotNull World world,
                                                              @NotNull String backupDimensionFolder,
                                                              int regionX, int regionZ) {
        return java.util.List.of("当前 NMS 版本不支持存档恢复诊断。");
    }

    /**
     * Resets a chunk at the specified chunk coordinate in the given world.
     *
     * @param craftWorld The world where the chunk is to be reset.
     * @param chunk      The chunk coordinate.
     */
    public abstract void resetChunk(World craftWorld, ChunkCoordinate chunk);

    /**
     * Resets a chunk at the specified position in the given world.
     *
     * @param craftWorld The world where the chunk is to be reset.
     * @param position   The position of the chunk to reset.
     * @deprecated Use {@link #resetChunk(World, ChunkCoordinate)} instead.
     */
    @Deprecated(forRemoval = true, since = "3.x")
    @ApiStatus.ScheduledForRemoval(inVersion = "4.x")
    public void resetChunk(World craftWorld, Position position) {
        resetChunk(craftWorld, new ChunkCoordinate(position.x(), position.z()));
    }

    /**
     * Gets the current location TPS.
     *
     * @param location the location for which to get the TPS
     * @return current location TPS (5s, 15s, 1m, 5m, 15m in Folia-Server), or null if the region doesn't exist
     */
    public abstract double @Nullable [] getTPS(Location location);

    /**
     * Gets the current chunk TPS.
     *
     * @param chunk the chunk for which to get the TPS
     * @return current location TPS (5s, 15s, 1m, 5m, 15m in Folia-Server), or null if the region doesn't exist
     */
    public abstract double @Nullable [] getTPS(Chunk chunk);

    /**
     * Gets the average tick times for a specific location.
     *
     * @param location the location for which to get the average tick times
     * @return an array of average tick times, or null if the region doesn't exist
     */
    public abstract double @Nullable [] getAverageTickTimes(Location location);

    /**
     * Gets the average tick times for a specific chunk.
     *
     * @param chunk the chunk for which to get the average tick times
     * @return an array of average tick times, or null if the region doesn't exist
     */
    public abstract double @Nullable [] getAverageTickTimes(Chunk chunk);

    /**
     * Gets the current Folia Tick Region id for a location.
     *
     * @param location the location to inspect
     * @return the current tick region id, or null when unavailable
     */
    public @Nullable Long getTickRegionId(@NotNull Location location) {
        return null;
    }

    public List<Entity> getEntities(World craftWorld, final @Nullable Entity except, final BoundingBox bb, Predicate<? super Entity> filter) {
        return List.of();
    }

    ;
}
