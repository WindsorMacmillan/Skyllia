package fr.euphyllia.skyllia.commands.admin.subcommands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.event.IslandInfoEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import net.kyori.adventure.text.Component;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class InfoSubCommand implements SubCommandInterface {
    /**
     * Handles the execution of a sub-command.
     *
     * @param plugin the {@link Plugin} instance that is executing the command
     * @param sender the {@link CommandSender} who issued the command
     * @param args   the arguments provided with the command
     */
    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        Player player = sender instanceof Player currentPlayer ? currentPlayer : null;
        if (args.length == 0 && player == null) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        Island island;
        if (args.length == 1) {
            UUID playerId = Bukkit.getPlayerUniqueId(args[0]);
            if (playerId == null) {
                ConfigLoader.language.sendMessage(sender, "island.player.not-found");
                return;
            }
            island = SkylliaAPI.getIslandByPlayerId(playerId);
        } else {
            if (player == null) {
                ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
                return;
            }
            if (!SkylliaAPI.isWorldSkyblock(player.getWorld())) {
                ConfigLoader.language.sendMessage(sender, "island.info.wilderness");
                return;
            }
            int chunkX = player.getLocation().getBlockX() >> 4;
            int chunkZ = player.getLocation().getBlockZ() >> 4;
            island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        }

        if (island == null) {
            ConfigLoader.language.sendMessage(sender, "island.info.wilderness");
            return;
        }

        UUID islandId = island.getId();
        Optional<Players> found = island.getMembers().stream().filter(p -> p.getRoleType() == RoleType.OWNER).findFirst();
        Players leader = found.orElse(null);
        if (leader == null) {
            ConfigLoader.language.sendMessage(sender, "island.info.no-owner");
            return;
        }

        Location center = RegionHelper.getCenterRegion(null, island.getRegionCoordinate().x(), island.getRegionCoordinate().z());
        Timestamp createdAt = island.getCreateDate();
        int maxMembers = island.getMaxMembers();
        List<Players> members = island.getMembers();
        long now = System.currentTimeMillis();
        boolean someoneOnline = members.stream().anyMatch(member -> {
            Player online = Bukkit.getPlayer(member.getMojangId());
            return online != null && online.isOnline();
        });
        String inactive = someoneOnline ? "活跃" : latestInactiveDuration(members, now);

        Component infoComponent = Component.text("")
                .append(translate(sender, "island.info.display.title")).append(Component.newline())
                .append(translate(sender, "island.info.display.id", Map.of("%id%", islandId.toString()))).append(Component.newline())
                .append(translate(sender, "island.info.display.owner", Map.of(
                        "%owner%", leader.getLastKnowName(), "%owner_uuid%", leader.getMojangId().toString()))).append(Component.newline())
                .append(translate(sender, "island.info.display.private", Map.of("%private%", island.isPrivateIsland() ? "Yes" : "No"))).append(Component.newline())
                .append(translate(sender, "island.info.display.max-members", Map.of("%max%", String.valueOf(maxMembers)))).append(Component.newline())
                .append(translate(sender, "island.info.display.created", Map.of("%created%", createdAt.toString()))).append(Component.newline())
                .append(translate(sender, "island.info.display.online-members", Map.of(
                        "%online%", String.valueOf(members.stream().filter(p -> {
                            Player online = Bukkit.getPlayer(p.getMojangId());
                            return online != null && online.isOnline();
                        }).count()),
                        "%total%", String.valueOf(members.size())
                ))).append(Component.newline())
                .append(translate(sender, "island.info.display.inactive", Map.of(
                        "%duration%", inactive))).append(Component.newline())
                .append(translate(sender, "island.info.display.location", Map.of(
                        "%x%", String.valueOf(center.getBlockX()),
                        "%z%", String.valueOf(center.getBlockZ())
                )));


        if (player != null) {
            IslandInfoEvent infoEvent = new IslandInfoEvent(player, island);
            infoEvent.callEvent();
            for (Component extra : infoEvent.getExtraMessages()) {
                infoComponent = infoComponent.append(Component.newline()).append(extra);
            }
        }

        if (!members.isEmpty()) {
            infoComponent = infoComponent.append(Component.newline()).append(translate(sender, "island.info.display.members-title"));
            for (Players member : members) {
                Player onlinePlayer = Bukkit.getPlayer(member.getMojangId());
                boolean online = onlinePlayer != null && onlinePlayer.isOnline();
                String color = online ? "<green>" : "<red>";
                String offlineDuration = online ? "" : offlineDuration(member.getMojangId(), now);
                infoComponent = infoComponent.append(Component.newline()).append(
                        translate(sender, "island.info.display.member-line", Map.of(
                                 "%color%", color,
                                 "%role%", member.getRoleType().name(),
                                 "%name%", member.getLastKnowName(),
                                 "%offline%", offlineDuration
                        ))
                );
            }
        }

        sender.sendMessage(infoComponent);
    }

    private static Component translate(CommandSender sender, String key) {
        if (sender instanceof Player player) {
            return ConfigLoader.language.translate(player, key);
        }
        return ConfigLoader.language.translate(key, Map.of());
    }

    private static Component translate(CommandSender sender, String key, Map<String, String> placeholders) {
        if (sender instanceof Player player) {
            return ConfigLoader.language.translate(player, key, placeholders);
        }
        return ConfigLoader.language.translate(key, placeholders);
    }

    private static String latestInactiveDuration(List<Players> members, long now) {
        long latest = Long.MIN_VALUE;
        for (Players member : members) {
            String raw = lastPlayed(member.getMojangId());
            if (raw == null) continue;
            try {
                latest = Math.max(latest, Long.parseLong(raw));
            } catch (NumberFormatException ignored) {
            }
        }
        return latest == Long.MIN_VALUE ? "Unknown" : formatDuration(Math.max(0L, now - latest));
    }

    private static String offlineDuration(UUID playerId, long now) {
        String raw = lastPlayed(playerId);
        if (raw == null) return "Unknown";
        try {
            return formatDuration(Math.max(0L, now - Long.parseLong(raw)));
        } catch (NumberFormatException ignored) {
            return "Unknown";
        }
    }

    private static String lastPlayed(UUID playerId) {
        try {
            String value = PlaceholderAPI.setPlaceholders(Bukkit.getOfflinePlayer(playerId), "%player_last_played%");
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        return days + "d " + hours + "h";
    }

    /**
     * Provides tab completion suggestions for the sub-command.
     *
     * @param plugin the {@link Plugin} instance that is executing the command
     * @param sender the {@link CommandSender} who is tab completing
     * @param args   the arguments provided with the command so far
     * @return a {@link List} of suggestions for tab completion, or {@code null} if no suggestions are available
     */
    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            return new ArrayList<>(Bukkit.getOnlinePlayers()).stream()
                    .map(CommandSender::getName)
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public String permission() {
        return "skyllia.admins.commands.island.info";
    }
}
