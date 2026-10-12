package fr.euphyllia.skyllia.commands.admin.subcommands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.commands.common.subcommands.PermissionSubCommand;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class AdminPermissionSubCommand implements SubCommandInterface {
    private static final List<String> ACTIONS = List.of("list", "get", "set", "toggle");
    private static final List<String> BOOLS = List.of("true", "false", "on", "off");
    private final PermissionSubCommand delegate = new PermissionSubCommand();

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            ConfigLoader.language.sendMessage(sender, "island.permission.usage");
            return;
        }
        var targetId = Bukkit.getPlayerUniqueId(args[0]);
        if (targetId == null) {
            ConfigLoader.language.sendMessage(sender, "island.player.not-found");
            return;
        }
        Island island = SkylliaAPI.getIslandByPlayerId(targetId);
        if (island == null) {
            ConfigLoader.language.sendMessage(sender, "island.player.no-island");
            return;
        }
        delegate.executeForIsland(plugin, sender, island, Arrays.copyOfRange(args, 1, args.length));
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream().map(CommandSender::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .sorted().collect(Collectors.toList());
        }
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        if (tail.length == 1) {
            String partial = tail[0].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(a -> a.startsWith(partial)).toList();
        }
        int offset = ACTIONS.contains(tail[0].toLowerCase(Locale.ROOT)) ? 1 : 0;
        if (tail.length == offset + 1) {
            String partial = tail[offset].toLowerCase(Locale.ROOT);
            return Arrays.stream(RoleType.values()).map(Enum::name)
                    .filter(role -> role.toLowerCase(Locale.ROOT).startsWith(partial)).sorted().toList();
        }
        if (tail.length == offset + 2) {
            String partial = tail[offset + 1].toLowerCase(Locale.ROOT);
            PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
            return registry.keys().stream().map(AdminPermissionSubCommand::key)
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(partial)).sorted().limit(50).toList();
        }
        if (tail.length == offset + 3) {
            String partial = tail[offset + 2].toLowerCase(Locale.ROOT);
            return BOOLS.stream().filter(value -> value.startsWith(partial)).toList();
        }
        return List.of();
    }

    private static String key(NamespacedKey key) {
        return key.getNamespace() + ":" + key.getKey();
    }
}
