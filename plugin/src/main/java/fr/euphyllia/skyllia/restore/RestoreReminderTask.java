package fr.euphyllia.skyllia.restore;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本服务端项目自定义修改：定时提醒在线管理员对世界存档做全量备份。
 *
 * <p>存档恢复（热回档）依赖外部全量备份；本任务按配置间隔向在线管理员与控制台提醒，
 * 降低"备份过期"导致恢复不到期望数据的风险。间隔为 0 时关闭。</p>
 *
 * <p>实例级、无静态可变状态；任务在插件禁用时由 {@code cancelTasks} 一并取消。</p>
 */
public final class RestoreReminderTask {

    private static final Logger LOGGER = LogManager.getLogger(RestoreReminderTask.class);
    private static final String ALERT_PERMISSION = "skyllia.admins.commands.restore";
    private final Skyllia plugin;
    private final AtomicBoolean started = new AtomicBoolean();

    public RestoreReminderTask(Skyllia plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int hours = ConfigLoader.general.getRestoreSettings().reminderIntervalHours();
        if (hours <= 0) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> remind(), hours, hours, TimeUnit.HOURS);
            LOGGER.info("存档备份提醒已启用，每 {} 小时提醒一次在线管理员", hours);
        } catch (RuntimeException exception) {
            started.set(false);
            LOGGER.warn("无法启动存档备份提醒任务", exception);
        }
    }

    private void remind() {
        if (!plugin.isEnabled()) {
            return;
        }
        Component message = Component.text("[存档备份提醒] ", NamedTextColor.RED)
                .append(Component.text("建议定期对服务器 worlds 目录做全量快照备份，"
                        + "以便区块损坏时用 /skylliadmin restore 恢复。", NamedTextColor.YELLOW));
        LOGGER.warn("存档备份提醒：建议定期对 worlds 目录做全量快照备份。");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.isOp() && !online.hasPermission(ALERT_PERMISSION)) {
                continue;
            }
            try {
                online.getScheduler().execute(plugin, () -> online.sendMessage(message), null, 1L);
            } catch (RuntimeException exception) {
                LOGGER.warn("无法向 {} 发送存档备份提醒", online.getName(), exception);
            }
        }
    }
}
