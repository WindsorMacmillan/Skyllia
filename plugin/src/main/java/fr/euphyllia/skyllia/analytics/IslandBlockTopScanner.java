package fr.euphyllia.skyllia.analytics;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.utils.nms.WorldNMS;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本服务端项目自定义修改：光荣榜「方块类排名」的统计任务。
 *
 * <p>以单条后台线程（守护线程、最低优先级）按队列滚动遍历全部有效岛屿，对每个岛屿读取
 * 其所在区域（恰好 32×32 区块）在三个维度下的全部区块，使用
 * {@link WorldNMS#getCountAllBlocksInChunkFromDisk} 直接读盘统计、<b>不加载区块</b>，
 * 因而可在异步线程安全运行、内存占用极低。全量一轮通常需要数十分钟到一小时，
 * 因此结果按 {@link #PUBLISH_INTERVAL_MILLIS}（每小时）发布一次快照，
 * 恰好对应“每小时更新一次”的需求。</p>
 *
 * <p>本类为实例级、无静态可变状态，{@link #stop()} 会终止线程并清空结果，
 * 可安全随插件热重载创建/销毁。</p>
 */
public final class IslandBlockTopScanner {

    private static final Logger LOGGER = LogManager.getLogger(IslandBlockTopScanner.class);
    // 本服务端项目自定义修改：每个岛屿恰好占据一个 MC 区域，即 32×32 个区块
    private static final int REGION_CHUNKS = 32;
    private static final int TOP_SIZE = 10;
    // 本服务端项目自定义修改：结果发布间隔（1 小时）
    private static final long PUBLISH_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);
    // 本服务端项目自定义修改：无岛屿/无世界时的空转等待
    private static final long IDLE_SLEEP_MILLIS = TimeUnit.SECONDS.toMillis(30);
    // 本服务端项目自定义修改：停止时等待统计线程退出的最长时间
    private static final long STOP_JOIN_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final IslandBlockTopStore store;
    private final WorldNMS worldNms;
    private final AtomicBoolean running = new AtomicBoolean();
    private long lastPublishAt;
    private Thread worker;

    public IslandBlockTopScanner(Skyllia plugin, IslandBlockTopStore store) {
        this.store = store;
        this.worldNms = SkylliaAPI.getWorldNMS();
    }

    /**
     * 启动滚动统计线程。重复调用无效。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // 本服务端项目自定义修改：以启动时刻为基准，使首次发布在 1 小时后发生，
        // 避免冷启动时仅统计到个别岛屿就抢先发布
        lastPublishAt = System.currentTimeMillis();
        worker = new Thread(this::workerLoop, "skyllia-block-top-scanner");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        LOGGER.info("方块类排名统计已启动（滚动队列，每小时发布一次）");
    }

    /**
     * 停止统计线程并清空结果，供插件禁用/热重载时调用。
     * <p>先置停止标志并中断线程，再有界等待其退出（最长 {@link #STOP_JOIN_MILLIS}），
     * 之后才清空结果，避免与统计线程并发读写。线程为守护线程，即使超时未退出也不会阻止关服。</p>
     */
    public void stop() {
        running.set(false);
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(STOP_JOIN_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        // 本服务端项目自定义修改：把当前进度与已统计岛屿落盘，便于下次启动续跑
        store.save();
    }

    private void workerLoop() {
        while (running.get()) {
            try {
                runCycle();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                LOGGER.error("方块类排名统计发生异常，稍后重试", exception);
                if (!sleep(IDLE_SLEEP_MILLIS)) {
                    return;
                }
            }
        }
    }

    private void runCycle() throws InterruptedException {
        Map<World.Environment, World> worlds = resolveWorlds();
        List<Island> islands = SkylliaAPI.getAllIslandsValid();
        if (worlds.isEmpty() || islands.isEmpty()) {
            sleep(IDLE_SLEEP_MILLIS);
            return;
        }

        // 本服务端项目自定义修改：剔除已被删除的岛屿，避免其残留在排名中
        Set<UUID> validIds = new HashSet<>(islands.size());
        for (Island island : islands) {
            if (island != null) {
                validIds.add(island.getId());
            }
        }
        store.retainTallies(validIds);

        // 本服务端项目自定义修改：若上一轮未跑完，继续沿用其进度续跑（跨重启/热重载）；
        // 否则开启新一轮。已完成本轮统计的岛屿会被跳过，无需重复读盘。
        IslandBlockTopStore.Progress progress = store.progress();
        boolean resume = !progress.complete() && progress.total() > 0;
        if (!resume) {
            store.beginCycle(islands.size());
        }
        Set<UUID> done = resume ? store.tallies().keySet() : Set.of();

        for (Island island : islands) {
            if (!running.get()) {
                return;
            }
            if (island == null) {
                continue;
            }
            if (done.contains(island.getId())) {
                continue;
            }
            try {
                IslandBlockTally tally = scanIsland(island, worlds);
                if (tally != null) {
                    store.putTally(new IslandBlockTopStore.Tally(
                            tally.islandId(), tally.ownerName(), tally.totals()));
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("统计岛屿 {} 的方块数量失败", island.getId(), exception);
            }
            publishIfDue();
        }
        store.finishCycle();
        publishIfDue();
    }

    private @Nullable IslandBlockTally scanIsland(Island island, Map<World.Environment, World> worlds) {
        long[] totals = new long[IslandBlockTopStore.Category.values().length];
        RegionCoordinate region = island.getRegionCoordinate();
        // 本服务端项目自定义修改：直接遍历该岛屿所在区域的 32×32 区块。
        // 一个区域只归属一个岛屿，因此不会读到相邻岛屿的方块。
        int baseChunkX = region.x() << 5;
        int baseChunkZ = region.z() << 5;
        for (World world : worlds.values()) {
            for (int dx = 0; dx < REGION_CHUNKS; dx++) {
                int chunkX = baseChunkX + dx;
                for (int dz = 0; dz < REGION_CHUNKS; dz++) {
                    if (!running.get()) {
                        return null;
                    }
                    Map<Material, Integer> counts;
                    try {
                        counts = worldNms.getCountAllBlocksInChunkFromDisk(world, chunkX, baseChunkZ + dz);
                    } catch (RuntimeException exception) {
                        // 单个区块读取失败不应中断整岛统计
                        continue;
                    }
                    if (counts.isEmpty()) {
                        continue;
                    }
                    for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
                        IslandBlockTopStore.Category category = IslandBlockTopStore.categoryOf(entry.getKey());
                        if (category != null) {
                            totals[category.ordinal()] += entry.getValue();
                        }
                    }
                }
            }
        }
        return new IslandBlockTally(island.getId(), ownerName(island), totals);
    }

    private void publishIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastPublishAt < PUBLISH_INTERVAL_MILLIS) {
            return;
        }
        lastPublishAt = now;
        Map<UUID, IslandBlockTopStore.Tally> tallies = store.tallies();
        Map<IslandBlockTopStore.Category, List<IslandBlockTopStore.Ranking>> rankings = buildRankings(tallies);
        store.publish(rankings);
        int total = rankings.values().stream().mapToInt(List::size).sum();
        LOGGER.info("方块类排名已更新，共 {} 条记录（覆盖 {} 个岛屿）", total, tallies.size());
    }

    private Map<IslandBlockTopStore.Category, List<IslandBlockTopStore.Ranking>> buildRankings(
            @NotNull Map<UUID, IslandBlockTopStore.Tally> tallies) {
        List<IslandBlockTopStore.Tally> values = new ArrayList<>(tallies.values());
        Map<IslandBlockTopStore.Category, List<IslandBlockTopStore.Ranking>> result =
                new EnumMap<>(IslandBlockTopStore.Category.class);
        for (IslandBlockTopStore.Category category : IslandBlockTopStore.Category.values()) {
            int index = category.ordinal();
            List<IslandBlockTopStore.Ranking> rankings = values.stream()
                    .filter(tally -> tally.totals()[index] > 0L)
                    .sorted(Comparator
                            .comparingLong((IslandBlockTopStore.Tally tally) -> tally.totals()[index]).reversed()
                            .thenComparing(IslandBlockTopStore.Tally::ownerName, String.CASE_INSENSITIVE_ORDER))
                    .limit(TOP_SIZE)
                    .map(tally -> new IslandBlockTopStore.Ranking(
                            tally.islandId(), tally.ownerName(), tally.totals()[index]))
                    .toList();
            result.put(category, rankings);
        }
        return result;
    }

    private static Map<World.Environment, World> resolveWorlds() {
        Map<World.Environment, World> worlds = new EnumMap<>(World.Environment.class);
        for (WorldConfig config : WorldUtils.getWorldConfigs()) {
            World world = config.getWorld();
            if (world != null) {
                worlds.putIfAbsent(config.getEnvironment(), world);
            }
        }
        return worlds;
    }

    private static String ownerName(Island island) {
        Players owner = island.getOwner();
        if (owner == null) {
            return "未知";
        }
        String name = owner.getLastKnowName();
        return (name == null || name.isBlank()) ? "未知" : name;
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // 本服务端项目自定义修改：单个岛屿的统计结果（各类别方块总量）
    private record IslandBlockTally(UUID islandId, String ownerName, long[] totals) {
    }
}
