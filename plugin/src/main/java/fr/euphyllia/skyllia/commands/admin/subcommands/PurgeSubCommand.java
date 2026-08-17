package fr.euphyllia.skyllia.commands.admin.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class PurgeSubCommand implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, "skyllia.admins.commands.island.purge")) {
            ConfigLoader.language.sendMessage(sender, "island.player.permission-denied");
            return;
        }

        if (!(plugin instanceof Skyllia skyllia) || skyllia.getInactiveIslandPurgeService() == null) {
            sender.sendMessage("[Skyllia] Inactive island purge service is not available.");
            return;
        }

        boolean started = skyllia.getInactiveIslandPurgeService().runNow();
        if (started) {
            sender.sendMessage("[Skyllia] Inactive island purge check completed. Purge actions, if any, were queued. Check console logs for details.");
        } else {
            sender.sendMessage("[Skyllia] Inactive island purge is already running.");
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        return List.of();
    }
}
