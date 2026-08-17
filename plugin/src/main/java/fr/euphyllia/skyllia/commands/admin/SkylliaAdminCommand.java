package fr.euphyllia.skyllia.commands.admin;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.commands.SkylliaCommandInterface;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.commands.SubCommandRegistry;
import fr.euphyllia.skyllia.commands.admin.subcommands.AdminSetDescriptionCommand;
import fr.euphyllia.skyllia.commands.admin.subcommands.ClaimSubCommand;
import fr.euphyllia.skyllia.commands.admin.subcommands.CurrentSubCommands;
import fr.euphyllia.skyllia.commands.admin.subcommands.ForceCreateSubCommands;
import fr.euphyllia.skyllia.commands.admin.subcommands.ForceDeleteSubCommands;
import fr.euphyllia.skyllia.commands.admin.subcommands.ForceTransferSubCommands;
import fr.euphyllia.skyllia.commands.admin.subcommands.InfoSubCommand;
import fr.euphyllia.skyllia.commands.admin.subcommands.PurgeSubCommand;
import fr.euphyllia.skyllia.commands.admin.subcommands.ReloadSubCommands;
import fr.euphyllia.skyllia.commands.admin.subcommands.SchematicSubCommands;
import fr.euphyllia.skyllia.commands.admin.subcommands.SetHeightSubCommands;
import fr.euphyllia.skyllia.commands.admin.subcommands.SetMaxMembersSubCommands;
import fr.euphyllia.skyllia.commands.admin.subcommands.SetSizeSubCommands;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class SkylliaAdminCommand implements SkylliaCommandInterface {

    private final Skyllia plugin;
    private final SubCommandRegistry registry;

    public SkylliaAdminCommand(Skyllia Skyllia) {
        this.plugin = Skyllia;
        this.registry = this.plugin.getAdminCommandRegistry();
        registerDefaultCommands();
    }

    @Override
    public void execute(CommandSourceStack sender, String @NotNull [] args) {
        Player player = sender.getSender() instanceof Player ? (Player) sender.getSender() : null;
        if (!sender.getSender().hasPermission(permission())) {
            ConfigLoader.language.sendMessage(player != null ? player : sender.getSender(), "island.player.permission-denied");
            return;
        }
        if (args.length != 0) {
            String subCommand = args[0].trim().toLowerCase();
            String[] listArgs = Arrays.copyOfRange(args, 1, args.length);
            SubCommandInterface subCommandInterface = registry.getSubCommandByName(subCommand);
            if (subCommandInterface == null) {
                ConfigLoader.language.sendMessage(player != null ? player : sender.getSender(), "misc.unknown-command");
                return;
            }
            Bukkit.getAsyncScheduler().runNow(this.plugin, task ->
                    subCommandInterface.onExecute(this.plugin, sender.getSender(), listArgs));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NonNull CommandSourceStack sender, String[] args) {
        if (!sender.getSender().hasPermission(permission())) {
            return Collections.emptyList();
        }
        Set<String> commands = registry.getCommandMap().keySet();
        if (args.length == 0) {
            return commands;
        } else if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            return commands.stream().filter(command -> command.toLowerCase().startsWith(partial)).toList();
        } else {
            String subCommand = args[0].trim().toLowerCase();
            String[] listArgs = Arrays.copyOfRange(args, 1, args.length);
            SubCommandInterface subCommandInterface = registry.getSubCommandByName(subCommand);
            if (subCommandInterface != null) {
                return subCommandInterface.onTabComplete(this.plugin, sender.getSender(), listArgs);
            }
        }
        return Collections.emptyList();
    }

    private void registerDefaultCommands() {
        registry.registerSubCommand(new CurrentSubCommands(), "current");
        registry.registerSubCommand(new ForceDeleteSubCommands(), "force_delete", "forcedelete");
        registry.registerSubCommand(new ForceTransferSubCommands(), "force_transfer", "forcetransfer");
        registry.registerSubCommand(new InfoSubCommand(), "info", "information");
        registry.registerSubCommand(new ReloadSubCommands(), "reload");
        registry.registerSubCommand(new SetMaxMembersSubCommands(), "set_max_member", "setmaxmembers");
        registry.registerSubCommand(new SetSizeSubCommands(), "set_size", "setsize");
        registry.registerSubCommand(new SetHeightSubCommands(), "set_height", "setheight");
        registry.registerSubCommand(new SchematicSubCommands(), "schematic", "schem");
        registry.registerSubCommand(new ForceCreateSubCommands(), "create");
        registry.registerSubCommand(new ClaimSubCommand(), "claim");
        registry.registerSubCommand(new PurgeSubCommand(), "purge");

        // extra
        registry.registerSubCommand(new AdminSetDescriptionCommand(), "set_name", "setname");
        registry.registerSubCommand(new AdminSetDescriptionCommand(), "set_description", "setdescription");
    }

    @Override
    public @NotNull String permission() {
        return "skyllia.admins.commands";
    }
}
