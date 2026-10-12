package fr.euphyllia.skyllia.restore;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.utils.nms.WorldNMS;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本服务端项目自定义修改：岛屿存档恢复（热回档）服务。
 *
 * <p>以「整体覆写」方式把备份世界的存档数据恢复到岛屿区域（32×32 区块）或指定区块范围：
 * 全程经服务端自身的存储层写入，<b>不加载区块、不触发方块更新</b>。为保证这一点，恢复期间会：</p>
 * <ol>
 *   <li>锁定目标区块所属区域（{@link IslandRestoreLock}），并驱逐该范围内玩家；</li>
 *   <li>卸载目标区块并校验确已卸载；</li>
 *   <li>快照当前活库文件（安全网）；</li>
 *   <li>逐区块覆写 region/poi/entities；</li>
 *   <li>强制刷盘后解除锁定。</li>
 * </ol>
 *
 * <p>锁解除后玩家可正常进入，区块会从恢复后的磁盘数据重新加载。同一时刻只允许一个恢复任务运行。</p>
 */
public final class IslandRestoreService {

    private static final Logger LOGGER = LogManager.getLogger(IslandRestoreService.class);
    private static final int REGION_CHUNKS = 32;
    private static final long UNLOAD_TIMEOUT_MILLIS = 10_000L;
    private static final long UNLOAD_POLL_MILLIS = 100L;
    // 本服务端项目自定义修改：恢复期间持续执法的周期（秒）
    private static final long ENFORCE_PERIOD_SECONDS = 1L;
    // 本服务端项目自定义修改：驱逐玩家并卸载区块后，写盘前的静置时间，确保卸载真正落定
    private static final long SETTLE_DELAY_MILLIS = 3_000L;
    private static final DateTimeFormatter SNAPSHOT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final IslandRestoreLock lock = new IslandRestoreLock();
    private final Map<UUID, RestoreTask> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    // 本服务端项目自定义修改：恢复期间的持续执法任务
    private volatile ScheduledTask enforcementTask;

    public boolean isLocked(@NotNull World world, int regionX, int regionZ) {
        return lock.isLocked(world, regionX, regionZ);
    }

    /**
     * 本服务端项目自定义修改：判断某区块是否在被锁范围内（区块级，供入口拦截使用）。
     */
    public boolean isChunkLocked(@NotNull World world, int chunkX, int chunkZ) {
        return lock.isChunkLocked(world, chunkX, chunkZ);
    }

    public boolean isBusy() {
        return running.get();
    }

    /**
     * 本服务端项目自定义修改：诊断——返回某岛屿在各维度解析出的「备份维度目录」与
     * 「实机维度目录」，用于排查恢复源是否指对了地图。
     *
     * @return 每行一条信息
     */
    public @NotNull List<String> describeSources(@NotNull Island island) {
        List<String> lines = new ArrayList<>();
        WorldNMS nms = SkylliaAPI.getWorldNMS();
        if (Bukkit.getWorlds().isEmpty()) {
            lines.add("服务器暂无已加载世界。");
            return lines;
        }
        if (nms.getDimensionFolder(Bukkit.getWorlds().getFirst()) == null) {
            lines.add("当前服务端不支持存档恢复（需要 v26_3+）。");
            return lines;
        }
        Path backupRoot = resolveBackupRoot();
        lines.add("备份根目录（配置 settings.restore.backup-root）: " + backupRoot);
        lines.add("备份根是否存在: " + Files.isDirectory(backupRoot));
        World firstWorld = Bukkit.getWorlds().getFirst();
        lines.add("世界文件夹: " + nms.getWorldFolder(firstWorld));
        lines.add("服务端根目录: " + nms.getServerRoot(firstWorld));
        RegionCoordinate region = island.getRegionCoordinate();
        lines.add("岛屿区域: " + region.x() + "," + region.z()
                + "  区块范围: " + (region.x() << 5) + "~" + ((region.x() << 5) + 31)
                + " / " + (region.z() << 5) + "~" + ((region.z() << 5) + 31));
        for (Map.Entry<World.Environment, World> entry : findDimensionWorlds().entrySet()) {
            World world = entry.getValue();
            String liveDim = nms.getDimensionFolder(world);
            if (liveDim == null) {
                continue;
            }
            Path backupDim = computeBackupDimFolder(backupRoot, world, liveDim);
            lines.add("[" + environmentName(entry.getKey()) + "] 世界: " + world.getName());
            lines.add("    实机维度目录: " + liveDim);
            lines.add("    备份维度目录: " + backupDim);
            lines.add("    备份 region 文件: "
                    + backupDim.resolve("region").resolve("r." + region.x() + "." + region.z() + ".mca"));
        }
        return lines;
    }

