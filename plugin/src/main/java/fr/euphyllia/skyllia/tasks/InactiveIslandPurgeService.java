package fr.euphyllia.skyllia.tasks;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.enums.RemovalCause;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.utils.RegionUtils;
import fr.euphyllia.skyllia.commands.common.subcommands.DeleteSubCommand;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.managers.skyblock.SkyblockManager;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import me.clip.placeholderapi.PlaceholderAPI;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class InactiveIslandPurgeService {

    private static final Logger LOGGER = LogManager.getLogger(InactiveIslandPurgeService.class);
    private static final String BYPASS_PERMISSION = "skyllia.island.purge.bypass";
    private static final String LAST_PLAYED_PLACEHOLDER = "%player_last_played%";
    private static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Skyllia plugin;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public InactiveIslandPurgeService(Skyllia plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long initialDelaySeconds = secondsUntilNextRun();
        Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                task -> runCheck(),
                initialDelaySeconds,
                TimeUnit.DAYS.toSeconds(1),
                TimeUnit.SECONDS
        );
        LOGGER.info("不活跃岛屿清理已定时，首次运行将在 {} 后", formatDuration(initialDelaySeconds * 1000L));
    }

    private long secondsUntilNextRun() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime nextRun = now.withHour(4).withMinute(0).withSecond(0).withNano(0);
        if (!nextRun.isAfter(now)) {
            nextRun = nextRun.plusDays(1);
        }
        return Math.max(1L, Duration.between(now, nextRun).getSeconds());
    }

    public boolean runNow() {
        return runCheck();
    }

    private boolean runCheck() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("上一轮清理仍在进行，跳过本次");
            return false;
        }

        try {
            if (!isPluginEnabled("PlaceholderAPI")) {
                LOGGER.warn("未检测到 PlaceholderAPI，跳过不活跃岛屿清理");
                return true;
            }

            Optional<ChallengeMethods> challengeMethods = resolveChallengeMethods();
            if (challengeMethods.isEmpty()) {
                LOGGER.warn("未检测到 SkylliaChallenge，跳过不活跃岛屿清理");
                return true;
            }

            SkyblockManager skyblockManager = plugin.getInterneAPI().getSkyblockManager();
            List<Island> islands = skyblockManager.getAllIslandsValid();
            long now = System.currentTimeMillis();
            List<Island> toPurge = new ArrayList<>();

            LOGGER.info("开始不活跃岛屿检查，共 {} 个岛屿", islands.size());

            for (Island island : islands) {
                try {
                    if (evaluateIsland(skyblockManager, challengeMethods.get(), island, now)) {
                        toPurge.add(island);
                    }
                } catch (Throwable throwable) {
                    LOGGER.error("检查岛屿 {} 时出错: {}", island.getId(), throwable.getMessage(), throwable);
                }
            }

            LOGGER.info("检查完成，共 {} 个岛屿，{} 个需要清理", islands.size(), toPurge.size());
            processNextPurge(skyblockManager, toPurge.iterator());
            return true;
        } catch (Throwable throwable) {
            running.set(false);
            LOGGER.error("不活跃岛屿检查发生异常: {}", throwable.getMessage(), throwable);
            return false;
        }
    }

    /**
     * 逐个执行清理任务，等上一个彻底完成（含世界删除）后再提交下一个，避免卡顿。
     */
    private void processNextPurge(SkyblockManager skyblockManager, Iterator<Island> iterator) {
        if (!iterator.hasNext()) {
            running.set(false);
            LOGGER.info("不活跃岛屿清理全部完成");
            return;
        }

        Island island = iterator.next();
        List<Players> participants = participants(island);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            purgeIsland(skyblockManager, island, participants, () ->
                    Bukkit.getAsyncScheduler().runNow(plugin, nextTask -> processNextPurge(skyblockManager, iterator))
            );
        });
    }

    private boolean evaluateIsland(SkyblockManager skyblockManager, ChallengeMethods challengeMethods, Island island, long now) throws ReflectiveOperationException {
        List<Players> participants = participants(island);
        Players owner = island.getOwner();
        String ownerName = ownerName(owner);

        ChallengeStats challengeStats = challengeMethods.stats(island);
        long maxOfflineDays = challengeStats.levelFiveUnlocked()
                ? Long.MAX_VALUE
                : 30L + challengeStats.completedChallenges() * 2L;

        if (hasBypass(participants)) {
            logIsland(ownerName, false, "豁免", "豁免", challengeStats.completedChallenges(), maxOfflineDays);
            return false;
        }

        Optional<Long> lastActive = latestLastPlayed(participants);
        if (lastActive.isEmpty()) {
            logIsland(ownerName, false, "未知", "未知", challengeStats.completedChallenges(), maxOfflineDays);
            LOGGER.warn("岛屿 {} 因部分成员缺少 {} 数据被跳过", island.getId(), LAST_PLAYED_PLACEHOLDER);
            return false;
        }

        long offlineMillis = Math.max(0L, now - lastActive.get());
        boolean shouldPurge = !challengeStats.levelFiveUnlocked() && offlineMillis > maxOfflineDays * MILLIS_PER_DAY;

        logIsland(
                ownerName,
                shouldPurge,
                DATE_FORMATTER.format(Instant.ofEpochMilli(lastActive.get())),
                formatDaysHours(offlineMillis),
                challengeStats.completedChallenges(),
                maxOfflineDays
        );

        return shouldPurge;
    }

    private List<Players> participants(Island island) {
        Map<UUID, Players> byId = new LinkedHashMap<>();
        Players owner = island.getOwner();
        if (owner != null && owner.getMojangId() != null) {
            byId.put(owner.getMojangId(), owner);
        }
        for (Players member : island.getMembers()) {
            if (member != null && member.getMojangId() != null) {
                byId.put(member.getMojangId(), member);
            }
        }
        return List.copyOf(byId.values());
    }

    private boolean hasBypass(List<Players> participants) {
        for (Players participant : participants) {
            UUID playerId = participant.getMojangId();
            Player online = Bukkit.getPlayer(playerId);
            if (online != null && online.isOnline()) {
                if (PlayerUtils.hasPermission(online, BYPASS_PERMISSION)) {
                    return true;
                }
                continue;
            }
            if (hasOfflinePermission(playerId, BYPASS_PERMISSION)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOfflinePermission(UUID playerId, String permission) {
        LuckPerms luckPerms = getLuckPerms();
        if (luckPerms == null) {
            return false;
        }

        try {
            User user = luckPerms.getUserManager().getUser(playerId);
            if (user == null) {
                user = luckPerms.getUserManager().loadUser(playerId).join();
            }
            QueryOptions queryOptions = luckPerms.getContextManager().getStaticQueryOptions();
            return user.getCachedData().getPermissionData(queryOptions).checkPermission(permission).asBoolean();
        } catch (Throwable throwable) {
            LOGGER.warn("检查 {} 的离线豁免权限失败: {}", playerId, throwable.getMessage());
            return false;
        }
    }

    private Optional<Long> latestLastPlayed(List<Players> participants) {
        long latest = Long.MIN_VALUE;

        for (Players participant : participants) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(participant.getMojangId());
            String raw = PlaceholderAPI.setPlaceholders(offlinePlayer, LAST_PLAYED_PLACEHOLDER);
            long parsed;
            try {
                parsed = Long.parseLong(raw == null ? "" : raw.trim());
            } catch (NumberFormatException exception) {
                LOGGER.warn("成员 {} 的 {} 数据无效: {}", ownerName(participant), LAST_PLAYED_PLACEHOLDER, raw);
                return Optional.empty();
            }
            latest = Math.max(latest, parsed);
        }

        return latest == Long.MIN_VALUE ? Optional.empty() : Optional.of(latest);
    }

    private void purgeIsland(SkyblockManager skyblockManager, Island island, List<Players> participants, Runnable onComplete) {
        try {
            LOGGER.warn("清理不活跃岛屿 {}（岛主: {}）", island.getId(), ownerName(island.getOwner()));

            if (!skyblockManager.setLockedIsland(island, true)) {
                LOGGER.error("岛屿 {} 加锁失败，清理中止", island.getId());
                onComplete.run();
                return;
            }

            if (!island.setDisable(true)) {
                skyblockManager.setLockedIsland(island, false);
                LOGGER.error("岛屿 {} 禁用失败，清理中止", island.getId());
                onComplete.run();
                return;
            }

            Economy economy = getEconomy();
            for (Players participant : participants) {
                participant.setRoleType(RoleType.VISITOR);
                island.updateMember(participant);
                DeleteSubCommand.checkClearPlayer(skyblockManager, participant, RemovalCause.ISLAND_DELETED);
                clearBalance(economy, participant);
            }

            teleportPlayersOutOfIsland(island);
            deleteIslandWorlds(skyblockManager, island, onComplete);
        } catch (Throwable throwable) {
            LOGGER.error("清理岛屿 {} 时出错: {}", island.getId(), throwable.getMessage(), throwable);
            skyblockManager.setLockedIsland(island, false);
            onComplete.run();
        }
    }

    private void teleportPlayersOutOfIsland(Island island) {
        for (WorldConfig worldConfig : WorldUtils.getWorldConfigs()) {
            RegionUtils.getEntitiesInRegion(
                    plugin,
                    ConfigLoader.general.getIslandSettings().regionDistance(),
                    EntityType.PLAYER,
                    worldConfig.getWorld(),
                    island.getRegionCoordinate(),
                    island.getSize(),
                    entity -> {
                        Player player = (Player) entity;
                        if (PlayerUtils.hasPermission(player, "skyllia.island.command.access.bypass")) {
                            return;
                        }
                        PlayerUtils.teleportPlayerSpawn(player);
                    }
            );
        }
    }

    private void deleteIslandWorlds(SkyblockManager skyblockManager, Island island, Runnable onComplete) {
        List<Map.Entry<String, WorldConfig>> worldsToDelete = ConfigLoader.worldManager.getWorldConfigs().entrySet().stream()
                .filter(entry -> entry.getValue().shouldDeleteIsland())
                .toList();

        AtomicInteger worldsLeft = new AtomicInteger(worldsToDelete.size());
        AtomicBoolean failed = new AtomicBoolean(false);

        if (worldsLeft.get() == 0) {
            skyblockManager.setLockedIsland(island, true);
            LOGGER.info("岛屿 {} 已禁用，无世界需删除，保持锁定", island.getId());
            onComplete.run();
            return;
        }

        for (Map.Entry<String, WorldConfig> entry : worldsToDelete) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) {
                failed.set(true);
                LOGGER.error("岛屿 {} 在世界 {} 删除失败: 世界未加载", island.getId(), entry.getKey());
                finishWorldDeletion(skyblockManager, island, worldsLeft, failed, onComplete);
                continue;
            }

            plugin.getInterneAPI().getWorldModifier().deleteIsland(
                    island,
                    world,
                    ConfigLoader.general.getIslandSettings().regionDistance(),
                    success -> {
                        if (!success) {
                            failed.set(true);
                        }
                        finishWorldDeletion(skyblockManager, island, worldsLeft, failed, onComplete);
                    }
            );
        }
    }

    private void finishWorldDeletion(SkyblockManager skyblockManager, Island island, AtomicInteger worldsLeft, AtomicBoolean failed, Runnable onComplete) {
        if (worldsLeft.decrementAndGet() != 0) {
            return;
        }

        boolean lockResult = skyblockManager.setLockedIsland(island, failed.get());
        if (!lockResult) {
            LOGGER.error("清理后岛屿 {} 的锁定状态更新失败", island.getId());
            onComplete.run();
            return;
        }

        if (failed.get()) {
            LOGGER.error("岛屿 {} 清理完成但有错误，保持锁定", island.getId());
        } else {
            LOGGER.info("岛屿 {} 清理完成", island.getId());
        }
        onComplete.run();
    }

    private void clearBalance(Economy economy, Players participant) {
        if (economy == null) {
            LOGGER.warn("未检测到 Vault，{} 的余额未清理", ownerName(participant));
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(participant.getMojangId());
        double balance = economy.getBalance(offlinePlayer);
        if (balance == 0.0D) {
            return;
        }

        EconomyResponse response = balance > 0.0D
                ? economy.withdrawPlayer(offlinePlayer, balance)
                : economy.depositPlayer(offlinePlayer, -balance);

        if (!response.transactionSuccess()) {
            LOGGER.warn("清理 {} 的余额失败: {}", ownerName(participant), response.errorMessage);
        }
    }

    private Economy getEconomy() {
        if (!isPluginEnabled("Vault")) {
            return null;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }

    private LuckPerms getLuckPerms() {
        if (!isPluginEnabled("LuckPerms")) {
            return null;
        }
        RegisteredServiceProvider<LuckPerms> registration = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        return registration == null ? null : registration.getProvider();
    }

    private Optional<ChallengeMethods> resolveChallengeMethods() {
        Plugin challengePlugin = Bukkit.getPluginManager().getPlugin("SkylliaChallenge");
        if (challengePlugin == null || !challengePlugin.isEnabled()) {
            return Optional.empty();
        }

        try {
            Object challengeManager = challengePlugin.getClass().getMethod("getChallengeManager").invoke(challengePlugin);
            if (challengeManager == null) {
                return Optional.empty();
            }
            Method getCompletedCount = challengeManager.getClass().getMethod("getCompletedCount", int.class, Island.class);
            Method isLevelUnlocked = challengeManager.getClass().getMethod("isLevelUnlocked", int.class, Island.class);
            return Optional.of(new ChallengeMethods(challengeManager, getCompletedCount, isLevelUnlocked));
        } catch (ReflectiveOperationException exception) {
            LOGGER.error("访问 SkylliaChallenge 挑战管理器 API 失败: {}", exception.getMessage(), exception);
            return Optional.empty();
        }
    }

    private boolean isPluginEnabled(String pluginName) {
        Plugin dependency = Bukkit.getPluginManager().getPlugin(pluginName);
        return dependency != null && dependency.isEnabled();
    }

    private void logIsland(String ownerName, boolean shouldPurge, String lastActive, String offlineTime, int completedChallenges, long maxOfflineDays) {
        LOGGER.info(
                "{} 清理={} 最后在线={} 离线={} 完成挑战={} 上限天数={}",
                ownerName,
                shouldPurge,
                lastActive,
                offlineTime,
                completedChallenges,
                maxOfflineDays == Long.MAX_VALUE ? "无限" : Long.toString(maxOfflineDays)
        );
    }

    private String ownerName(Players players) {
        if (players == null) {
            return "unknown";
        }
        String name = players.getLastKnowName();
        return name == null || name.isBlank() ? players.getMojangId().toString() : name;
    }

    private String formatDaysHours(long millis) {
        long days = millis / MILLIS_PER_DAY;
        long hours = (millis % MILLIS_PER_DAY) / TimeUnit.HOURS.toMillis(1);
        return days + "天" + hours + "小时";
    }

    private String formatDuration(long millis) {
        long days = millis / MILLIS_PER_DAY;
        long hours = (millis % MILLIS_PER_DAY) / TimeUnit.HOURS.toMillis(1);
        long minutes = (millis % TimeUnit.HOURS.toMillis(1)) / TimeUnit.MINUTES.toMillis(1);
        if (days > 0) return days + "天" + hours + "小时";
        if (hours > 0) return hours + "小时" + minutes + "分";
        return minutes + "分";
    }

    private record ChallengeMethods(Object manager, Method getCompletedCount, Method isLevelUnlocked) {

        private ChallengeStats stats(Island island) throws ReflectiveOperationException {
            int completed = 0;
            for (int level = 1; level <= 5; level++) {
                completed += ((Number) getCompletedCount.invoke(manager, level, island)).intValue();
            }
            boolean levelFiveUnlocked = (Boolean) isLevelUnlocked.invoke(manager, 5, island);
            return new ChallengeStats(completed, levelFiveUnlocked);
        }
    }

    private record ChallengeStats(int completedChallenges, boolean levelFiveUnlocked) {
    }
}
