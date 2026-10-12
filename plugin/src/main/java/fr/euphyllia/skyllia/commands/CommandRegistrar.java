package fr.euphyllia.skyllia.commands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.analytics.IslandBlockTopStore;
import fr.euphyllia.skyllia.api.commands.SubCommandRegistry;
import fr.euphyllia.skyllia.commands.admin.SkylliaAdminCommand;
import fr.euphyllia.skyllia.commands.admin.SubAdminCommandImpl;
import fr.euphyllia.skyllia.commands.common.SkylliaCommand;
import fr.euphyllia.skyllia.commands.common.SubCommandImpl;
import fr.euphyllia.skyllia.commands.common.subcommands.TopSubCommand;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;

/**
 * This class handles the registration of all plugin commands using Paper's Brigadier.
 */
@SuppressWarnings("UnstableApiUsage")
public class CommandRegistrar {

    private final Skyllia plugin;
    private final SubCommandRegistry commandRegistry;
    private final SubCommandRegistry adminCommandRegistry;
    // 本服务端项目自定义修改：复用的 TopSubCommand 实例，使告警轮询随插件启动
    private final TopSubCommand topSubCommand;
    // 本服务端项目自定义修改：方块类排名结果存储，供光荣榜 GUI 读取
    private final IslandBlockTopStore blockTopStore;

    /**
     * Constructs a CommandRegistrar that will register Skyllia commands.
     *
     * @param plugin The Skyllia plugin instance
     */
    public CommandRegistrar(Skyllia plugin) {
        this.plugin = plugin;

        // Création des SubCommandRegistry
        this.commandRegistry = new SubCommandImpl();
        this.adminCommandRegistry = new SubAdminCommandImpl();
        // 本服务端项目自定义修改：方块类排名结果存储（持久化到 analytics/island-block-top.json）
        this.blockTopStore = new IslandBlockTopStore(plugin.getDataFolder().toPath()
                .resolve("analytics").resolve("island-block-top.json"));
        // 本服务端项目自定义修改：提前创建 TopSubCommand，以便启动其告警轮询任务
        this.topSubCommand = new TopSubCommand(plugin, blockTopStore);
    }

    /**
     * Registers all plugin commands via the Paper lifecycle command event.
     */
    public void registerCommands() {
        LifecycleEventManager<@org.jetbrains.annotations.NotNull Plugin> manager = plugin.getLifecycleManager();

        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                    "skyllia",
                    "Islands commands",
                    java.util.List.of("is", "ob"),
                    new SkylliaCommand(plugin, topSubCommand)
            );

            commands.register(
                    "skylliadmin",
                    "island.administrator commands",
                    java.util.List.of("isadmin", "skylliaadmin"),
                    new SkylliaAdminCommand(plugin)
            );
        });
    }

    // 本服务端项目自定义修改：启动光荣榜告警轮询任务，使异常检测在无人使用命令时也始终在线
    public void startTopAlertPolling() {
        this.topSubCommand.startPolling(this.plugin);
    }

    // 本服务端项目自定义修改：方块类排名结果存储
    public IslandBlockTopStore getBlockTopStore() {
        return blockTopStore;
    }

    public SubCommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    public SubCommandRegistry getAdminCommandRegistry() {
        return adminCommandRegistry;
    }
}