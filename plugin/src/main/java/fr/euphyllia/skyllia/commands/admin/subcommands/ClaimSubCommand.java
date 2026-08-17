package fr.euphyllia.skyllia.commands.admin.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.event.SkyblockCreateEvent;
import fr.euphyllia.skyllia.api.event.SkyblockLoadEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.HeightType;
import fr.euphyllia.skyllia.api.skyblock.model.IslandSettings;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.skyblock.model.SchematicPlugin;
import fr.euphyllia.skyllia.api.skyblock.model.SchematicSetting;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.managers.skyblock.SkyblockManager;
import fr.euphyllia.skyllia.utils.IslandUtils;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Assigns the executor's current, unclaimed region to a player as a new island.
 */
public class ClaimSubCommand implements SubCommandInterface {

    private static final Logger LOGGER = LogManager.getLogger(ClaimSubCommand.class);

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player executor)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        if (!executor.isOp()) {
            ConfigLoader.language.sendMessage(executor, "island.player.permission-denied");
            return;
        }
        if (args.length != 1) {
            ConfigLoader.language.sendMessage(executor, "island.admin.claim-args-missing");
            return;
        }

        String targetName = args[0];
        executor.getScheduler().execute(plugin, () -> {
            if (!executor.isOnline()) {
                return;
            }

            Location location = executor.getLocation();
            if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) {
                ConfigLoader.language.sendMessage(executor, "island.admin.claim-not-in-skyblock-world");
                return;
            }

            RegionCoordinate region = RegionHelper.getRegionCoordinateFromLocation(location);
            Bukkit.getAsyncScheduler().runNow(plugin, task -> claim(executor, targetName, region));
        }, null, 1L);
    }

    private void claim(Player executor, String requestedPlayer, RegionCoordinate region) {
        UUID targetId = Bukkit.getPlayerUniqueId(requestedPlayer);
        if (targetId == null) {
            ConfigLoader.language.sendMessage(executor, "island.admin.player-not-found");
            return;
        }

        SkyblockManager skyblockManager = Skyllia.getInstance().getInterneAPI().getSkyblockManager();
        if (SkylliaAPI.getIslandByChunk(region.x() << 5, region.z() << 5) != null) {
            ConfigLoader.language.sendMessage(executor, "island.admin.claim-region-occupied");
            return;
        }
        if (skyblockManager.getIslandByPlayerId(targetId) != null) {
            ConfigLoader.language.sendMessage(executor, "island.admin.create-already-has-island");
            return;
        }

        String schematicKey = resolveDefaultSchematicKey();
        if (schematicKey == null) {
            ConfigLoader.language.sendMessage(executor, "island.schematic-not-exist");
            return;
        }

        Map<String, SchematicSetting> schematicSettings = IslandUtils.getSchematic(schematicKey);
        IslandSettings islandSettings = IslandUtils.getIslandSettings(schematicKey);
        if (schematicSettings == null || schematicSettings.isEmpty() || islandSettings == null) {
            ConfigLoader.language.sendMessage(executor, "island.schematic-not-exist");
            return;
        }

        String targetName = Bukkit.getOfflinePlayer(targetId).getName();
        if (targetName == null || targetName.isBlank()) {
            targetName = requestedPlayer;
        }
        final String resolvedTargetName = targetName;

        ConfigLoader.language.sendMessage(executor, "island.create-in-progress");
        UUID islandId = UUID.randomUUID();
        Players owner = new Players(targetId, resolvedTargetName, null, RoleType.OWNER);
        if (!skyblockManager.createIslandAtRegion(islandId, islandSettings, owner, region)) {
            ConfigLoader.language.sendMessage(executor, "island.admin.claim-region-occupied");
            return;
        }

        Island island = SkylliaAPI.getIslandByIslandId(islandId);
        if (island == null) {
            ConfigLoader.language.sendMessage(executor, "island.generic-error");
            return;
        }

        new SkyblockCreateEvent(island, targetId).callEvent();
        pasteAllSchematics(island, schematicSettings, targetId)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Claimed island creation failed for {}: {}", island.getId(), throwable.getMessage(), throwable);
                        ConfigLoader.language.sendMessage(executor, "island.generic-error");
                        return;
                    }
                    ConfigLoader.language.sendMessage(executor, "island.admin.claim-success", Map.of("%player%", resolvedTargetName));
                });
    }

    private String resolveDefaultSchematicKey() {
        List<String> schematicKeys = ConfigLoader.schematicManager.getIslandTypes();
        if (schematicKeys.isEmpty()) {
            return null;
        }

        String defaultKey = ConfigLoader.islandManager.getDefaultIslandKey();
        return defaultKey != null && schematicKeys.contains(defaultKey) ? defaultKey : schematicKeys.getFirst();
    }

    private CompletableFuture<Void> pasteAllSchematics(Island island,
                                                       Map<String, SchematicSetting> schematicMap,
                                                       UUID ownerId) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        AtomicBoolean isFirst = new AtomicBoolean(true);

        for (Map.Entry<String, SchematicSetting> entry : schematicMap.entrySet()) {
            String worldName = entry.getKey();
            SchematicSetting setting = entry.getValue();
            boolean first = isFirst.getAndSet(false);

            chain = chain.thenCompose(ignored -> {
                Location center = RegionHelper.getCenterRegion(
                        Bukkit.getWorld(worldName),
                        island.getRegionCoordinate().x(),
                        island.getRegionCoordinate().z()
                );
                center.setY(setting.height());
                island.setCenterLocation(center);

                return Skyllia.getInstance().getInterneAPI()
                        .getSchematicHook(SchematicPlugin.fromString(setting.plugin()))
                        .paste(center, setting)
                        .thenComposeAsync(success -> {
                            if (!success) {
                                island.setDisable(true);
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                        "Schematic paste failed for world " + worldName));
                            }
                            if (setting.minBuildHeight() != null) {
                                island.setBuildHeight(worldName, HeightType.MIN, setting.minBuildHeight());
                            }
                            if (setting.maxBuildHeight() != null) {
                                island.setBuildHeight(worldName, HeightType.MAX, setting.maxBuildHeight());
                            }
                            if (!first) {
                                return CompletableFuture.completedFuture(null);
                            }

                            island.addWarps("home", center, true);
                            island.setSpawnLocation(center);
                            Skyllia.getInstance().getInterneAPI().getSkyblockManager().cacheIslandAndIndex(island);
                            new SkyblockLoadEvent(island).callEvent();

                            Player onlineOwner = Bukkit.getPlayer(ownerId);
                            return onlineOwner == null
                                    ? CompletableFuture.completedFuture(null)
                                    : teleportAndApplyBorder(onlineOwner, island, center);
                        }, command -> Bukkit.getAsyncScheduler().runNow(Skyllia.getInstance(), task -> command.run()));
            });
        }
        return chain;
    }

    private CompletableFuture<Void> teleportAndApplyBorder(Player player, Island island, Location center) {
        Location spawn = center.clone().add(0.5, 0.1, 0.5);
        return player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (!success) {
                return;
            }
            player.setVelocity(new Vector(0, 0, 0));
            player.setFallDistance(0);
        });
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length != 1) {
            return Collections.emptyList();
        }
        String partial = args[0].trim().toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partial))
                .sorted()
                .collect(Collectors.toList());
    }
}
