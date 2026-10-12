package fr.euphyllia.skyllia.analytics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本服务端项目自定义修改：光荣榜「方块类排名」的类别定义、结果与进度存储。
 *
 * <p>每个类别由一组方块构成，统计全服各岛屿在三个维度内该类方块的总量并取前十名。
 * 由于全服扫描周期较长，本类会把以下内容持久化到 {@code analytics/island-block-top.json}：</p>
 * <ul>
 *   <li>已发布的排名（GUI 直接展示）；</li>
 *   <li>本轮已统计的各岛屿数据（供构建排名与跨重启续跑）；</li>
 *   <li>本轮扫描进度（已扫描/总数、起止时间）。</li>
 * </ul>
 *
 * <p>数据由 {@link IslandBlockTopScanner} 写入、{@link fr.euphyllia.skyllia.gui.IslandTopGui}
 * 与 {@code /is top progress} 读取。本类为实例级、无静态可变状态，可安全随插件热重载创建/销毁。</p>
 */
public final class IslandBlockTopStore {

    private static final Logger LOGGER = LogManager.getLogger(IslandBlockTopStore.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final int FILE_VERSION = 1;
    // 本服务端项目自定义修改：统计线程落盘的最小间隔，避免每统计一个岛屿都写盘
    private static final long SAVE_THROTTLE_MILLIS = 30_000L;

    /**
     * 方块类排名的类别。
     *
     * @param title     带 {@code &} 颜色代码的原始标题，由 GUI 渲染时解析
     * @param icon      展示图标
     * @param materials 参与统计的方块集合
     */
    public enum Category {
        // 本服务端项目自定义修改：枚举顺序即第 6 行 46~52 格的展示顺序
        HOPPER("&8漏斗&c最多的岛屿", Material.HOPPER, List.of(Material.HOPPER)),
        FURNACE("&7熔炉&c最多的岛屿", Material.FURNACE,
                List.of(Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER)),
        NETHER_PORTAL("&d双维度机器&c规模最大的岛屿", Material.OBSIDIAN,
                List.of(Material.NETHER_PORTAL)),
        // 本服务端项目自定义修改：需求确认「红石」仅统计红石线（REDSTONE_WIRE）
        REDSTONE("&c裸露红石最多的岛屿", Material.REDSTONE, List.of(Material.REDSTONE_WIRE)),
        OBSERVER_PISTON("&7侦测器和&6活塞&c最多的岛屿", Material.OBSERVER,
                List.of(Material.OBSERVER, Material.PISTON, Material.STICKY_PISTON)),
        TNT("&4TNT&c最多的岛屿", Material.TNT, List.of(Material.TNT)),
        WATER("&9水&c最多的岛屿", Material.WATER_BUCKET, List.of(Material.WATER));

        private final String title;
        private final Material icon;
        private final List<Material> materials;

        Category(String title, Material icon, List<Material> materials) {
            this.title = title;
            this.icon = icon;
            this.materials = materials;
        }

        public String title() {
            return title;
        }

        public Material icon() {
            return icon;
        }

        public List<Material> materials() {
            return materials;
        }
    }

    // 本服务端项目自定义修改：方块 -> 类别 的反查表，扫描时对每个区块的方块表单次遍历即可归类
    private static final Map<Material, Category> MATERIAL_INDEX = buildMaterialIndex();

    private static Map<Material, Category> buildMaterialIndex() {
        Map<Material, Category> index = new EnumMap<>(Material.class);
        for (Category category : Category.values()) {
            for (Material material : category.materials()) {
                index.put(material, category);
            }
        }
        return Map.copyOf(index);
    }

    /**
     * 本服务端项目自定义修改：返回方块所属的排名类别，未参与统计的方块返回 {@code null}。
     */
    public static Category categoryOf(Material material) {
        return MATERIAL_INDEX.get(material);
    }

    private final Path file;
    private final Object lock = new Object();
    private final Map<UUID, Tally> tallies = new ConcurrentHashMap<>();
    private volatile Map<Category, List<Ranking>> rankings = Map.of();
    private volatile Progress progress = Progress.EMPTY;
    private volatile long lastSaveAt;

    public IslandBlockTopStore(@NotNull Path file) {
        this.file = file;
        load();
    }

    /**
     * 返回最近一次发布的排名快照（不可变），供 GUI 展示。
     */
    public @NotNull Map<Category, List<Ranking>> snapshot() {
        return rankings;
    }

    /**
     * 返回某类别下的排名条目（按数量降序，最多十项）。
     */
    public @NotNull List<Ranking> rankings(@NotNull Category category) {
        return rankings.getOrDefault(category, List.of());
    }

    /**
     * 返回本轮扫描进度。
     */
    public @NotNull Progress progress() {
        return progress;
    }

    /**
     * 返回本轮已统计的各岛屿数据（不可变副本），供构建排名与续跑判断。
     */
    public @NotNull Map<UUID, Tally> tallies() {
        return Map.copyOf(tallies);
    }

    /**
     * 开始新一轮统计：清空本轮数据并重置进度。
     */
    public void beginCycle(int total) {
        tallies.clear();
        progress = new Progress(0, Math.max(0, total), System.currentTimeMillis(), progress.publishedAt(), false);
        save();
    }

    /**
     * 记录一个本轮已统计的岛屿，并推进进度。
     */
    public void putTally(@NotNull Tally tally) {
        tallies.put(tally.islandId(), tally);
        Progress current = progress;
        progress = new Progress(tallies.size(), current.total(), current.startedAt(), current.publishedAt(), false);
        saveThrottled();
    }

    /**
     * 移除已不存在岛屿的本轮统计，避免其残留在排名中。
     */
    public void retainTallies(@NotNull java.util.Set<UUID> keep) {
        if (tallies.keySet().retainAll(keep)) {
            Progress current = progress;
            progress = new Progress(tallies.size(), current.total(), current.startedAt(),
                    current.publishedAt(), current.complete());
            save();
        }
    }

    /**
     * 标记本轮统计结束。
     */
    public void finishCycle() {
        Progress current = progress;
        progress = new Progress(tallies.size(), current.total(), current.startedAt(), current.publishedAt(), true);
        save();
    }

    /**
     * 发布新的排名结果并记录发布时间。传入 map 会被复制为不可变快照，读方无需加锁。
     */
    public void publish(@NotNull Map<Category, List<Ranking>> next) {
        this.rankings = Map.copyOf(next);
        Progress current = progress;
        progress = new Progress(current.scanned(), current.total(), current.startedAt(),
                System.currentTimeMillis(), current.complete());
        save();
    }

    public void save() {
        synchronized (lock) {
            saveLocked();
        }
    }

    private void saveThrottled() {
        if (System.currentTimeMillis() - lastSaveAt < SAVE_THROTTLE_MILLIS) {
            return;
        }
        save();
    }

    private void saveLocked() {
        lastSaveAt = System.currentTimeMillis();
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        StoreState state = toState();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(state, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.warn("无法保存方块类排名数据 {}", file, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StoreState state = GSON.fromJson(reader, StoreState.class);
            if (state == null) {
                return;
            }
            state.normalize();

            Map<Category, List<Ranking>> loaded = new EnumMap<>(Category.class);
            for (StoredRanking stored : state.rankings) {
                Category category = parseCategory(stored.category);
                UUID islandId = parseUuid(stored.islandId);
                if (category == null || islandId == null) {
                    continue;
                }
                loaded.computeIfAbsent(category, ignored -> new ArrayList<>())
                        .add(new Ranking(islandId, safeName(stored.ownerName), stored.count));
            }
            Map<Category, List<Ranking>> immutable = new EnumMap<>(Category.class);
            loaded.forEach((category, list) -> immutable.put(category, List.copyOf(list)));
            this.rankings = Map.copyOf(immutable);

            int expected = Category.values().length;
            for (StoredTally stored : state.tallies) {
                UUID islandId = parseUuid(stored.islandId);
                if (islandId == null || stored.totals == null || stored.totals.length != expected) {
                    continue;
                }
                tallies.put(islandId, new Tally(islandId, safeName(stored.ownerName), stored.totals));
            }

            StoredProgress storedProgress = state.progress;
            this.progress = new Progress(storedProgress.scanned, storedProgress.total,
                    storedProgress.startedAt, storedProgress.publishedAt, storedProgress.complete);
            LOGGER.info("已加载方块类排名数据：{} 个类别、{} 个岛屿统计，进度 {}/{}",
                    rankings.size(), tallies.size(), progress.scanned(), progress.total());
        } catch (IOException | JsonParseException exception) {
            LOGGER.warn("无法读取方块类排名数据 {}，将重新统计", file, exception);
            moveCorruptFile();
        }
    }

    private @NotNull StoreState toState() {
        StoreState state = new StoreState();
        state.updatedAt = System.currentTimeMillis();
        state.progress = new StoredProgress(progress.scanned(), progress.total(),
                progress.startedAt(), progress.publishedAt(), progress.complete());
        for (Map.Entry<Category, List<Ranking>> entry : rankings.entrySet()) {
            for (Ranking ranking : entry.getValue()) {
                state.rankings.add(new StoredRanking(entry.getKey().name(),
                        ranking.islandId().toString(), ranking.ownerName(), ranking.count()));
            }
        }
        for (Tally tally : tallies.values()) {
            state.tallies.add(new StoredTally(tally.islandId().toString(), tally.ownerName(), tally.totals()));
        }
        return state;
    }

    private void moveCorruptFile() {
        try {
            Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            LOGGER.warn("无法归档损坏的方块类排名数据 {}", file, exception);
        }
    }

    private static @Nullable Category parseCategory(@Nullable String name) {
        if (name == null) {
            return null;
        }
        try {
            return Category.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static @Nullable UUID parseUuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static @NotNull String safeName(@Nullable String name) {
        return (name == null || name.isBlank()) ? "未知" : name;
    }

    /**
     * 某类别下的一个排名条目。
     *
     * @param islandId  岛屿 ID
     * @param ownerName 岛主名称
     * @param count     该类方块数量
     */
    public record Ranking(@NotNull UUID islandId, @NotNull String ownerName, long count) {
    }

    /**
     * 某个岛屿本轮已统计的各类别方块总量。
     *
     * @param islandId  岛屿 ID
     * @param ownerName 岛主名称
     * @param totals    按 {@link Category#ordinal()} 排列的各类别数量
     */
    public record Tally(@NotNull UUID islandId, @NotNull String ownerName, long[] totals) {
    }

    /**
     * 本轮扫描进度。
     *
     * @param scanned     本轮已统计的岛屿数
     * @param total       本轮岛屿总数
     * @param startedAt   本轮开始时间戳
     * @param publishedAt 上次发布时间戳
     * @param complete    本轮是否已完成
     */
    public record Progress(int scanned, int total, long startedAt, long publishedAt, boolean complete) {
        public static final Progress EMPTY = new Progress(0, 0, 0L, 0L, true);

        public int percent() {
            return total <= 0 ? 0 : (int) Math.min(100L, (long) scanned * 100L / total);
        }
    }

    private static final class StoreState {
        private int version = FILE_VERSION;
        private long updatedAt;
        private StoredProgress progress = new StoredProgress();
        private List<StoredRanking> rankings = new ArrayList<>();
        private List<StoredTally> tallies = new ArrayList<>();

        private void normalize() {
            version = FILE_VERSION;
            if (progress == null) {
                progress = new StoredProgress();
            }
            if (rankings == null) {
                rankings = new ArrayList<>();
            }
            if (tallies == null) {
                tallies = new ArrayList<>();
            }
        }
    }

    private static final class StoredProgress {
        private int scanned;
        private int total;
        private long startedAt;
        private long publishedAt;
        private boolean complete = true;

        private StoredProgress() {
        }

        private StoredProgress(int scanned, int total, long startedAt, long publishedAt, boolean complete) {
            this.scanned = scanned;
            this.total = total;
            this.startedAt = startedAt;
            this.publishedAt = publishedAt;
            this.complete = complete;
        }
    }

    private static final class StoredRanking {
        private String category;
        private String islandId;
        private String ownerName;
        private long count;

        private StoredRanking() {
        }

        private StoredRanking(String category, String islandId, String ownerName, long count) {
            this.category = category;
            this.islandId = islandId;
            this.ownerName = ownerName;
            this.count = count;
        }
    }

    private static final class StoredTally {
        private String islandId;
        private String ownerName;
        private long[] totals;

        private StoredTally() {
        }

        private StoredTally(String islandId, String ownerName, long[] totals) {
            this.islandId = islandId;
            this.ownerName = ownerName;
            this.totals = totals;
        }
    }
}
