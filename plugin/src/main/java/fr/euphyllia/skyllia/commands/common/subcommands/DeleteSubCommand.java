package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.enums.RemovalCause;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.utils.RegionUtils;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.managers.skyblock.SkyblockManager;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DeleteSubCommand implements SubCommandInterface {

    private final Logger logger = LogManager.getLogger(DeleteSubCommand.class);

    // Lophine 自定义修改：删除命令采用两阶段确认，状态仅保存在当前子命令实例中，便于 PlugmanX 热卸载时清理。
    private static final long DELETE_CONFIRM_WINDOW_NANOS = 15_000_000_000L;
    private static final long DELETION_IN_PROGRESS = Long.MAX_VALUE;
    private final Map<UUID, Long> pendingDeleteConfirmations = new ConcurrentHashMap<>();

    public static void checkClearPlayer(SkyblockManager skyblockManager, Players players, RemovalCause cause) {
        Player bPlayer = Bukkit.getPlayer(players.getMojangId());
        if (bPlayer != null && bPlayer.isOnline()) {
            PlayerUtils.teleportPlayerSpawn(bPlayer);
            bPlayer.getScheduler().execute(Skyllia.getInstance(), () -> {
                switch (cause) {
                    case KICKED -> {
                        if (ConfigLoader.playerManager.isClearInventoryWhenKicked()) {
                            bPlayer.getInventory().clear();
                        }
                        if (ConfigLoader.playerManager.isClearEnderChestWhenKicked()) {
                            bPlayer.getEnderChest().clear();
                        }
                        if (ConfigLoader.playerManager.isResetExperienceWhenKicked()) {
                            bPlayer.setTotalExperience(0);
                            bPlayer.setExp(0);
                            bPlayer.setLevel(0);
                            bPlayer.sendExperienceChange(0, 0); // Mise à jour du packet
                        }
                    }
                    case ISLAND_DELETED -> {
                        if (ConfigLoader.playerManager.isClearInventoryWhenDelete()) {
                            bPlayer.getInventory().clear();
                        }
                        if (ConfigLoader.playerManager.isClearEnderChestWhenDelete()) {
                            bPlayer.getEnderChest().clear();
                        }
                        if (ConfigLoader.playerManager.isResetExperienceWhenDelete()) {
                            bPlayer.setTotalExperience(0);
                            bPlayer.setExp(0);
                            bPlayer.setLevel(0);
                            bPlayer.sendExperienceChange(0, 0); // Mise à jour du packet
                        }
                    }
                    case LEAVE -> {
                        if (ConfigLoader.playerManager.isClearInventoryWhenLeave()) {
                            bPlayer.getInventory().clear();
                        }
                        if (ConfigLoader.playerManager.isClearEnderChestWhenLeave()) {
                            bPlayer.getEnderChest().clear();
                        }
                        if (ConfigLoader.playerManager.isResetExperienceWhenLeave()) {
                            bPlayer.setTotalExperience(0);
                            bPlayer.setExp(0);
                            bPlayer.setLevel(0);
                            bPlayer.sendExperienceChange(0, 0);
                        }
                    }
                }
                bPlayer.setGameMode(GameMode.SURVIVAL);
            }, null, 1L);
        } else {
            skyblockManager.addClearMemberNextLogin(players.getMojangId(), cause);
        }
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        if (args.length > 1) {
            ConfigLoader.language.sendMessage(player, "island.delete.args-missing");
            return;
        }
        if (args.length == 1 && !args[0].equalsIgnoreCase("confirm")) {
            ConfigLoader.language.sendMessage(player, "island.admin.delete-no-confirm");
            return;
        }

        try {
            SkyblockManager skyblockManager = Skyllia.getInstance().getInterneAPI().getSkyblockManager();
            Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
            if (island == null) {
                ConfigLoader.language.sendMessage(player, "island.player.no-island");
                return;
            }

            Players executorPlayer = island.getMember(player.getUniqueId());
            if (executorPlayer == null || !executorPlayer.getRoleType().equals(RoleType.OWNER)) {
                ConfigLoader.language.sendMessage(player, "island.delete-only-owner");
                return;
            }

            // Vérification des membres
            if (ConfigLoader.general.getIslandSettings().preventDeletionIfHasMembers()) {
                long memberCount = island.getMembers().stream()
                        .filter(member -> !member.getMojangId().equals(player.getUniqueId()))
                        .count();
                if (memberCount > 0) {
                    ConfigLoader.language.sendMessage(player, "island.player.delete-has-members");
                    return;
                }
            }

            boolean hasConfirmationArgument = args.length == 1;
            if (hasConfirmationArgument && isDeletionInProgress(player.getUniqueId())) {
                return;
            }
            if (!hasConfirmationArgument || !tryStartDeletion(player.getUniqueId())) {
                startDeleteConfirmation(plugin, player);
                return;
            }

            // Lophine 自定义修改：记录每次由玩家确认执行的空岛删除，便于追溯反复重置行为。
            Players owner = island.getOwner();
            logger.info("Island deletion confirmed by player {} ({}), island={}, owner={} ({}), worlds-to-delete={}",
                    player.getName(),
                    player.getUniqueId(),
                    island.getId(),
                    owner == null ? "unknown" : owner.getLastKnowName(),
                    owner == null ? "unknown" : owner.getMojangId(),
                    ConfigLoader.worldManager.getWorldConfigs().entrySet().stream()
                            .filter(entry -> entry.getValue().shouldDeleteIsland())
                            .count());

            skyblockManager.setLockedIsland(island, true);

            boolean isDisabled = island.setDisable(true);
            if (isDisabled) {
                this.updatePlayer(skyblockManager, island);
                this.kickAllPlayerOnIsland(island);

                List<String> worldsToDelete = ConfigLoader.worldManager.getWorldConfigs().entrySet().stream()
                        .filter(entry -> entry.getValue().shouldDeleteIsland())
                        .map(Map.Entry::getKey)
                        .toList();
                AtomicInteger worldsLeft = new AtomicInteger(worldsToDelete.size());
                AtomicBoolean failed = new AtomicBoolean(false);

                if (worldsLeft.get() == 0) {
                    finalizeDeletion(skyblockManager, island, false, player);
                    return;
                }

                worldsToDelete.forEach(worldName -> {
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        failed.set(true);
                        logger.log(Level.FATAL, "Failed to delete island {} in world {}: world not loaded", island.getId(), worldName);
                        if (worldsLeft.decrementAndGet() == 0) {
                            finalizeDeletion(skyblockManager, island, failed.get(), player);
                        }
                        return;
                    }

                    Skyllia.getInstance().getInterneAPI().getWorldModifier().deleteIsland(island, world, ConfigLoader.general.getIslandSettings().regionDistance(), (success) -> {
                        if (!success) failed.set(true);
                        if (worldsLeft.decrementAndGet() == 0) {
                            finalizeDeletion(skyblockManager, island, failed.get(), player);
                        }
                    });
                });
            } else {
                pendingDeleteConfirmations.remove(player.getUniqueId(), DELETION_IN_PROGRESS);
                ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
            }
        } catch (Exception e) {
            pendingDeleteConfirmations.remove(player.getUniqueId(), DELETION_IN_PROGRESS);
            logger.log(Level.FATAL, e.getMessage(), e);
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        }
    }

    // Lophine 自定义修改：创建 15 秒确认窗口；窗口内再次执行 /is delete confirm 才会真正删除。
    private void startDeleteConfirmation(@NotNull Plugin plugin, @NotNull Player player) {
        UUID playerId = player.getUniqueId();
        long now = System.nanoTime();
        long expiresAt = now + DELETE_CONFIRM_WINDOW_NANOS;
        Long existingExpiresAt = pendingDeleteConfirmations.putIfAbsent(playerId, expiresAt);
        if (existingExpiresAt != null && existingExpiresAt == DELETION_IN_PROGRESS) {
            return;
        }
        if (existingExpiresAt != null && existingExpiresAt <= now) {
            if (!pendingDeleteConfirmations.replace(playerId, existingExpiresAt, expiresAt)) {
                return;
            }
            existingExpiresAt = null;
        }
        if (existingExpiresAt == null) {
            player.getScheduler().runDelayed(plugin,
                    scheduledTask -> pendingDeleteConfirmations.remove(playerId, expiresAt),
                    () -> pendingDeleteConfirmations.remove(playerId, expiresAt),
                    300L);
        }
        ConfigLoader.language.sendMessage(player, "island.delete.confirm", Map.of(), false);
    }

    // Lophine 自定义修改：原子消费确认窗口，确保并发执行 confirm 时最多只有一次会进入删除流程。
    private boolean tryStartDeletion(UUID playerId) {
        Long expiresAt = pendingDeleteConfirmations.get(playerId);
        if (expiresAt == null || expiresAt == DELETION_IN_PROGRESS || expiresAt <= System.nanoTime()) {
            return false;
        }
        return pendingDeleteConfirmations.replace(playerId, expiresAt, DELETION_IN_PROGRESS);
    }

    // Lophine 自定义修改：删除执行期间阻止重复 confirm 再次进入删除流程。
    private boolean isDeletionInProgress(UUID playerId) {
        Long state = pendingDeleteConfirmations.get(playerId);
        return state != null && state == DELETION_IN_PROGRESS;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            if ("confirm".startsWith(partial)) {
                return Collections.singletonList("confirm");
            }
        }
        return Collections.emptyList();
    }

    private void updatePlayer(SkyblockManager skyblockManager, Island island) {
        for (Players players : island.getMembers()) {
            players.setRoleType(RoleType.VISITOR);
            island.updateMember(players);
            checkClearPlayer(skyblockManager, players, RemovalCause.ISLAND_DELETED);
        }
    }

    private void finalizeDeletion(SkyblockManager skyblockManager, Island island, boolean failed, Player player) {
        pendingDeleteConfirmations.remove(player.getUniqueId(), DELETION_IN_PROGRESS);
        boolean lockResult = skyblockManager.setLockedIsland(island, failed);
        if (!lockResult) {
            logger.log(Level.FATAL, "Failed to update lock state for island {} after deletion", island.getId());
            if (player.isOnline()) {
                ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
            }
            return;
        }

        if (!player.isOnline()) {
            return;
        }

        if (failed) {
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        } else {
            ConfigLoader.language.sendMessage(player, "island.delete-success");
        }
    }

    private void kickAllPlayerOnIsland(final Island island) {
        for (WorldConfig worldConfig : WorldUtils.getWorldConfigs()) {
            RegionUtils.getEntitiesInRegion(Skyllia.getInstance(), ConfigLoader.general.getIslandSettings().regionDistance(), EntityType.PLAYER, worldConfig.getWorld(), island.getRegionCoordinate(), island.getSize(), entity -> {
                Player playerInIsland = (Player) entity;
                if (PlayerUtils.hasPermission(playerInIsland, "skyllia.island.command.access.bypass")) return;
                PlayerUtils.teleportPlayerSpawn(playerInIsland);
            });
        }
    }
}