package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.analytics.IslandBlockTopStore;
import fr.euphyllia.skyllia.analytics.IslandLagAttribution;
import fr.euphyllia.skyllia.analytics.IslandLagProfileStore;
import fr.euphyllia.skyllia.analytics.LagWeights;
import fr.euphyllia.skyllia.analytics.RegionLagSample;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.gui.IslandTopGui;
import fr.euphyllia.skyllia.utils.WorldUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Read-only island lag ranking. Samples are collected from the current tick
 * regions and stored so entity/chunk workload weights can be fitted over time.
 *
 * <p>本服务端项目自定义修改：数据刷新不再由玩家执行命令触发，而是由单一的
 * 30 秒轮询任务驱动。轮询一次扫描的结果会分发给所有当前打开了光荣榜窗口的
 * 玩家会话（{@link #viewers}），从而实现多窗口同步更新。为提高响应速度，
 * 玩家打开窗口时也会立即触发一次刷新。</p>
 */
public final class TopSubCommand implements SubCommandInterface {

    private static final Logger LOGGER = LogManager.getLogger(TopSubCommand.class);
    private static final int MAX_RANKING_ENTRIES = 10;
    // 本服务端项目自定义修改：轮询周期（秒）
    private static final long POLL_PERIOD_SECONDS = 30L;
    // 本服务端项目自定义修改：异常判定阈值（判定顺序见 #abnormalTypes）
    private static final long ABNORMAL_ITEM_THRESHOLD = 1000L;
    private static final long ABNORMAL_TNT_THRESHOLD = 1000L;
    private static final long ABNORMAL_WITHER_SKULL_THRESHOLD = 1000L;
    private static final long ABNORMAL_FALLING_BLOCK_THRESHOLD = 1000L;
    private static final long ABNORMAL_BOAT_THRESHOLD = 200L;
    private static final long ABNORMAL_ENTITY_TOTAL_THRESHOLD = 3000L;
    // 本服务端项目自定义修改：异常告警间隔（10 分钟）。
    // 有人看榜时扫描为 30 秒/次，约 20 次扫描触发一次告警；无人看榜时为
    // 10 分钟/次，则每次扫描触发一次，均保持约 10 分钟的告警节奏。
    private static final long ALERT_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    // 本服务端项目自定义修改：无人看榜时仍以该间隔扫描一次，确保异常告警始终在线
    private static final long IDLE_SCAN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    // 本服务端项目自定义修改：在线管理员告警的额外权限节点（除 OP 外）
    private static final String ALERT_PERMISSION = "skyllia.island.top.alerts";
    // 本服务端项目自定义修改：进度展示用的时间格式
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    // 本服务端项目自定义修改：所有类型船（含带箱船与竹筏），用于“疑似滥用船吸”判定。
    // 通过 Boat 实体类动态识别，避免因版本新增船型（如 poplar）而漏判。
    private static final Set<EntityType> BOAT_TYPES = buildBoatTypes();

    private static @NotNull Set<EntityType> buildBoatTypes() {
        Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        for (EntityType type : EntityType.values()) {
            try {
                Class<? extends Entity> entityClass = type.getEntityClass();
                if (entityClass != null && Boat.class.isAssignableFrom(entityClass)) {
                    types.add(type);
                }
            } catch (RuntimeException ignored) {
                // 少数类型（如 UNKNOWN/LEGACY）不提供实体类，忽略即可
            }
        }
        return types;
    }
    private static final World.Environment[] DIMENSIONS = {
            World.Environment.NORMAL,
            World.Environment.NETHER,
            World.Environment.THE_END
    };

    private final IslandLagProfileStore profileStore;
    // 本服务端项目自定义修改：方块类排名的结果存储，由 IslandBlockTopScanner 每小时更新
    private final IslandBlockTopStore blockTopStore;
    private final AtomicBoolean queryRunning = new AtomicBoolean();
    // 本服务端项目自定义修改：当前打开了光荣榜窗口的玩家会话（UUID -> Player）
    private final Map<UUID, Player> viewers = new ConcurrentHashMap<>();
    // 本服务端项目自定义修改：轮询任务是否已启动
    private final AtomicBoolean polling = new AtomicBoolean();
    // 本服务端项目自定义修改：最近一次扫描结果，供新打开的窗口立即展示
    private volatile Snapshot lastSnapshot;
    // 本服务端项目自定义修改：最近一次触发异常告警的时间戳，用于按时间控制告警节奏
    private final AtomicLong lastAlertAt = new AtomicLong();
    // 本服务端项目自定义修改：最近一次扫描发起的时间戳，用于在无人看榜时按 10 分钟节流
    private final AtomicLong lastScanAt = new AtomicLong();

    public TopSubCommand(@NotNull Skyllia plugin, @NotNull IslandBlockTopStore blockTopStore) {
        Path profileFile = plugin.getDataFolder().toPath()
                .resolve("analytics")
                .resolve("island-top-profile.json");
        this.profileStore = new IslandLagProfileStore(profileFile);
        this.blockTopStore = blockTopStore;
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(plugin instanceof Skyllia skyllia) || !(sender instanceof Player viewer)) {
            return;
        }
        // 本服务端项目自定义修改：/is top progress 只查询当前方块类排名扫描进度
        if (args.length >= 1 && args[0].equalsIgnoreCase("progress")) {
            sendProgress(skyllia, viewer);
            return;
        }
        // 本服务端项目自定义修改：注册窗口会话并立即打开一次（使用最近一次扫描结果），
        // 随后交给 30 秒轮询任务持续刷新。
        UUID viewerId = viewer.getUniqueId();
        startPolling(skyllia);
        Snapshot snapshot = lastSnapshot;
        try {
            // 注册与打开在同一线程（玩家所在线程）内完成，避免与轮询清理发生竞态
            boolean scheduled = viewer.getScheduler().execute(skyllia, () -> {
                viewers.put(viewerId, viewer);
                openGui(viewer, snapshot);
                // 本服务端项目自定义修改：打开窗口时一并提示方块类排名的扫描进度
                viewer.sendMessage(buildProgressMessage());
            }, () -> viewers.remove(viewerId, viewer), 1L);
            if (!scheduled) {
                viewers.remove(viewerId, viewer);
            }
        } catch (RuntimeException exception) {
            viewers.remove(viewerId, viewer);
            LOGGER.warn("Cannot open island-top inventory for {}", viewer.getName(), exception);
        }
        // 本服务端项目自定义修改：仅在尚无任何快照（冷启动首次打开）时补一次扫描，
        // 避免窗口长时间空白；其余刷新一律交给 30 秒轮询，不再由命令触发。
        if (lastSnapshot == null) {
            startScan(skyllia);
        }
    }

    // 本服务端项目自定义修改：启动全局 30 秒轮询任务。
    // 公开以便插件启动时调用，使异常告警在无人使用命令时也始终在线。
    public void startPolling(@NotNull Skyllia skyllia) {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runAtFixedRate(skyllia,
                    task -> pollTick(skyllia),
                    POLL_PERIOD_SECONDS, POLL_PERIOD_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException exception) {
            polling.set(false);
            LOGGER.error("Cannot start island-top polling task", exception);
        }
    }

    // 本服务端项目自定义修改：轮询回调。
    // 有人看榜时每次轮询（30 秒）都扫描以刷新窗口；无人看榜时按 10 分钟节流扫描，
    // 保证异常告警始终在线且不会带来持续的全服区块遍历开销。
    private void pollTick(@NotNull Skyllia skyllia) {
        if (!skyllia.isEnabled()) {
            viewers.clear();
            return;
        }
        if (!viewers.isEmpty()) {
            startScan(skyllia);
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastScanAt.get();
        if (now - last >= IDLE_SCAN_INTERVAL_MILLIS && lastScanAt.compareAndSet(last, now)) {
            startScan(skyllia);
        }
    }

    private void openGui(@NotNull Player viewer, @Nullable Snapshot snapshot) {
        // 本服务端项目自定义修改：方块类排名直接读取最新的存储快照
        Map<IslandBlockTopStore.Category, List<IslandBlockTopStore.Ranking>> blockTop = blockTopStore.snapshot();
        if (snapshot == null) {
            viewer.openInventory(IslandTopGui.create(List.of(), new boolean[DIMENSIONS.length], List.of(), blockTop));
            return;
        }
        viewer.openInventory(IslandTopGui.create(
                snapshot.entries(), snapshot.loadedDimensions(), snapshot.abnormal(), blockTop));
    }

    // 本服务端项目自定义修改：查询并展示方块类排名的扫描进度
    private void sendProgress(@NotNull Skyllia skyllia, @NotNull Player viewer) {
        Component message = buildProgressMessage();
        try {
            boolean scheduled = viewer.getScheduler().execute(skyllia,
                    () -> viewer.sendMessage(message), null, 1L);
            if (!scheduled) {
                viewer.sendMessage(message);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Cannot send island-top progress to {}", viewer.getName(), exception);
        }
    }

    // 本服务端项目自定义修改：构建方块类排名扫描进度文本
    private @NotNull Component buildProgressMessage() {
        IslandBlockTopStore.Progress progress = blockTopStore.progress();
        Component message = Component.text("[光荣榜] ", NamedTextColor.RED)
                .append(Component.text("方块类排名扫描进度：", NamedTextColor.GRAY))
                .append(Component.text(progress.scanned() + "/" + progress.total(), NamedTextColor.YELLOW))
                .append(Component.text("（" + progress.percent() + "%）", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("状态：", NamedTextColor.GRAY))
                .append(progress.complete()
                        ? Component.text("本轮已完成", NamedTextColor.GREEN)
                        : Component.text("本轮进行中", NamedTextColor.GOLD));
        if (progress.startedAt() > 0L) {
            message = message
                    .append(Component.text("，已用时 ", NamedTextColor.GRAY))
                    .append(Component.text(formatDuration(System.currentTimeMillis() - progress.startedAt()),
                            NamedTextColor.AQUA));
        }
        if (progress.publishedAt() > 0L) {
            message = message
                    .append(Component.newline())
                    .append(Component.text("上次更新时间：", NamedTextColor.GRAY))
                    .append(Component.text(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(progress.publishedAt())),
                            NamedTextColor.AQUA));
        }
        return message;
    }

    // 本服务端项目自定义修改：毫秒转“x天x小时x分x秒”
    private static @NotNull String formatDuration(long millis) {
        long safe = Math.max(0L, millis);
        long days = safe / TimeUnit.DAYS.toMillis(1);
        long hours = (safe % TimeUnit.DAYS.toMillis(1)) / TimeUnit.HOURS.toMillis(1);
        long minutes = (safe % TimeUnit.HOURS.toMillis(1)) / TimeUnit.MINUTES.toMillis(1);
        long seconds = (safe % TimeUnit.MINUTES.toMillis(1)) / TimeUnit.SECONDS.toMillis(1);
        if (days > 0L) {
            return days + "天" + hours + "小时";
        }
        if (hours > 0L) {
            return hours + "小时" + minutes + "分";
        }
        if (minutes > 0L) {
            return minutes + "分" + seconds + "秒";
        }
        return seconds + "秒";
    }

    private void startScan(@NotNull Skyllia skyllia) {
        if (!queryRunning.compareAndSet(false, true)) {
            return;
        }
        // 本服务端项目自定义修改：记录扫描发起时间，供无人看榜时的 10 分钟节流判断
        lastScanAt.set(System.currentTimeMillis());

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        QuerySession session = new QuerySession(skyllia);
        if (players.isEmpty()) {
            prepareSession(session);
            return;
        }
        AtomicInteger remainingPlayers = new AtomicInteger(players.size());
        Runnable playerComplete = () -> {
            if (remainingPlayers.decrementAndGet() == 0) {
                prepareSession(session);
            }
        };

        for (Player player : players) {
            try {
                boolean scheduled = player.getScheduler().execute(skyllia,
                        () -> samplePlayer(session, player, playerComplete),
                        playerComplete, 1L);
                if (!scheduled) {
                    playerComplete.run();
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Cannot schedule island-top player sample for {}", player.getName(), exception);
                playerComplete.run();
            }
        }
    }

    private void samplePlayer(@NotNull QuerySession session, @NotNull Player player,
                              @NotNull Runnable completion) {
        try {
            Location location = player.getLocation().clone();
            World world = location.getWorld();
            if (world == null || !WorldUtils.isWorldSkyblock(world.getName())) {
                return;
            }

            RuntimeRegionKey region = runtimeRegionKey(location);
            if (region == null) {
                return;
            }

            RegionCoordinate skylliaRegion = RegionHelper.getRegionCoordinateFromChunk(
                    location.getBlockX() >> 4, location.getBlockZ() >> 4);
            session.rawPlayers.add(new RawPlayerSample(
                    player.getUniqueId(),
                    player.getName(),
                    player.isOp(),
                    location,
                    skylliaRegion,
                    region
            ));
        } catch (RuntimeException exception) {
            LOGGER.warn("Cannot sample island-top player {}", player.getName(), exception);
        } finally {
            completion.run();
        }
    }

    private void prepareSession(@NotNull QuerySession session) {
        if (!session.isActive()) {
            finishQuery(session);
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runNow(session.plugin, task -> {
                try {
                    prepareIslands(session);
                    startWorldScans(session);
                } catch (RuntimeException exception) {
                    LOGGER.error("Cannot prepare island-top query", exception);
                    finishQuery(session);
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.error("Cannot start island-top preparation", exception);
            finishQuery(session);
        }
    }

    private void prepareIslands(@NotNull QuerySession session) {
        List<Island> allIslands = SkylliaAPI.getAllIslandsValid();
        Map<UUID, IslandMeta> islandsById = new HashMap<>(allIslands.size());
        Map<RegionCoordinate, IslandMeta> islandsByRegion = new HashMap<>(allIslands.size());

        for (Island island : allIslands) {
            UUID islandId = island.getId();
            Players owner = island.getOwner();
            IslandMeta meta = new IslandMeta(
                    islandId,
                    island.getRegionCoordinate(),
                    island.getSize(),
                    owner == null ? null : owner.getMojangId(),
                    owner == null || owner.getLastKnowName() == null ? "未知" : owner.getLastKnowName()
            );
            islandsById.put(islandId, meta);
            islandsByRegion.putIfAbsent(meta.region(), meta);
        }

        session.islandsById.putAll(islandsById);
        session.islandsByRegion.putAll(islandsByRegion);
        for (RawPlayerSample raw : session.rawPlayers) {
            IslandMeta island = islandsByRegion.get(raw.skylliaRegion());
            if (island == null || !isInside(island, raw.location())) {
                continue;
            }
            ValidPlayerSample sample = new ValidPlayerSample(
                    raw.playerId(), raw.playerName(), raw.op(), island.id(),
                    raw.region()
            );
            session.validPlayers.add(sample);
            session.collector.addPlayerSample(sample);
        }
        session.worlds.putAll(findDimensionWorlds());
        for (int index = 0; index < DIMENSIONS.length; index++) {
            session.loadedDimensions[index] = session.worlds.get(DIMENSIONS[index]) != null;
        }
    }

    private static boolean isInside(@NotNull IslandMeta island, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        Location center = RegionHelper.getCenterRegion(world, island.region().x(), island.region().z());
        double halfSize = (island.size() - 1.0D) / 2.0D;
        return location.getBlockX() >= Math.floor(center.getBlockX() - halfSize)
                && location.getBlockX() <= Math.floor(center.getBlockX() + halfSize)
                && location.getBlockZ() >= Math.floor(center.getBlockZ() - halfSize)
                && location.getBlockZ() <= Math.floor(center.getBlockZ() + halfSize)
                && location.getBlockY() >= world.getMinHeight()
                && location.getBlockY() < world.getMaxHeight();
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

    private void startWorldScans(@NotNull QuerySession session) {
        if (!session.isActive()) {
            finishQuery(session);
            return;
        }
        List<ChunkRef> chunks = new ArrayList<>();
        for (World world : session.worlds.values()) {
            try {
                for (Chunk chunk : world.getLoadedChunks()) {
                    chunks.add(new ChunkRef(world, chunk.getX(), chunk.getZ()));
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Cannot snapshot loaded chunks for island-top world {}", world.getName(), exception);
            }
        }

        int totalChunks = chunks.size();
        if (totalChunks == 0) {
            finishSession(session);
            return;
        }

        AtomicInteger remaining = new AtomicInteger(totalChunks);
        for (ChunkRef chunk : chunks) {
            try {
                Bukkit.getRegionScheduler().execute(session.plugin, chunk.world(), chunk.x(), chunk.z(), () -> {
                    try {
                        if (session.isActive()) {
                            collectChunk(session, chunk);
                        }
                    } catch (RuntimeException exception) {
                        LOGGER.warn("Cannot collect island-top chunk {} {} {}",
                                chunk.world().getName(), chunk.x(), chunk.z(), exception);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            finishSession(session);
                        }
                    }
                });
            } catch (RuntimeException exception) {
                LOGGER.warn("Cannot schedule island-top chunk {} {} {}",
                        chunk.world().getName(), chunk.x(), chunk.z(), exception);
                if (remaining.decrementAndGet() == 0) {
                    finishSession(session);
                }
            }
        }
    }

    private void collectChunk(@NotNull QuerySession session, @NotNull ChunkRef chunkRef) {
        World world = chunkRef.world();
        if (!world.isChunkLoaded(chunkRef.x(), chunkRef.z())) {
            return;
        }

        Chunk chunk = world.getChunkAt(chunkRef.x(), chunkRef.z(), false);
        if (!chunk.isLoaded()) {
            return;
        }

        Location chunkLocation = new Location(world,
                (chunkRef.x() << 4) + 8, world.getMinHeight(), (chunkRef.z() << 4) + 8);
        RuntimeRegionKey regionKey = runtimeRegionKey(chunkLocation);
        if (regionKey == null) {
            return;
        }

        RegionObservation region = session.collector.regions.get(regionKey);
        if (region == null) {
            region = session.collector.region(regionKey);
        }
        region.recordMspt(Bukkit.getAverageTickTime());
        region.addChunk();

        RegionCoordinate islandRegion = RegionHelper.getRegionCoordinateFromChunk(chunkRef.x(), chunkRef.z());
        IslandMeta island = session.islandsByRegion.get(islandRegion);
        IslandObservation islandObservation = null;
        IslandObservation regionIslandObservation = null;
        IslandBounds islandBounds = null;
        if (island != null) {
            IslandDimensionKey islandKey = new IslandDimensionKey(island.id(), world.getEnvironment());
            islandBounds = session.islandBounds.computeIfAbsent(islandKey,
                    ignored -> IslandBounds.from(island, world));
            islandObservation = session.collector.island(islandKey);
            if (islandBounds.overlapsChunk(chunkRef.x(), chunkRef.z())) {
                islandObservation.addChunk();
                regionIslandObservation = session.collector.regionIsland(regionKey, islandKey);
                regionIslandObservation.addChunk();
            }
        }

        for (Entity entity : chunk.getEntities()) {
            EntityType type = entity.getType();
            if (type == EntityType.PLAYER) {
                continue;
            }
            if (region != null) {
                region.addEntity(type);
            }
            if (islandObservation != null && islandBounds.contains(entity.getLocation())) {
                islandObservation.addEntity(type);
                regionIslandObservation.addEntity(type);
            }
        }
    }

    private void finishSession(@NotNull QuerySession session) {
        if (!session.finishing.compareAndSet(false, true)) {
            return;
        }
        if (!session.isActive()) {
            finishQuery(session);
            return;
        }

        try {
            Bukkit.getAsyncScheduler().runNow(session.plugin, task -> {
                try {
                    if (!session.isActive()) {
                        finishQuery(session);
                        return;
                    }
                    LagWeights weights = profileStore.recordSamplesAndFit(
                            session.collector.createSamples(System.currentTimeMillis()));
                    // 本服务端项目自定义修改：同时产出排行榜条目与数据异常岛屿列表
                    ScanResult result = buildEntries(session, weights);
                    Snapshot snapshot = new Snapshot(
                            result.entries(), session.loadedDimensions.clone(), result.abnormal());
                    lastSnapshot = snapshot;
                    broadcastSnapshot(session.plugin, snapshot);
                    // 本服务端项目自定义修改：按时间节流告警（约每 10 分钟一次），
                    // 若存在异常岛屿则向在线管理员告警并打印到控制台
                    maybeAlertAbnormal(session.plugin, snapshot.abnormal());
                } catch (RuntimeException exception) {
                    LOGGER.error("Cannot finish island-top query", exception);
                } finally {
                    finishQuery(session);
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.error("Cannot schedule island-top result", exception);
            finishQuery(session);
        }
    }

    // 本服务端项目自定义修改：把最新快照分发给所有打开了光荣榜窗口的会话。
    // 若玩家已离线或已关闭窗口，则将其从会话表中移除，避免残留引用。
    private void broadcastSnapshot(@NotNull Skyllia skyllia, @NotNull Snapshot snapshot) {
        if (viewers.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Player> entry : viewers.entrySet()) {
            Player viewer = entry.getValue();
            if (viewer == null || !viewer.isOnline()) {
                viewers.remove(entry.getKey(), viewer);
                continue;
            }
            try {
                boolean scheduled = viewer.getScheduler().execute(skyllia, () -> {
                    if (!viewer.isOnline() || !IslandTopGui.isIslandTopInventory(
                            viewer.getOpenInventory().getTopInventory())) {
                        // 窗口已关闭：停止对该会话的轮询更新
                        viewers.remove(entry.getKey(), viewer);
                        return;
                    }
                    // 本服务端项目自定义修改：刷新时一并带上最新的方块类排名
                    viewer.getOpenInventory().getTopInventory()
                            .setContents(IslandTopGui.create(
                                    snapshot.entries(), snapshot.loadedDimensions(),
                                    snapshot.abnormal(), blockTopStore.snapshot()).getContents());
                }, () -> viewers.remove(entry.getKey(), viewer), 1L);
                if (!scheduled) {
                    viewers.remove(entry.getKey(), viewer);
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Cannot refresh island-top inventory for {}", viewer.getName(), exception);
            }
        }
    }

    // 本服务端项目自定义修改：按 ALERT_INTERVAL_MILLIS 节流触发异常告警。
    // 无异常岛屿时不消耗告警节流窗口，保证异常出现后尽快（下一次扫描）即可告警。
    private void maybeAlertAbnormal(@NotNull Skyllia skyllia,
                                    @NotNull List<IslandTopGui.Entry> abnormal) {
        if (abnormal.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastAlertAt.get();
        if (now - last < ALERT_INTERVAL_MILLIS || !lastAlertAt.compareAndSet(last, now)) {
            return;
        }
        alertAbnormalIslands(skyllia, abnormal);
    }

    // 本服务端项目自定义修改：向在线管理员发送异常岛屿告警，并打印到控制台。
    // 运行于异步线程，玩家消息通过各自 EntityScheduler 派发到对应线程执行。
    private void alertAbnormalIslands(@NotNull Skyllia skyllia,
                                      @NotNull List<IslandTopGui.Entry> abnormal) {
        if (abnormal.isEmpty()) {
            return;
        }
        Component message = buildAlertMessage(abnormal);
        LOGGER.warn("检测到 {} 个数据异常岛屿：{}", abnormal.size(), formatAlertPlain(abnormal));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.isOp() && !online.hasPermission(ALERT_PERMISSION)) {
                continue;
            }
            try {
                online.getScheduler().execute(skyllia,
                        () -> online.sendMessage(message), null, 1L);
            } catch (RuntimeException exception) {
                LOGGER.warn("Cannot send island-top alert to {}", online.getName(), exception);
            }
        }
    }

    private static @NotNull Component buildAlertMessage(@NotNull List<IslandTopGui.Entry> abnormal) {
        Component message = Component.text("[光荣榜] 数据异常岛屿告警：", NamedTextColor.DARK_RED);
        for (IslandTopGui.Entry entry : abnormal) {
            message = message.append(Component.newline())
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text(entry.ownerName(), NamedTextColor.YELLOW))
                    .append(Component.text("：", NamedTextColor.GRAY))
                    .append(Component.text(String.join(" ", entry.abnormalTypes()), NamedTextColor.DARK_RED));
        }
        return message;
    }

    private static @NotNull String formatAlertPlain(@NotNull List<IslandTopGui.Entry> abnormal) {
        StringBuilder builder = new StringBuilder();
        for (IslandTopGui.Entry entry : abnormal) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.ownerName())
                    .append('[')
                    .append(String.join(" ", entry.abnormalTypes()))
                    .append(']');
        }
        return builder.toString();
    }

    private void finishQuery(@NotNull QuerySession session) {
        queryRunning.set(false);
    }

    private @NotNull ScanResult buildEntries(@NotNull QuerySession session,
                                             @NotNull LagWeights weights) {
        Map<IslandDimensionKey, Double> estimates = estimateIslandMspt(session, weights);
        List<IslandTopGui.Entry> entries = new ArrayList<>(session.islandsById.size());
        List<IslandTopGui.Entry> abnormal = new ArrayList<>();
        for (IslandMeta island : session.islandsById.values()) {
            double[] mspt = new double[DIMENSIONS.length];
            long entityTotal = 0L;
            Map<EntityType, Long> combinedEntities = new HashMap<>();
            for (int index = 0; index < DIMENSIONS.length; index++) {
                IslandDimensionKey key = new IslandDimensionKey(island.id(), DIMENSIONS[index]);
                mspt[index] = estimates.getOrDefault(key, 0.0D);
                IslandObservation observation = session.collector.islands.get(key);
                if (observation == null) {
                    continue;
                }
                for (Map.Entry<EntityType, LongAdder> entry : observation.entityCounts.entrySet()) {
                    long count = entry.getValue().sum();
                    if (count <= 0L) {
                        continue;
                    }
                    entityTotal += count;
                    combinedEntities.merge(entry.getKey(), count, Long::sum);
                }
            }

            // No loaded non-player entities means this island has no observable
            // workload in the current snapshot and must not occupy a ranking slot.
            if (entityTotal == 0L) {
                continue;
            }

            // 本服务端项目自定义修改：按既定顺序判定异常状态，命中即记录
            List<String> abnormalTypes = abnormalTypes(combinedEntities, entityTotal);
            List<String> players = playersForIsland(session, island.id());
            List<IslandTopGui.EntityCount> topEntities = combinedEntities.entrySet().stream()
                    .sorted(Map.Entry.<EntityType, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(entry -> entry.getKey().translationKey()))
                    .limit(20L)
                    .map(entry -> new IslandTopGui.EntityCount(entry.getKey(), entry.getValue()))
                    .toList();
            IslandTopGui.Entry built = new IslandTopGui.Entry(
                    island.id(), island.ownerId(), island.ownerName(), players,
                    mspt, entityTotal, topEntities, abnormalTypes
            );
            entries.add(built);
            // 本服务端项目自定义修改：命中任一异常状态即视为数据异常岛屿，单独展示
            if (!abnormalTypes.isEmpty()) {
                abnormal.add(built);
            }
        }

        entries.sort(Comparator
                .comparingDouble((IslandTopGui.Entry entry) -> lagScore(entry.mspt())).reversed()
                .thenComparing(Comparator.comparingLong(IslandTopGui.Entry::entityTotal).reversed())
                .thenComparing(IslandTopGui.Entry::ownerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.islandId().toString()));
        abnormal.sort(Comparator
                .comparingLong(IslandTopGui.Entry::entityTotal).reversed()
                .thenComparing(IslandTopGui.Entry::ownerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.islandId().toString()));
        return new ScanResult(
                List.copyOf(entries.subList(0, Math.min(MAX_RANKING_ENTRIES, entries.size()))),
                List.copyOf(abnormal));
    }

    // 本服务端项目自定义修改：按需求既定顺序判定岛屿异常状态（从上到下），
    // 一个岛屿可同时命中多个异常。返回值为展示用文案，顺序即判定顺序。
    private static @NotNull List<String> abnormalTypes(@NotNull Map<EntityType, Long> entities,
                                                       long entityTotal) {
        List<String> types = new ArrayList<>(4);
        if (entities.getOrDefault(EntityType.ITEM, 0L) > ABNORMAL_ITEM_THRESHOLD) {
            types.add("疑似掉落物未销毁");
        }
        if (entities.getOrDefault(EntityType.TNT, 0L) > ABNORMAL_TNT_THRESHOLD) {
            types.add("疑似弱加载TNT复制");
        }
        if (entities.getOrDefault(EntityType.WITHER_SKULL, 0L) > ABNORMAL_WITHER_SKULL_THRESHOLD) {
            types.add("疑似弱加载凋零头");
        }
        if (entities.getOrDefault(EntityType.FALLING_BLOCK, 0L) > ABNORMAL_FALLING_BLOCK_THRESHOLD) {
            types.add("疑似未关闭刷沙机");
        }
        long boats = 0L;
        for (EntityType boatType : BOAT_TYPES) {
            boats += entities.getOrDefault(boatType, 0L);
        }
        if (boats > ABNORMAL_BOAT_THRESHOLD) {
            types.add("疑似滥用船吸");
        }
        if (entityTotal > ABNORMAL_ENTITY_TOTAL_THRESHOLD) {
            types.add("岛屿实体过多");
        }
        return List.copyOf(types);
    }

    private static @NotNull List<String> playersForIsland(@NotNull QuerySession session,
                                                           @NotNull UUID islandId) {
        Set<String> names = new LinkedHashSet<>();
        for (World.Environment dimension : DIMENSIONS) {
            session.validPlayers.stream()
                    .filter(sample -> sample.islandId().equals(islandId)
                            && sample.region().environment() == dimension
                            && !sample.op())
                    .map(ValidPlayerSample::playerName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(names::add);
        }
        return List.copyOf(names);
    }

    private @NotNull Map<IslandDimensionKey, Double> estimateIslandMspt(
            @NotNull QuerySession session, @NotNull LagWeights weights) {
        List<IslandLagAttribution.PlayerSample<IslandDimensionKey, RuntimeRegionKey>> players
                = new ArrayList<>(session.validPlayers.size());
        Map<RuntimeRegionKey, Double> regionMspt = new HashMap<>();
        Map<RuntimeRegionKey, Set<IslandDimensionKey>> representedIslands = new HashMap<>();
        Map<RuntimeRegionKey, Map<IslandDimensionKey, Double>> islandWeights = new HashMap<>();
        for (ValidPlayerSample sample : session.validPlayers) {
            RegionObservation region = session.collector.regions.get(sample.region());
            if (region == null || region.msptSamples.sum() <= 0L || region.loadedChunks.sum() <= 0L) {
                continue;
            }
            IslandDimensionKey sampleKey = new IslandDimensionKey(
                    sample.islandId(), sample.region().environment());
            players.add(new IslandLagAttribution.PlayerSample<>(sampleKey, sample.region()));
            regionMspt.put(sample.region(), region.averageMspt());
            Set<IslandDimensionKey> regionIslands = representedIslands.computeIfAbsent(
                    sample.region(), ignored -> new HashSet<>());
            for (UUID islandId : region.representedIslandIds) {
                IslandDimensionKey key = new IslandDimensionKey(
                        islandId, sample.region().environment());
                regionIslands.add(key);
                Map<IslandDimensionKey, Double> regionWeights = islandWeights.computeIfAbsent(
                        sample.region(), ignored -> new HashMap<>());
                regionWeights.computeIfAbsent(key,
                        ignored -> islandWeight(session.collector.regionIslands.get(
                                new RegionIslandKey(sample.region(), key)), weights));
            }
        }
        return IslandLagAttribution.estimate(players, regionMspt, representedIslands, islandWeights);
    }

    private static double islandWeight(IslandObservation observation, @NotNull LagWeights weights) {
        if (observation == null) {
            return 0.0D;
        }
        double weight = observation.loadedChunks.sum() * weights.chunkWeight();
        for (Map.Entry<EntityType, LongAdder> entry : observation.entityCounts.entrySet()) {
            weight += entry.getValue().sum() * weights.entityWeight(entry.getKey());
        }
        return Math.max(0.0D, weight);
    }

    private static double lagScore(double[] values) {
        double score = 0.0D;
        for (double value : values) {
            if (Double.isFinite(value) && value > 0.0D) {
                score += value;
            }
        }
        return score;
    }

    private static @org.jetbrains.annotations.Nullable RuntimeRegionKey runtimeRegionKey(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        Long regionId = SkylliaAPI.getWorldNMS().getTickRegionId(location);
        return regionId == null ? null
                : new RuntimeRegionKey(world.getUID(), world.getName(), world.getEnvironment(), regionId);
    }

    private record RuntimeRegionKey(@NotNull UUID worldId, @NotNull String worldName,
                                    @NotNull World.Environment environment, long regionId) {}

    private record RawPlayerSample(@NotNull UUID playerId, @NotNull String playerName, boolean op,
                                   @NotNull Location location, @NotNull RegionCoordinate skylliaRegion,
                                   @NotNull RuntimeRegionKey region) {}

    private record ValidPlayerSample(@NotNull UUID playerId, @NotNull String playerName, boolean op,
                                     @NotNull UUID islandId, @NotNull RuntimeRegionKey region) {}

    private record IslandMeta(@NotNull UUID id, @NotNull RegionCoordinate region, double size,
                              @org.jetbrains.annotations.Nullable UUID ownerId,
                              @NotNull String ownerName) {}

    private record ChunkRef(@NotNull World world, int x, int z) {}

    private record IslandDimensionKey(@NotNull UUID islandId, @NotNull World.Environment environment) {}

    private record RegionIslandKey(@NotNull RuntimeRegionKey region,
                                   @NotNull IslandDimensionKey island) {}

    private record IslandBounds(int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
        private static @NotNull IslandBounds from(@NotNull IslandMeta island, @NotNull World world) {
            int centerX = (island.region().x() << 9) + 256;
            int centerZ = (island.region().z() << 9) + 256;
            double half = (island.size() - 1.0D) / 2.0D;
            return new IslandBounds((int) Math.floor(centerX - half), (int) Math.floor(centerX + half),
                    (int) Math.floor(centerZ - half), (int) Math.floor(centerZ + half),
                    world.getMinHeight(), world.getMaxHeight() - 1);
        }

        private boolean overlapsChunk(int chunkX, int chunkZ) {
            int minChunkX = chunkX << 4;
            int minChunkZ = chunkZ << 4;
            return minChunkX + 15 >= minX && minChunkX <= maxX
                    && minChunkZ + 15 >= minZ && minChunkZ <= maxZ;
        }

        private boolean contains(@NotNull Location location) {
            return location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockY() >= minY && location.getBlockY() <= maxY
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }
    }

    private static final class RegionObservation {
        private final AtomicBoolean msptCaptured = new AtomicBoolean();
        private final LongAdder msptSamples = new LongAdder();
        private final DoubleAdder msptTotal = new DoubleAdder();
        private final LongAdder players = new LongAdder();
        private final LongAdder loadedChunks = new LongAdder();
        private final Map<EntityType, LongAdder> entityCounts = new ConcurrentHashMap<>();
        private final Set<UUID> representedIslandIds = ConcurrentHashMap.newKeySet();

        private void recordMspt(double mspt) {
            if (!Double.isFinite(mspt) || mspt < 0.0D || !msptCaptured.compareAndSet(false, true)) {
                return;
            }
            msptSamples.increment();
            msptTotal.add(mspt);
        }

        private void addPlayerSample(@NotNull UUID islandId) {
            players.increment();
            representedIslandIds.add(islandId);
        }

        private void addChunk() { loadedChunks.increment(); }
        private void addEntity(@NotNull EntityType type) {
            entityCounts.computeIfAbsent(type, ignored -> new LongAdder()).increment();
        }
        private double averageMspt() {
            long count = msptSamples.sum();
            return count == 0L ? 0.0D : msptTotal.sum() / count;
        }
    }

    private static final class IslandObservation {
        private final LongAdder loadedChunks = new LongAdder();
        private final Map<EntityType, LongAdder> entityCounts = new ConcurrentHashMap<>();
        private void addChunk() { loadedChunks.increment(); }
        private void addEntity(@NotNull EntityType type) {
            entityCounts.computeIfAbsent(type, ignored -> new LongAdder()).increment();
        }
    }

    private static final class ObservationCollector {
        private final Map<RuntimeRegionKey, RegionObservation> regions = new ConcurrentHashMap<>();
        private final Map<IslandDimensionKey, IslandObservation> islands = new ConcurrentHashMap<>();
        private final Map<RegionIslandKey, IslandObservation> regionIslands = new ConcurrentHashMap<>();

        private @NotNull RegionObservation region(@NotNull RuntimeRegionKey key) {
            return regions.computeIfAbsent(key, ignored -> new RegionObservation());
        }
        private @NotNull IslandObservation island(@NotNull IslandDimensionKey key) {
            return islands.computeIfAbsent(key, ignored -> new IslandObservation());
        }
        private @NotNull IslandObservation regionIsland(@NotNull RuntimeRegionKey region,
                                                        @NotNull IslandDimensionKey island) {
            return regionIslands.computeIfAbsent(new RegionIslandKey(region, island),
                    ignored -> new IslandObservation());
        }
        private void addPlayerSample(@NotNull ValidPlayerSample sample) {
            region(sample.region()).addPlayerSample(sample.islandId());
        }
        private @NotNull List<RegionLagSample> createSamples(long timestamp) {
            List<RegionLagSample> result = new ArrayList<>(regions.size());
            for (Map.Entry<RuntimeRegionKey, RegionObservation> entry : regions.entrySet()) {
                RegionObservation observation = entry.getValue();
                if (observation.msptSamples.sum() == 0L || observation.loadedChunks.sum() == 0L
                        || !Double.isFinite(observation.averageMspt())) continue;
                Map<EntityType, Long> counts = new HashMap<>();
                for (Map.Entry<EntityType, LongAdder> entity : observation.entityCounts.entrySet()) {
                    if (entity.getValue().sum() > 0L) counts.put(entity.getKey(), entity.getValue().sum());
                }
                RuntimeRegionKey key = entry.getKey();
                result.add(new RegionLagSample(key.worldName(), key.environment(), key.regionId(),
                        observation.averageMspt(), (int) Math.min(Integer.MAX_VALUE, observation.players.sum()),
                        observation.loadedChunks.sum(), counts, timestamp));
            }
            return result;
        }
    }

    // 本服务端项目自定义修改：扫描结果（排行榜条目 + 数据异常岛屿）
    private record ScanResult(@NotNull List<IslandTopGui.Entry> entries,
                              @NotNull List<IslandTopGui.Entry> abnormal) {
    }

    // 本服务端项目自定义修改：可复用的展示快照，供多个窗口会话共享
    private record Snapshot(@NotNull List<IslandTopGui.Entry> entries,
                            @NotNull boolean[] loadedDimensions,
                            @NotNull List<IslandTopGui.Entry> abnormal) {
    }

    private static final class QuerySession {
        private final Skyllia plugin;
        private final List<RawPlayerSample> rawPlayers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<ValidPlayerSample> validPlayers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Map<UUID, IslandMeta> islandsById = new ConcurrentHashMap<>();
        private final Map<RegionCoordinate, IslandMeta> islandsByRegion = new ConcurrentHashMap<>();
        private final Map<IslandDimensionKey, IslandBounds> islandBounds = new ConcurrentHashMap<>();
        private final Map<World.Environment, World> worlds = new EnumMap<>(World.Environment.class);
        private final boolean[] loadedDimensions = new boolean[DIMENSIONS.length];
        private final ObservationCollector collector = new ObservationCollector();
        private final AtomicBoolean finishing = new AtomicBoolean();

        private QuerySession(@NotNull Skyllia plugin) {
            this.plugin = plugin;
        }

        private boolean isActive() {
            return plugin.isEnabled();
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin,
                                                @NotNull CommandSender sender,
                                                @NotNull String[] args) {
        return List.of();
    }
}