    /**
     * 本服务端项目自定义修改：诊断——对某岛屿在指定维度上执行只读的恢复源检查。
     */
    public @NotNull List<String> checkSource(@NotNull Island island, @NotNull World.Environment environment) {
        World world = findDimensionWorlds().get(environment);
        if (world == null) {
            return List.of("未找到维度 " + environmentName(environment) + " 对应的世界。");
        }
        WorldNMS nms = SkylliaAPI.getWorldNMS();
        String liveDim = nms.getDimensionFolder(world);
        if (liveDim == null) {
            return List.of("当前服务端不支持存档恢复（需要 v26_3+）。");
        }
        RegionCoordinate region = island.getRegionCoordinate();
        Path backupDim = computeBackupDimFolder(resolveBackupRoot(), world, liveDim);
        return nms.checkRestoreSource(world, backupDim.toString(), region.x(), region.z());
    }

    /**
     * 本服务端项目自定义修改：插件禁用/热重载时调用，停止持续执法任务并清空区域锁。
     */
    public void stop() {
        stopEnforcement();
        lock.clear();
        tasks.clear();
    }

    public @Nullable RestoreTask taskOf(@NotNull UUID islandId) {
        return tasks.get(islandId);
    }

    public @NotNull List<RestoreTask> allTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 启动一次恢复。
     *
     * @param rect 目标区块范围（含端点）；为 {@code null} 时表示恢复该岛屿在每个维度下的整个区域
     * @return 已创建的任务；若已有任务在运行则返回 {@code null}
     */
    public @Nullable RestoreTask start(@NotNull Skyllia plugin, @NotNull Island island,
                                       @NotNull List<World.Environment> environments,
                                       @Nullable ChunkRect rect) {
        return start(plugin, island, environments, rect, null, 0, 0);
    }

    /**
     * 本服务端项目自定义修改：启动一次恢复，并将每一步的进展通过 {@code listener} 实时回调
     * （用于向命令执行者与控制台推送进度）。
     *
     * @param listener 步骤文本回调，可为 {@code null}；回调在异步恢复线程上执行
     */
    public @Nullable RestoreTask start(@NotNull Skyllia plugin, @NotNull Island island,
                                       @NotNull List<World.Environment> environments,
                                       @Nullable ChunkRect rect,
                                       @Nullable java.util.function.Consumer<String> listener) {
        return start(plugin, island, environments, rect, listener, 0, 0);
    }

