package fr.euphyllia.skyllia.configuration.manager;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlWriter;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GeneralConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(GeneralConfigManager.class);
    private final CommentedFileConfig config;
    private boolean changed = false;

    private Basic basic;
    private IslandSettings islandSettings;
    private SpawnSettings spawnSettings;
    private DebugSettings debugSettings;
    private CacheTtlSettings cacheTtlSettings;
    private PermissionsSettings permissionsSettings;

    private UpdateCheckerSettings updateCheckerSettings;
    // 本服务端项目自定义修改：岛屿存档恢复（热回档）设置
    private RestoreSettings restoreSettings;

    public GeneralConfigManager(CommentedFileConfig config) {
        this.config = config;
        loadConfig();
    }

    @Override
    public void loadConfig() {
        changed = false;

        this.basic = new Basic(
                getOrSetDefault("config-version", 4, Integer.class),
                getOrSetDefault("verbose", false, Boolean.class)
        );

        this.islandSettings = new IslandSettings(
                getOrSetDefault("settings.island.region-distance", -1, Integer.class),
                getOrSetDefault("settings.island.max-islands", 500_000, Integer.class),
                getOrSetDefault("settings.island.teleport-outside-island", false, Boolean.class),
                getOrSetDefault("settings.island.restrict-player-movement", false, Boolean.class),
                getOrSetDefault("settings.island.enable-obsidian-to-lava-conversion", true, Boolean.class),
                getOrSetDefault("settings.island.delete.prevent-deletion-if-has-members", true, Boolean.class),
                getOrSetDefault("settings.island.delete.chunk-perimeter-island", false, Boolean.class),
                getOrSetDefault("settings.island.invitation.teleport-when-accepting", true, Boolean.class),
                getOrSetDefault("settings.island.queue.allow-bypass", true, Boolean.class),
                getOrSetDefault("settings.island.queue.max-concurrent", -1, Integer.class),
                new ChunkProcessingSettings(
                        getOrSetDefault("settings.island.chunk-processing.delete.threads", -1, Integer.class),
                        getOrSetDefault("settings.island.chunk-processing.delete.delay-ms", 50, Integer.class),
                        getOrSetDefault("settings.island.chunk-processing.biome.threads", -1, Integer.class),
                        getOrSetDefault("settings.island.chunk-processing.biome.delay-ms", 50, Integer.class)
                )
        );

        this.spawnSettings = new SpawnSettings(
                getOrSetDefault("settings.spawn.enable", true, Boolean.class),
                getOrSetDefault("settings.spawn.world-name", "world", String.class),
                getOrSetDefault("settings.spawn.block-x", 0.0, Double.class),
                getOrSetDefault("settings.spawn.block-y", 64.0, Double.class),
                getOrSetDefault("settings.spawn.block-z", 0.0, Double.class),
                getOrSetDefault("settings.spawn.yaw", 0.0f, Float.class),
                getOrSetDefault("settings.spawn.pitch", 0.0f, Float.class)
        );

        this.debugSettings = new DebugSettings(
                getOrSetDefault("debug.permission", false, Boolean.class)
        );

        this.cacheTtlSettings = new CacheTtlSettings(
                getOrSetDefault("settings.cache.ttl.warps", -1L, Long.class),
                getOrSetDefault("settings.cache.ttl.name-role", -1L, Long.class),
                getOrSetDefault("settings.cache.ttl.role", -1L, Long.class),
                getOrSetDefault("settings.cache.ttl.island", -1L, Long.class),
                getOrSetDefault("settings.cache.ttl.player-link", -1L, Long.class),
                getOrSetDefault("settings.cache.ttl.members", -1L, Long.class),
                getOrSetDefault("settings.cache.ttl.state", -1L, Long.class),
                getOrSetDefault("settings.cache.ttl.island-name", -1L, Long.class),
                getOrSetDefault("settings.cache.ttl.island-description", -1L, Long.class)
        );

        this.permissionsSettings = new PermissionsSettings(
                getOrSetDefault("permissions.check-owner", false, Boolean.class),
                getOrSetDefault("permissions.check-ban", false, Boolean.class)
        );

        this.updateCheckerSettings = new UpdateCheckerSettings(
                getOrSetDefault("settings.update-checker.enabled", true, Boolean.class),
                getOrSetDefault("settings.update-checker.interval-minutes", 60L, Long.class)
        );

        // 本服务端项目自定义修改：岛屿存档恢复设置。
        // backup-root：备份世界目录（备份中的世界根目录，其下应有 dimensions/<命名空间>/<维度>/region|poi|entities）；
        // reminder-interval-hours：定时提醒管理员备份世界存档的间隔（小时），0 表示关闭。
        this.restoreSettings = new RestoreSettings(
                getOrSetDefault("settings.restore.backup-root", "restore/world", String.class),
                getOrSetDefault("settings.restore.reminder-interval-hours", 2, Integer.class)
        );

        if (changed) {
            TomlWriter tomlWriter = new TomlWriter();
            tomlWriter.setIndent(IndentStyle.NONE);
            tomlWriter.write(config, config.getFile(), WritingMode.REPLACE);
        }
    }

    @Override
    public void reloadFromDisk() {
        config.load();
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrSetDefault(String path, T defaultValue, Class<T> expectedClass) {
        Object value = config.get(path);
        if (value == null) {
            config.set(path, defaultValue);
            changed = true;
            return defaultValue;
        }

        if (expectedClass.isInstance(value)) {
            return (T) value;
        }

        if (expectedClass == Long.class && value instanceof Integer) {
            return (T) Long.valueOf((Integer) value);
        }

        if (expectedClass == Float.class && value instanceof Double) {
            return (T) Float.valueOf(((Double) value).floatValue());
        }

        throw new IllegalStateException("Cannot convert value at path '" + path + "' from "
                + value.getClass().getSimpleName() + " to " + expectedClass.getSimpleName());
    }

    public Basic getBasic() {
        return basic;
    }

    public IslandSettings getIslandSettings() {
        return islandSettings;
    }

    public SpawnSettings getSpawnSettings() {
        return spawnSettings;
    }

    public DebugSettings getDebugSettings() {
        return debugSettings;
    }

    public CacheTtlSettings getCacheTtlSettings() {
        return cacheTtlSettings;
    }

    public PermissionsSettings getPermissionsSettings() {
        return permissionsSettings;
    }

    public UpdateCheckerSettings getUpdateCheckerSettings() {
        return updateCheckerSettings;
    }

    // 本服务端项目自定义修改：岛屿存档恢复设置
    public RestoreSettings getRestoreSettings() {
        return restoreSettings;
    }

    // 本服务端项目自定义修改：岛屿存档恢复设置
    public record RestoreSettings(String backupRoot, int reminderIntervalHours) {
    }

    public Location getSpawnLocation() {
        if (!spawnSettings.enabled()) return null;
        var world = Bukkit.getWorld(spawnSettings.worldName());
        if (world == null) return null;
        return new Location(world, spawnSettings.x(), spawnSettings.y(), spawnSettings.z(),
                spawnSettings.yaw(), spawnSettings.pitch());
    }

    public record Basic(int configVersion, boolean verbose) {
    }

    public record IslandSettings(
            int regionDistance,
            int maxIslands,
            boolean teleportOutsideIsland,
            boolean restrictPlayerMovement,
            boolean enableObsidianToLavaConversion,
            boolean preventDeletionIfHasMembers,
            boolean deleteChunkPerimeterIsland,
            boolean teleportWhenAcceptingInvitation,
            boolean allowBypassQueue,
            int maxConcurrentCreations,
            ChunkProcessingSettings chunkProcessing
    ) {
        /**
         * Resolves the configured maximum number of islands that may be
         * created concurrently across the whole server.
         * <p>
         * -1 means "auto": derived from the number of available CPU cores,
         * capped at 4 so that servers running the default SQLite backend —
         * whose HikariCP pool is itself capped at 4 connections, see
         * database.toml — never end up with more concurrent creations than
         * the connection pool can actually serve. Raise {@code
         * settings.island.queue.max-concurrent} explicitly past 4 if you run
         * MariaDB/PostgreSQL with a larger pool and want more parallelism.
         *
         * @return the resolved concurrency limit, always &gt;= 1
         */
        public int resolvedMaxConcurrentCreations() {
            if (maxConcurrentCreations == -1) {
                return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
            }
            if (maxConcurrentCreations > 0) {
                return maxConcurrentCreations;
            }
            log.warn("Invalid settings.island.queue.max-concurrent value ({}), falling back to 1.", maxConcurrentCreations);
            return 1;
        }
    }


    public record ChunkProcessingSettings(
            int deleteThreads,
            int deleteDelayMs,
            int biomeThreads,
            int biomeDelayMs
    ) {

        private static int resolveThreads(int configured, String context) {
            if (configured == -1) {
                return Math.max(4, Runtime.getRuntime().availableProcessors() / 8);
            }

            if (configured > 0) {
                return configured;
            }

            log.warn("Invalid chunk-processing.{}.threads value ({}), falling back to 1.", context, configured);
            return 1;
        }

        public int resolvedDeleteThreads() {
            return resolveThreads(deleteThreads, "delete");
        }

        public int resolvedBiomeThreads() {
            return resolveThreads(biomeThreads, "biome");
        }
    }


    public record SpawnSettings(boolean enabled, String worldName, double x, double y, double z, float yaw,
                                float pitch) {
    }

    public record DebugSettings(boolean permission) {
    }

    public record CacheTtlSettings(long warps, long nameRole, long role, long island, long playerLink, long members,
                                   long state, long islandName, long islandDescription) {
    }

    public record PermissionsSettings(boolean checkOwner, boolean checkBan) {
    }

    public record UpdateCheckerSettings(boolean enabled, long intervalMinutes) {

    }
}
