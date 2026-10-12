package fr.euphyllia.skyllia.commands.common;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SkylliaCommandInterface;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.commands.SubCommandRegistry;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.commands.common.subcommands.*;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class SkylliaCommand implements SkylliaCommandInterface {

    private final Skyllia plugin;
    private final SubCommandRegistry registry;
    private final Logger logger = LogManager.getLogger(this);
    // 本服务端项目自定义修改：由外部注入，便于插件启动时启动异常告警轮询
    private final TopSubCommand topSubCommand;

    public SkylliaCommand(Skyllia Skyllia, TopSubCommand topSubCommand) {
        this.plugin = Skyllia;
        this.registry = this.plugin.getCommandRegistry();
        this.topSubCommand = topSubCommand;
        registerDefaultCommands();
    }

    private void registerDefaultCommands() {
        registry.registerSubCommand(new AccessSubCommand(), "access");
        registry.registerSubCommand(new BanListCommand(), "banlist", "banned");
        registry.registerSubCommand(new BanSubCommand(), "ban");
        registry.registerSubCommand(new SetBiomeSubCommand(), "biome");
        registry.registerSubCommand(new CreateSubCommand(), "create");
        registry.registerSubCommand(new DeleteSubCommand(), "delete");
        registry.registerSubCommand(new DelWarpSubCommand(), "delwarp");
        registry.registerSubCommand(new DemoteSubCommand(), "demote");
        registry.registerSubCommand(new ExpelSubCommand(), "expel");
        registry.registerSubCommand(new HomeSubCommand(), "home", "go", "tp");
        registry.registerSubCommand(new InfoSubCommand(), "info");
        registry.registerSubCommand(new InviteSubCommand(), "invite", "add");
        registry.registerSubCommand(new KickSubCommand(), "kick");
        registry.registerSubCommand(new LeaveSubCommand(), "leave");
        // 本服务端项目自定义修改：/is near 显示四方向邻接岛屿的岛主
        registry.registerSubCommand(new NearSubCommand(), "near");
        registry.registerSubCommand(new PermissionSubCommand(), "permission");
        registry.registerSubCommand(new FlagSubCommand(), "flag", "gamerule");
        registry.registerSubCommand(new PromoteSubCommand(), "promote");
        registry.registerSubCommand(new TPSSubCommand(), "tps", "lag", "mspt");
        registry.registerSubCommand(topSubCommand, "top");
        registry.registerSubCommand(new TransferSubCommand(), "transfer");
        registry.registerSubCommand(new TrustSubCommand(), "trust");
        registry.registerSubCommand(new SetHomeSubCommand(), "sethome");
        registry.registerSubCommand(new SetSpawnSubCommand(), "setspawn");
        registry.registerSubCommand(new SetVisitSubCommand(), "setvisit");
        registry.registerSubCommand(new SetWarpSubCommand(), "setwarp");
        registry.registerSubCommand(new UnbanSubCommand(), "unban");
        registry.registerSubCommand(new UntrustSubCommand(), "untrust");
        registry.registerSubCommand(new VisitSubCommand(), "visit");
        registry.registerSubCommand(new WarpSubCommand(), "warp");

        // extra
        registry.registerSubCommand(new SetNameCommand(plugin), "set_name", "setname");
        registry.registerSubCommand(new SetDescriptionCommand(plugin), "set_description", "setdescription");
    }

    @Override
    public void execute(@NonNull CommandSourceStack sender, String[] args) {
        if (args.length != 0) {
            String subCommand = args[0].trim().toLowerCase();
            String[] listArgs = Arrays.copyOfRange(args, 1, args.length);
            SubCommandInterface subCommandInterface = registry.getSubCommandByName(subCommand);
            if (subCommandInterface == null) {
                ConfigLoader.language.sendMessage(sender.getSender(), "misc.unknown-command");
                return;
            }
            Bukkit.getAsyncScheduler().runNow(this.plugin, task ->
                    subCommandInterface.onExecute(this.plugin, sender.getSender(), listArgs));
        } else {
            if (sender.getSender() instanceof Player player) {
                Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
                //logger.info("接受命令：玩家 {}", player.getName());
                if (island == null) {
                    //logger.info("玩家 {} 没有岛屿", player.getName());
                    Bukkit.getAsyncScheduler().runNow(this.plugin, task ->
                            registry.getSubCommandByName("create").onExecute(this.plugin, sender.getSender(), args));
                }
                else{
                    //logger.info("玩家 {} 有岛屿", player.getName());
                    Bukkit.getAsyncScheduler().runNow(this.plugin, task ->
                            registry.getSubCommandByName("home").onExecute(this.plugin, sender.getSender(), args));
                }
                // If no subcommand is provided, we can default to the "create" command
            }
            //else logger.info("接受命令：非玩家");
        }
    }

    @Override
    public @NonNull Collection<String> suggest(@NonNull CommandSourceStack sender, String[] args) {
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

    @Override
    public @Nullable String permission() {
        return null;
    }
}