    /**
     * 本服务端项目自定义修改：启动一次恢复，可带「区域偏移」。
     * <p>偏移用于把备份中<b>旧岛屿</b>区域的数据映射到<b>新岛屿</b>区域（岛屿删除重建后坐标变化）。
     * 偏移以区域为单位：{@code 目标区域 = 备份区域 + 偏移}。</p>
     *
     * @param regionOffsetX 区域偏移 X（新区域 - 旧区域）
     * @param regionOffsetZ 区域偏移 Z（新区域 - 旧区域）
     */
    public @Nullable RestoreTask start(@NotNull Skyllia plugin, @NotNull Island island,
                                       @NotNull List<World.Environment> environments,
                                       @Nullable ChunkRect rect,
                                       @Nullable java.util.function.Consumer<String> listener,
                                       int regionOffsetX, int regionOffsetZ) {
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        RestoreTask task = new RestoreTask(island.getId(), ownerName(island), environments.size());
        task.setListener(listener);
        tasks.put(island.getId(), task);
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduled ->
                    run(plugin, island, environments, rect, task, regionOffsetX, regionOffsetZ));
        } catch (RuntimeException exception) {
            running.set(false);
            task.markFailed("无法启动恢复任务：" + exception.getMessage());
            return null;
        }
        return task;
    }

    // 本服务端项目自定义修改：恢复主流程，运行于异步线程
    private void run(@NotNull Skyllia plugin, @NotNull Island island,
                     @NotNull List<World.Environment> environments,
                     @Nullable ChunkRect rect, @NotNull RestoreTask task,
                     int regionOffsetX, int regionOffsetZ) {
        boolean allOk = true;
        try {
            task.phase("准备中");
            WorldNMS nms = SkylliaAPI.getWorldNMS();
            if (nms.getDimensionFolder(Bukkit.getWorlds().getFirst()) == null) {
                task.markFailed("当前服务端不支持存档恢复（需要 v26_3+）");
                return;
            }
            Path backupRoot = resolveBackupRoot();
            RegionCoordinate region = island.getRegionCoordinate();
            Map<World.Environment, World> worlds = findDimensionWorlds();

            int activeDims = 0;
            for (World.Environment env : environments) {
                if (worlds.get(env) != null) {
                    activeDims++;
                }
            }
            ChunkRect target0 = rect != null ? rect : wholeRegion(region);
            task.totalWrites = activeDims * target0.chunkCount() * 3;

            for (World.Environment env : environments) {
                World world = worlds.get(env);
                if (world == null) {
                    LOGGER.warn("恢复岛屿 {}：未找到维度 {} 对应的世界，跳过", island.getId(), env);
                    continue;
                }
                ChunkRect target = rect != null ? rect : wholeRegion(region);
                String envName = environmentName(env);
                List<RegionCoordinate> covered = target.coveredRegions();
                for (RegionCoordinate rc : covered) {
                    lock.lock(world, rc.x(), rc.z());
                }
                // 本服务端项目自定义修改：登记被锁范围并启动持续执法（弹出闯入者 + 重新卸载）
                lock.addArea(world, target.minChunkX(), target.minChunkZ(),
                        target.maxChunkX(), target.maxChunkZ());
                startEnforcement(plugin);
                try {
                    task.phase("清场：" + envName);
                    if (!evacuateAndWait(world, target)) {
                        task.markFailed(envName + " 范围内仍有玩家，无法安全恢复（请确保出生点已启用）");
                        allOk = false;
                        return;
                    }

                    task.phase("卸载区块：" + envName);
                    if (!unloadAndVerify(plugin, world,
                            new IslandRestoreLock.LockedArea(world.getUID(), world,
                                    target.minChunkX(), target.minChunkZ(),
                                    target.maxChunkX(), target.maxChunkZ()),
                            task, envName)) {
                        task.markFailed("无法卸载 " + envName + " 的区块（可能有玩家或区块加载票据）");
                        allOk = false;
                        return;
                    }

                    // 本服务端项目自定义修改：卸载校验通过后再静置一段时间，确保卸载在服务端侧
                    // 真正落定（processUnloads 可能跨 tick，玩家票据也可能残留数 tick）。
                    // 否则未卸载的活区块会在写入后存盘、反向覆盖恢复数据。
                    task.phase("等待卸载落定：" + envName);
                    if (!settleDelay()) {
                        task.markFailed("恢复被取消（等待卸载落定期间中断）");
                        allOk = false;
                        return;
                    }
                    // 静置后二次确认确无残留加载，否则中止，避免覆盖恢复数据
                    if (anyLoadedInArea(world, new IslandRestoreLock.LockedArea(world.getUID(), world,
                            target.minChunkX(), target.minChunkZ(),
                            target.maxChunkX(), target.maxChunkZ()))) {
                        task.markFailed("等待后 " + envName + " 仍有区块处于加载状态，已中止（未修改数据）");
                        allOk = false;
                        return;
                    }

                    snapshotRegions(plugin, world, covered);

                    String liveDimFolder = nms.getDimensionFolder(world);
                    Path backupDim = computeBackupDimFolder(backupRoot, world, liveDimFolder);

                    // 本服务端项目自定义修改：安全阀——若备份中根本没有该区域的区块文件，
                    // 说明备份路径解析错误（否则会把整个岛清空成虚空）。此时中止，不做任何修改。
                    // 偏移模式下备份区域 = 目标区域 - 区域偏移。
                    int backupRegionX = region.x() - regionOffsetX;
                    int backupRegionZ = region.z() - regionOffsetZ;
                    Path backupRegionFile = backupDim.resolve("region")
                            .resolve("r." + backupRegionX + "." + backupRegionZ + ".mca");
                    if (!Files.isRegularFile(backupRegionFile)) {
                        task.markFailed("备份中未找到该岛屿的区块文件：" + backupRegionFile
                                + "（请用 /skylliadmin restore check <玩家> 核对备份路径；已中止，未修改任何数据）");
                        allOk = false;
                        return;
                    }

                    task.phase("覆写存档：" + envName);
                    boolean ok = nms.restoreChunksFromBackupOffset(world, backupDim.toString(),
                            target.minChunkX(), target.minChunkZ(),
                            target.maxChunkX(), target.maxChunkZ(),
                            regionOffsetX, regionOffsetZ,
                            true, true, true,
                            ignored -> task.reportWrite());
                    allOk &= ok;
                } finally {
                    lock.removeArea(world, target.minChunkX(), target.minChunkZ(),
                            target.maxChunkX(), target.maxChunkZ());
                    for (RegionCoordinate rc : covered) {
                        lock.unlock(world, rc.x(), rc.z());
                    }
                }
            }
            task.phase(allOk ? "完成" : "完成（部分失败）");
        } catch (Throwable throwable) {
            allOk = false;
            task.markFailed(throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage());
            LOGGER.error("岛屿 {} 存档恢复失败", island.getId(), throwable);
        } finally {
            // 本服务端项目自定义修改：恢复结束，停止持续执法并清空区域锁
            stopEnforcement();
            lock.clear();
            task.finish(allOk);
            running.set(false);
            LOGGER.info("岛屿 {}（{}）存档恢复结束：{}，写入 {}/{} 区块单元",
                    island.getId(), task.ownerName(), task.phase(),
                    task.completedWrites(), task.totalWrites());
        }
    }

    // 本服务端项目自定义修改：把目标范围内的玩家传送出去，并等待其确实离开。
    // 玩家传送是下一 tick 生效的异步操作，因此循环重试直到范围内无人或超时。
    private boolean evacuateAndWait(@NotNull World world, @NotNull ChunkRect target) {
        long deadline = System.currentTimeMillis() + UNLOAD_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            List<Player> inside = playersInside(world, target);
            if (inside.isEmpty()) {
                return true;
            }
            for (Player player : inside) {
                PlayerUtils.teleportPlayerSpawn(player);
            }
            try {
                Thread.sleep(UNLOAD_POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return playersInside(world, target).isEmpty();
    }

    private static @NotNull List<Player> playersInside(@NotNull World world, @NotNull ChunkRect target) {
        int minBlockX = target.minChunkX() << 4;
        int minBlockZ = target.minChunkZ() << 4;
        int maxBlockX = (target.maxChunkX() << 4) + 15;
        int maxBlockZ = (target.maxChunkZ() << 4) + 15;
        List<Player> inside = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(world) || !player.isOnline()) {
                continue;
            }
            int bx = player.getLocation().getBlockX();
            int bz = player.getLocation().getBlockZ();
            if (bx >= minBlockX && bx <= maxBlockX && bz >= minBlockZ && bz <= maxBlockZ) {
                inside.add(player);
            }
        }
        return inside;
    }

    // 本服务端项目自定义修改：在区域线程上卸载目标区块并轮询校验确已卸载。
    // Folia 下一个区域线程只能卸载属于自己区域的区块，因此按区域逐个提交卸载任务。
    private boolean unloadAndVerify(@NotNull Skyllia plugin, @NotNull World world,
                                    @NotNull IslandRestoreLock.LockedArea area,
                                    @NotNull RestoreTask task, @NotNull String envName) {
        List<CompletableFuture<Boolean>> dispatched = new ArrayList<>();
        for (int regionX = area.minChunkX() >> 5; regionX <= (area.maxChunkX() >> 5); regionX++) {
            for (int regionZ = area.minChunkZ() >> 5; regionZ <= (area.maxChunkZ() >> 5); regionZ++) {
                int baseChunkX = regionX << 5;
                int baseChunkZ = regionZ << 5;
                int fromX = Math.max(area.minChunkX(), baseChunkX);
                int fromZ = Math.max(area.minChunkZ(), baseChunkZ);
                int toX = Math.min(area.maxChunkX(), baseChunkX + REGION_CHUNKS - 1);
                int toZ = Math.min(area.maxChunkZ(), baseChunkZ + REGION_CHUNKS - 1);
                dispatched.add(dispatchUnload(plugin, world, baseChunkX, baseChunkZ,
                        fromX, fromZ, toX, toZ, envName));
            }
        }

        long deadline = System.currentTimeMillis() + UNLOAD_TIMEOUT_MILLIS;
        for (CompletableFuture<Boolean> done : dispatched) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                LOGGER.warn("等待卸载任务超时：{}", envName);
                return false;
            }
            try {
                if (!done.get(remaining, TimeUnit.MILLISECONDS)) {
                    return false;
                }
            } catch (Exception exception) {
                LOGGER.warn("等待卸载任务失败：{}", envName, exception);
                return false;
            }
        }

        // 轮询等待服务端在后续 tick 中真正完成卸载
        while (System.currentTimeMillis() < deadline) {
            if (!anyLoadedInArea(world, area)) {
                return true;
            }
            task.phase("等待卸载完成：" + envName);
            try {
                Thread.sleep(UNLOAD_POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // 本服务端项目自定义修改：在指定区域线程上卸载一个区块范围，返回完成信号
    private @NotNull CompletableFuture<Boolean> dispatchUnload(@NotNull Skyllia plugin, @NotNull World world,
                                                               int baseChunkX, int baseChunkZ,
                                                               int fromX, int fromZ, int toX, int toZ,
                                                               @NotNull String envName) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, baseChunkX, baseChunkZ, () -> {
                for (int chunkX = fromX; chunkX <= toX; chunkX++) {
                    for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
                        try {
                            if (world.isChunkLoaded(chunkX, chunkZ)) {
                                // save=false 丢弃内存态，避免未经恢复的旧数据被写回覆盖恢复结果
                                world.unloadChunk(chunkX, chunkZ, false);
                            }
                        } catch (RuntimeException ignored) {
                            // 交由校验阶段兜底
                        }
                    }
                }
                done.complete(true);
            });
        } catch (RuntimeException exception) {
            LOGGER.warn("提交卸载任务失败：{} 区域 {},{}", envName, baseChunkX >> 5, baseChunkZ >> 5, exception);
            done.complete(false);
        }
        return done;
    }

    private static boolean anyLoadedInArea(@NotNull World world, @NotNull IslandRestoreLock.LockedArea area) {
        for (int chunkX = area.minChunkX(); chunkX <= area.maxChunkX(); chunkX++) {
            for (int chunkZ = area.minChunkZ(); chunkZ <= area.maxChunkZ(); chunkZ++) {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 本服务端项目自定义修改：启动恢复期间的持续执法任务。
    // 由于 Lophine 的传送事件不可取消，玩家可能通过传送/末影珍珠/传送门闯入被锁区域，
    // 本任务周期性地：① 把被锁范围内的玩家弹出；② 重新卸载被锁范围内的区块（save=false）。
    // 这样即使有人闯入，该区域最多被加载极短时间，随后即被丢弃，不会产生方块更新、
    // 也不会把内存态写回覆盖恢复数据。
    private void startEnforcement(@NotNull Skyllia plugin) {
        if (enforcementTask != null && !enforcementTask.isCancelled()) {
            return;
        }
        try {
            enforcementTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                    task -> enforceTick(plugin), ENFORCE_PERIOD_SECONDS, ENFORCE_PERIOD_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException exception) {
            LOGGER.warn("无法启动恢复执法任务", exception);
        }
    }

    private void enforceTick(@NotNull Skyllia plugin) {
        if (lock.isEmpty()) {
            stopEnforcement();
            return;
        }
        for (IslandRestoreLock.LockedArea area : lock.lockedAreas()) {
            try {
                for (Player player : playersInArea(area)) {
                    PlayerUtils.teleportPlayerSpawn(player);
                }
                reUnloadArea(plugin, area);
            } catch (RuntimeException exception) {
                LOGGER.warn("恢复执法处理区域 {} 失败", area, exception);
            }
        }
    }

    private static @NotNull List<Player> playersInArea(@NotNull IslandRestoreLock.LockedArea area) {
        int minBlockX = area.minChunkX() << 4;
        int minBlockZ = area.minChunkZ() << 4;
        int maxBlockX = (area.maxChunkX() << 4) + 15;
        int maxBlockZ = (area.maxChunkZ() << 4) + 15;
        List<Player> inside = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(area.world()) || !player.isOnline()) {
                continue;
            }
            int bx = player.getLocation().getBlockX();
            int bz = player.getLocation().getBlockZ();
            if (bx >= minBlockX && bx <= maxBlockX && bz >= minBlockZ && bz <= maxBlockZ) {
                inside.add(player);
            }
        }
        return inside;
    }

    private void reUnloadArea(@NotNull Skyllia plugin, @NotNull IslandRestoreLock.LockedArea area) {
        if (!anyLoadedInArea(area.world(), area)) {
            return;
        }
        for (int regionX = area.minChunkX() >> 5; regionX <= (area.maxChunkX() >> 5); regionX++) {
            for (int regionZ = area.minChunkZ() >> 5; regionZ <= (area.maxChunkZ() >> 5); regionZ++) {
                int baseChunkX = regionX << 5;
                int baseChunkZ = regionZ << 5;
                int fromX = Math.max(area.minChunkX(), baseChunkX);
                int fromZ = Math.max(area.minChunkZ(), baseChunkZ);
                int toX = Math.min(area.maxChunkX(), baseChunkX + REGION_CHUNKS - 1);
                int toZ = Math.min(area.maxChunkZ(), baseChunkZ + REGION_CHUNKS - 1);
                dispatchUnload(plugin, area.world(), baseChunkX, baseChunkZ, fromX, fromZ, toX, toZ, "执法");
            }
        }
    }

    private void stopEnforcement() {
        ScheduledTask task = enforcementTask;
        if (task != null) {
            task.cancel();
            enforcementTask = null;
        }
    }

    // 本服务端项目自定义修改：写盘前的静置等待；返回 false 表示被中断（应终止恢复）
    private static boolean settleDelay() {
        try {
            Thread.sleep(SETTLE_DELAY_MILLIS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // 本服务端项目自定义修改：覆写前把覆盖到的活库区域文件快照到插件目录，作为安全网
    private void snapshotRegions(@NotNull Skyllia plugin, @NotNull World world,
                                 @NotNull List<RegionCoordinate> regions) {
        try {
            String liveFolder = SkylliaAPI.getWorldNMS().getDimensionFolder(world);
            if (liveFolder == null) {
                return;
            }
            Path liveDim = Paths.get(liveFolder);
            Path destRoot = plugin.getDataFolder().toPath()
                    .resolve("restore-snapshots")
                    .resolve(SNAPSHOT_FORMAT.format(LocalDateTime.now()))
                    .resolve(world.getName());
            for (RegionCoordinate rc : regions) {
                for (String category : new String[]{"region", "poi", "entities"}) {
                    Path source = liveDim.resolve(category).resolve("r." + rc.x() + "." + rc.z() + ".mca");
                    if (!Files.isRegularFile(source)) {
                        continue;
                    }
                    Path dest = destRoot.resolve(category).resolve(source.getFileName());
                    Files.createDirectories(dest.getParent());
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("快照区域文件失败（继续恢复）：{}", world.getName(), exception);
        }
    }

    // 本服务端项目自定义修改：计算备份中该世界的维度目录。
    // 由于备份布局可能把 backup-root 指向"世界文件夹"（如 restore/world），也可能指向
    // "世界文件夹的父目录"（如 restore），这里同时计算两种候选，取实际存在的那个，
    // 从而兼容两种备份组织方式；都不存在时回退到候选 A。
    private @NotNull Path computeBackupDimFolder(@NotNull Path backupRoot,
                                                 @NotNull World world, @NotNull String liveDimFolder) {
        WorldNMS nms = SkylliaAPI.getWorldNMS();
        Path liveDim = Paths.get(liveDimFolder).toAbsolutePath();

        // 本服务端项目自定义修改：世界根与服务端根均取自服务端 LevelStorageAccess（绝对路径），
        // 避免 World#getWorldFolder() 在某些服务端返回相对路径导致相对化结果为空。
        String worldFolderStr = nms.getWorldFolder(world);
        Path levelRoot = worldFolderStr != null
                ? Paths.get(worldFolderStr).toAbsolutePath()
                : liveDim.getParent();
        String serverRootStr = nms.getServerRoot(world);
        Path serverRoot = serverRootStr != null
                ? Paths.get(serverRootStr).toAbsolutePath()
                : levelRoot.getParent();

        // 维度目录相对世界根的路径（如 dimensions/minecraft/overworld）
        Path relative = relativize(levelRoot, liveDim);

        // 候选 A：backup-root 即世界文件夹（世界根 = 备份根）
        Path candidateA = backupRoot.resolve(relative);
        if (Files.isDirectory(candidateA.resolve("region"))) {
            return candidateA;
        }
        // 候选 B：backup-root 为服务端根（世界根 = 备份根/<世界文件夹名>）
        Path candidateB = backupRoot.resolve(levelRoot.getFileName().toString()).resolve(relative);
        if (Files.isDirectory(candidateB.resolve("region"))) {
            return candidateB;
        }
        // 都不存在时返回候选 A，并由上层安全阀给出明确错误
        return candidateA;
    }

    // 本服务端项目自定义修改：target 相对 from 的路径；from 不是 target 的祖先时回退为
    // "维度目录末两段 + target 末段"（标准布局 dimensions/<命名空间>/<维度>）。
    private static @NotNull Path relativize(@NotNull Path from, @NotNull Path target) {
        try {
            Path rel = from.relativize(target);
            if (!rel.toString().isEmpty() && !rel.isAbsolute()) {
                return rel;
            }
        } catch (IllegalArgumentException ignored) {
            // 不同根，走回退
        }
        int nameCount = target.getNameCount();
        if (nameCount >= 3) {
            Path tail = target.subpath(nameCount - 3, nameCount);
            if (tail.getName(0).toString().equals("dimensions")) {
                return tail;
            }
        }
        return target.getFileName();
    }

    private @NotNull Path resolveBackupRoot() {
        String raw = ConfigLoader.general.getRestoreSettings().backupRoot();
        Path path = Paths.get(raw);
        if (!path.isAbsolute()) {
            // 本服务端项目自定义修改：相对路径基于服务端根目录解析（而非 Craftbukkit 相对路径）
            Path base = null;
            for (World world : Bukkit.getWorlds()) {
                String root = SkylliaAPI.getWorldNMS().getServerRoot(world);
                if (root != null) {
                    base = Paths.get(root).toAbsolutePath();
                    break;
                }
            }
            if (base == null) {
                base = Bukkit.getWorldContainer().toPath().toAbsolutePath();
            }
            path = base.resolve(raw);
        }
        return path.toAbsolutePath().normalize();
    }

    private static @NotNull Map<World.Environment, World> findDimensionWorlds() {
        Map<World.Environment, World> worlds = new EnumMap<>(World.Environment.class);
        for (WorldConfig config : WorldUtils.getWorldConfigs()) {
            World world = config.getWorld();
            if (world != null) {
                worlds.putIfAbsent(config.getEnvironment(), world);
            }
        }
        return worlds;
    }

    // 本服务端项目自定义修改：某岛屿在其区域内的完整区块范围（32×32）
    private static @NotNull ChunkRect wholeRegion(@NotNull RegionCoordinate region) {
        int baseChunkX = region.x() << 5;
        int baseChunkZ = region.z() << 5;
        return new ChunkRect(baseChunkX, baseChunkZ,
                baseChunkX + REGION_CHUNKS - 1, baseChunkZ + REGION_CHUNKS - 1);
    }

    private static @NotNull String environmentName(@NotNull World.Environment environment) {
        return switch (environment) {
            case NORMAL -> "主世界";
            case NETHER -> "下界";
            case THE_END -> "末地";
            default -> environment.name();
        };
    }

    private static @NotNull String ownerName(@NotNull Island island) {
        var owner = island.getOwner();
        if (owner == null) {
            return "未知";
        }
        String name = owner.getLastKnowName();
        return (name == null || name.isBlank()) ? "未知" : name;
    }

    /**
     * 本服务端项目自定义修改：目标区块范围（含端点）。
     */
    public record ChunkRect(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

        public ChunkRect {
            if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
                throw new IllegalArgumentException("非法的区块范围");
            }
        }

        public int chunkCount() {
            return (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        }

        public boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minChunkX && chunkX <= maxChunkX
                    && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }

        /**
         * 返回该范围覆盖到的全部区域坐标。
         */
        public @NotNull List<RegionCoordinate> coveredRegions() {
            List<RegionCoordinate> regions = new ArrayList<>();
            for (int rx = minChunkX >> 5; rx <= (maxChunkX >> 5); rx++) {
                for (int rz = minChunkZ >> 5; rz <= (maxChunkZ >> 5); rz++) {
                    regions.add(new RegionCoordinate(rx, rz));
                }
            }
            return regions;
        }
    }

    /**
     * 本服务端项目自定义修改：恢复任务的实时状态。
     */
    public static final class RestoreTask {
        private final UUID islandId;
        private final String ownerName;
        private final int dimensions;
        private final long startedAt = System.currentTimeMillis();
        private final AtomicInteger completedWrites = new AtomicInteger();
        private volatile String phase = "准备中";
        private volatile int totalWrites;
        private volatile boolean done;
        private volatile boolean success;
        private volatile String error;
        private volatile long finishedAt;
        // 本服务端项目自定义修改：步骤文本实时回调（可为 null）
        private volatile java.util.function.Consumer<String> listener;

        RestoreTask(UUID islandId, String ownerName, int dimensions) {
            this.islandId = islandId;
            this.ownerName = ownerName;
            this.dimensions = dimensions;
        }

        void setListener(java.util.function.Consumer<String> listener) {
            this.listener = listener;
        }

        // 本服务端项目自定义修改：更新阶段并实时回调一次
        void phase(String value) {
            this.phase = value;
            report(value);
        }

        void markFailed(String message) {
            this.error = message;
            this.phase = "失败";
            report("失败：" + message);
        }

        void finish(boolean ok) {
            this.success = ok;
            this.done = true;
            this.finishedAt = System.currentTimeMillis();
            report(ok ? "恢复完成。" : "恢复结束（存在失败项）。");
        }

        private void report(String text) {
            java.util.function.Consumer<String> target = listener;
            if (target != null) {
                try {
                    target.accept(text);
                } catch (RuntimeException ignored) {
                    // 回调异常不应影响恢复流程
                }
            }
        }

        // 本服务端项目自定义修改：追加一条步骤文本（不改变阶段）
        void note(String text) {
            report(text);
        }

        // 本服务端项目自定义修改：每完成一个区块-类别单位的写入，计数并按步长回调一次进度
        void reportWrite() {
            int done = completedWrites.incrementAndGet();
            if (done % 256 == 0 || done == totalWrites) {
                report("写入进度：" + done + "/" + totalWrites + "（" + percent() + "%）");
            }
        }

        public UUID islandId() {
            return islandId;
        }

        public String ownerName() {
            return ownerName;
        }

        public int dimensions() {
            return dimensions;
        }

        public String phase() {
            return phase;
        }

        public int completedWrites() {
            return completedWrites.get();
        }

        public int totalWrites() {
            return totalWrites;
        }

        public boolean done() {
            return done;
        }

        public boolean success() {
            return success;
        }

        public @Nullable String error() {
            return error;
        }

        public long startedAt() {
            return startedAt;
        }

        public long finishedAt() {
            return finishedAt;
        }

        public int percent() {
            int total = totalWrites;
            return total <= 0 ? 0
                    : (int) Math.min(100L, (long) completedWrites.get() * 100L / total);
        }
    }
}
