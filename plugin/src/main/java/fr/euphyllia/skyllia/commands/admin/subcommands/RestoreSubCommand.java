package fr.euphyllia.skyllia.commands.admin.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.restore.IslandRestoreService;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本服务端项目自定义修改：岛屿存档恢复（热回档）管理命令。
 *
 * <p>用法：</p>
 * <pre>
 * /skylliadmin restore &lt;玩家&gt; [维度] [区块X 区块Z] confirm
 * /skylliadmin restore status [玩家|all]
 * </pre>
 * <p>不带 {@code confirm} 时仅给出提示与预览；维度可选（默认全部已配置维度）；
 * 指定区块坐标时只恢复该区块，否则恢复整岛区域。</p>
 */
public class RestoreSubCommand implements SubCommandInterface {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PERMISSION = "skyllia.admins.commands.restore";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, PERMISSION)) {
            sender.sendMessage(LEGACY.deserialize("&c你没有权限执行该命令。"));
            return;
        }
        if (!(plugin instanceof Skyllia skyllia)) {
            return;
        }
        IslandRestoreService service = skyllia.getIslandRestoreService();
        if (service == null) {
            sender.sendMessage(LEGACY.deserialize("&c存档恢复服务未初始化。"));
            return;
        }

        // status 子命令
        if (args.length >= 1 && args[0].equalsIgnoreCase("status")) {
            sendStatus(sender, service, args.length >= 2 ? args[1] : null);
            return;
        }

        // check 子命令（只读诊断，不修改任何数据）
        if (args.length >= 2 && args[0].equalsIgnoreCase("check")) {
            Island island = findIsland(args[1]);
            if (island == null) {
                sender.sendMessage(LEGACY.deserialize("&c未找到玩家 &e" + args[1] + " &c或该玩家没有岛屿。"));
                return;
            }
            sender.sendMessage(LEGACY.deserialize("&7===== 恢复源诊断 ====="));
            for (String line : service.describeSources(island)) {
                sender.sendMessage(LEGACY.deserialize("&7" + line));
            }
            // 若指定了维度则进一步做只读检查
            if (args.length >= 3) {
                World.Environment env = parseEnvironment(args[2]);
                if (env == null) {
                    sender.sendMessage(LEGACY.deserialize("&c未知维度 &e" + args[2]));
                    return;
                }
                sender.sendMessage(LEGACY.deserialize("&7===== 只读检查（" + args[2] + "）====="));
                for (String line : service.checkSource(island, env)) {
                    sender.sendMessage(LEGACY.deserialize("&7" + line));
                }
            }
            return;
        }

        if (args.length < 1) {
            sendUsage(sender);
            return;
        }

        boolean confirm = args[args.length - 1].equalsIgnoreCase("confirm");
        String[] rest = confirm ? java.util.Arrays.copyOf(args, args.length - 1) : args;
        if (rest.length < 1) {
            sendUsage(sender);
            return;
        }

        String targetName = rest[0];
        UUID targetId = Bukkit.getPlayerUniqueId(targetName);
        if (targetId == null) {
            try {
                targetId = Bukkit.getOfflinePlayer(targetName).getUniqueId();
            } catch (RuntimeException ignored) {
                targetId = null;
            }
        }
        Island island = targetId == null ? null : SkylliaAPI.getIslandByPlayerId(targetId);
        if (island == null) {
            sender.sendMessage(LEGACY.deserialize("&c未找到玩家 &e" + targetName + " &c或该玩家没有岛屿。"));
            return;
        }

        // 本服务端项目自定义修改：解析「世界」或「chunk」模式。
        // - 世界名（overworld/nether/the_end）：默认整岛区域，可再跟方块坐标限定单一区块
        // - chunk：按方块坐标（自动换算为区块）恢复单个区块；缺省则取执行者所在区块
        List<World.Environment> environments = new ArrayList<>();
        IslandRestoreService.ChunkRect rect = null;
        int index = 1;
        int regionOffsetX = 0;
        int regionOffsetZ = 0;
        boolean offsetMode = index < rest.length && rest[index].equalsIgnoreCase("offset");
        boolean chunkMode = index < rest.length && rest[index].equalsIgnoreCase("chunk");

        if (offsetMode) {
            // 本服务端项目自定义修改：偏移模式——把备份中「旧岛屿」区域映射到当前「新岛屿」区域。
            //   offset <oldx> <oldz>   oldx/oldz 为备份中旧岛屿范围内的任意方块坐标
            // 旧岛屿区域 = 坐标 >> 9（512 方块为一个区域），偏移 = 新区域 - 旧区域。
            index++;
            if (rest.length - index < 2 || !isInteger(rest[index]) || !isInteger(rest[index + 1])) {
                sender.sendMessage(LEGACY.deserialize(
                        "&c偏移模式用法：offset <oldx> <oldz>（备份中旧岛屿范围内的任意方块坐标）"));
                return;
            }
            int oldBlockX = Integer.parseInt(rest[index]);
            int oldBlockZ = Integer.parseInt(rest[index + 1]);
            int oldRegionX = oldBlockX >> 9;
            int oldRegionZ = oldBlockZ >> 9;
            RegionCoordinate region = island.getRegionCoordinate();
            regionOffsetX = region.x() - oldRegionX;
            regionOffsetZ = region.z() - oldRegionZ;
            // 目标为当前新岛屿的整个区域
            rect = new IslandRestoreService.ChunkRect(
                    region.x() << 5, region.z() << 5,
                    (region.x() << 5) + 31, (region.z() << 5) + 31);
            environments.addAll(configuredEnvironments());
        } else if (chunkMode) {
            index++;
            // 本服务端项目自定义修改：解析方块坐标——支持单点（1 组）或对角线（2 组）
            //   chunk <x> <z>              单点
            //   chunk <x1> <z1> <x2> <z2>  对角线矩形（含边界）
            int remaining = rest.length - index;
            Integer blockX1 = null;
            Integer blockZ1 = null;
            Integer blockX2 = null;
            Integer blockZ2 = null;
            if (remaining == 0) {
                // 未提供坐标：取执行者所在位置（控制台无法确定）
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(LEGACY.deserialize("&c控制台执行 chunk 模式时必须提供坐标：chunk <x> <z> [x2 z2]"));
                    return;
                }
                Location loc = player.getLocation();
                blockX1 = loc.getBlockX();
                blockZ1 = loc.getBlockZ();
            } else if (remaining == 2) {
                if (!isInteger(rest[index]) || !isInteger(rest[index + 1])) {
                    sender.sendMessage(LEGACY.deserialize("&cchunk 模式的坐标需为整数方块坐标：chunk <x> <z> [x2 z2]"));
                    return;
                }
                blockX1 = Integer.parseInt(rest[index]);
                blockZ1 = Integer.parseInt(rest[index + 1]);
            } else if (remaining == 4) {
                if (!isInteger(rest[index]) || !isInteger(rest[index + 1])
                        || !isInteger(rest[index + 2]) || !isInteger(rest[index + 3])) {
                    sender.sendMessage(LEGACY.deserialize("&cchunk 模式的坐标需为整数方块坐标：chunk <x> <z> [x2 z2]"));
                    return;
                }
                blockX1 = Integer.parseInt(rest[index]);
                blockZ1 = Integer.parseInt(rest[index + 1]);
                blockX2 = Integer.parseInt(rest[index + 2]);
                blockZ2 = Integer.parseInt(rest[index + 3]);
            } else {
                sender.sendMessage(LEGACY.deserialize(
                        "&cchunk 模式坐标数量不正确：需 0 组（取当前位置）、1 组 <x> <z>、或 2 组对角线 <x1> <z1> <x2> <z2>"));
                return;
            }

            int chunkX1 = blockX1 >> 4;
            int chunkZ1 = blockZ1 >> 4;
            int chunkX2 = (blockX2 != null ? blockX2 : blockX1) >> 4;
            int chunkZ2 = (blockZ2 != null ? blockZ2 : blockZ1) >> 4;
            // 对角线可任意方向：取最小/最大得到矩形范围（含边界）
            int minChunkX = Math.min(chunkX1, chunkX2);
            int maxChunkX = Math.max(chunkX1, chunkX2);
            int minChunkZ = Math.min(chunkZ1, chunkZ2);
            int maxChunkZ = Math.max(chunkZ1, chunkZ2);

            RegionCoordinate region = island.getRegionCoordinate();
            int baseChunkX = region.x() << 5;
            int baseChunkZ = region.z() << 5;
            if (minChunkX < baseChunkX || maxChunkX >= baseChunkX + 32
                    || minChunkZ < baseChunkZ || maxChunkZ >= baseChunkZ + 32) {
                sender.sendMessage(LEGACY.deserialize(
                        "&c范围（区块 " + minChunkX + "," + minChunkZ + " ~ " + maxChunkX + "," + maxChunkZ
                                + "）超出该岛屿（岛屿区块范围：" + baseChunkX + "~" + (baseChunkX + 31)
                                + " / " + baseChunkZ + "~" + (baseChunkZ + 31) + "）。"));
                return;
            }
            rect = new IslandRestoreService.ChunkRect(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
            environments.addAll(configuredEnvironments());
        } else {
            // 解析可选维度
            if (index < rest.length && !isInteger(rest[index])) {
                World.Environment env = parseEnvironment(rest[index]);
                if (env == null) {
                    sender.sendMessage(LEGACY.deserialize(
                            "&c未知参数 &e" + rest[index] + "&c，可用：overworld / nether / the_end / chunk"));
                    return;
                }
                environments.add(env);
                index++;
            }
            if (environments.isEmpty()) {
                environments.addAll(configuredEnvironments());
            }

            // 解析可选方块坐标（限定单一区块）
            if (index < rest.length) {
                if (index + 1 >= rest.length || !isInteger(rest[index]) || !isInteger(rest[index + 1])) {
                    sender.sendMessage(LEGACY.deserialize("&c坐标需为两个整数方块坐标。"));
                    return;
                }
                int blockX = Integer.parseInt(rest[index]);
                int blockZ = Integer.parseInt(rest[index + 1]);
                int chunkX = blockX >> 4;
                int chunkZ = blockZ >> 4;
                RegionCoordinate region = island.getRegionCoordinate();
                int baseChunkX = region.x() << 5;
                int baseChunkZ = region.z() << 5;
                if (chunkX < baseChunkX || chunkX >= baseChunkX + 32
                        || chunkZ < baseChunkZ || chunkZ >= baseChunkZ + 32) {
                    sender.sendMessage(LEGACY.deserialize(
                            "&c坐标（方块 " + blockX + "," + blockZ + " → 区块 " + chunkX + "," + chunkZ
                                    + "）不属于该岛屿（岛屿区块范围：" + baseChunkX + "~" + (baseChunkX + 31)
                                    + " / " + baseChunkZ + "~" + (baseChunkZ + 31) + "）。"));
                    return;
                }
                rect = new IslandRestoreService.ChunkRect(chunkX, chunkZ, chunkX, chunkZ);
            }
        }

        if (!confirm) {
            sender.sendMessage(LEGACY.deserialize("&6⚠ 存档恢复将【整体覆写】目标范围，"
                    + "丢弃该范围自备份时间点之后的一切改动，且不可撤销（会先快照当前文件）。"));
            sender.sendMessage(LEGACY.deserialize("&7目标：&e" + targetName
                    + " &7维度：&e" + describeEnvironments(environments)
                    + " &7范围：&e" + describeRect(rect)));
            if (offsetMode) {
                sender.sendMessage(LEGACY.deserialize("&7偏移：&e dx=" + regionOffsetX + " dz=" + regionOffsetZ
                        + " &7（备份区域 = 目标区域 - 偏移；区块偏移 "
                        + (regionOffsetX << 5) + "," + (regionOffsetZ << 5) + "）"));
            }
            sender.sendMessage(LEGACY.deserialize("&7确认请在命令末尾加上 &econfirm&7。"));
            return;
        }

        if (service.isBusy()) {
            sender.sendMessage(LEGACY.deserialize("&c已有恢复任务正在运行，请稍后再试（可用 &e/skylliadmin restore status&c 查看）。"));
            return;
        }

        // 本服务端项目自定义修改：每一步恢复情况实时推送给执行者与控制台
        final String scopeText = describeRect(rect);
        final String offsetText = offsetMode
                ? "，偏移 dx=" + regionOffsetX + " dz=" + regionOffsetZ : "";
        String envText = describeEnvironments(environments);
        java.util.function.Consumer<String> listener = step -> {
            Component line = LEGACY.deserialize("&7[恢复] " + step);
            try {
                sender.sendMessage(line);
            } catch (RuntimeException ignored) {
                // 执行者可能已离线
            }
            Bukkit.getConsoleSender().sendMessage(line);
        };

        IslandRestoreService.RestoreTask task = service.start(
                skyllia, island, environments, rect, listener, regionOffsetX, regionOffsetZ);
        if (task == null) {
            sender.sendMessage(LEGACY.deserialize("&c恢复任务启动失败（可能已有任务在运行）。"));
            return;
        }
        listener.accept("已启动存档恢复：目标 " + targetName + "，维度 " + envText + "，范围 " + scopeText
                + offsetText + "（可用 /skylliadmin restore status " + targetName + " 查询）");
    }

    private void sendStatus(@NotNull CommandSender sender, @NotNull IslandRestoreService service,
                            @Nullable String target) {
        List<IslandRestoreService.RestoreTask> tasks;
        if (target == null || target.equalsIgnoreCase("all")) {
            tasks = service.allTasks();
        } else {
            UUID id = Bukkit.getPlayerUniqueId(target);
            if (id == null) {
                try {
                    id = Bukkit.getOfflinePlayer(target).getUniqueId();
                } catch (RuntimeException ignored) {
                    id = null;
                }
            }
            Island island = id == null ? null : SkylliaAPI.getIslandByPlayerId(id);
            tasks = island == null ? List.of() : java.util.Optional
                    .ofNullable(service.taskOf(island.getId()))
                    .map(List::of).orElse(List.of());
        }
        if (tasks.isEmpty()) {
            sender.sendMessage(LEGACY.deserialize("&7当前没有存档恢复任务记录。"));
            return;
        }
        for (IslandRestoreService.RestoreTask task : tasks) {
            String state = task.done()
                    ? (task.success() ? "&a已完成" : "&c失败")
                    : "&e进行中";
            sender.sendMessage(LEGACY.deserialize("&7- &e" + task.ownerName() + " &7" + task.phase()
                    + " &7[" + state + "&7] &f" + task.completedWrites() + "/" + task.totalWrites()
                    + " (" + task.percent() + "%)"));
            if (task.error() != null) {
                sender.sendMessage(LEGACY.deserialize("  &c错误：" + task.error()));
            }
            sender.sendMessage(LEGACY.deserialize("  &8开始 " + TIME_FORMAT.format(Instant.ofEpochMilli(task.startedAt()))
                    + (task.done() ? "，结束 " + TIME_FORMAT.format(Instant.ofEpochMilli(task.finishedAt())) : "")
                    + "，维度数 " + task.dimensions()));
        }
    }

    // 本服务端项目自定义修改：范围的可读描述
    private static @NotNull String describeRect(@Nullable IslandRestoreService.ChunkRect rect) {
        if (rect == null) {
            return "整岛区域 (32×32)";
        }
        int count = rect.chunkCount();
        if (rect.minChunkX() == rect.maxChunkX() && rect.minChunkZ() == rect.maxChunkZ()) {
            return "区块 (" + rect.minChunkX() + "," + rect.minChunkZ() + ")";
        }
        return "区块范围 (" + rect.minChunkX() + "," + rect.minChunkZ() + " ~ "
                + rect.maxChunkX() + "," + rect.maxChunkZ() + "，共 " + count + " 个区块)";
    }

    private static @Nullable Island findIsland(@NotNull String name) {
        UUID id = Bukkit.getPlayerUniqueId(name);
        if (id == null) {
            try {
                id = Bukkit.getOfflinePlayer(name).getUniqueId();
            } catch (RuntimeException ignored) {
                id = null;
            }
        }
        return id == null ? null : SkylliaAPI.getIslandByPlayerId(id);
    }

    private static boolean isInteger(@NotNull String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static @Nullable World.Environment parseEnvironment(@NotNull String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "overworld", "normal", "world", "主世界" -> World.Environment.NORMAL;
            case "nether", "下界" -> World.Environment.NETHER;
            case "the_end", "end", "末地" -> World.Environment.THE_END;
            default -> null;
        };
    }

    private static @NotNull Set<World.Environment> configuredEnvironments() {
        Set<World.Environment> environments = new LinkedHashSet<>();
        for (WorldConfig config : WorldUtils.getWorldConfigs()) {
            if (config.getWorld() != null) {
                environments.add(config.getEnvironment());
            }
        }
        if (environments.isEmpty()) {
            environments.add(World.Environment.NORMAL);
        }
        return environments;
    }

    private static @NotNull String describeEnvironments(@NotNull List<World.Environment> environments) {
        StringBuilder builder = new StringBuilder();
        for (World.Environment environment : environments) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(switch (environment) {
                case NORMAL -> "主世界";
                case NETHER -> "下界";
                case THE_END -> "末地";
                default -> environment.name();
            });
        }
        return builder.toString();
    }

    private void sendUsage(@NotNull CommandSender sender) {
        sender.sendMessage(LEGACY.deserialize("&7用法："));
        sender.sendMessage(LEGACY.deserialize("&e /skylliadmin restore <玩家> [维度] [方块X 方块Z] confirm"));
        sender.sendMessage(LEGACY.deserialize("&7  维度可选：overworld / nether / the_end（默认全部）"));
        sender.sendMessage(LEGACY.deserialize("&7  方块坐标可选：指定则只恢复该方块所在区块，否则恢复整岛区域"));
        sender.sendMessage(LEGACY.deserialize("&e /skylliadmin restore <玩家> chunk [方块X 方块Z] [方块X2 方块Z2] confirm"));
        sender.sendMessage(LEGACY.deserialize("&7  区块模式：按方块坐标（自动换算区块）恢复区块；"));
        sender.sendMessage(LEGACY.deserialize("&7  给 1 组坐标恢复该区块，给 2 组坐标按对角线矩形范围恢复（含边界）；"));
        sender.sendMessage(LEGACY.deserialize("&7  未填坐标则取执行者所在位置（控制台必须填坐标）"));
        sender.sendMessage(LEGACY.deserialize("&e /skylliadmin restore <玩家> offset <旧方块X> <旧方块Z> confirm"));
        sender.sendMessage(LEGACY.deserialize("&7  偏移模式：把备份中旧岛屿区域映射到当前新岛屿区域"));
        sender.sendMessage(LEGACY.deserialize("&7  旧坐标取备份中旧岛屿范围内的任意方块坐标（自动换算区域）"));
        sender.sendMessage(LEGACY.deserialize("&e /skylliadmin restore status [玩家|all]"));
        sender.sendMessage(LEGACY.deserialize("&e /skylliadmin restore check <玩家> [维度] &7（只读诊断，不修改数据）"));
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender,
                                                @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("status");
            suggestions.add("check");
            String partial = args[0].toLowerCase(Locale.ROOT);
            Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                    .sorted().forEach(suggestions::add);
            return suggestions;
        }
        // 本服务端项目自定义修改：坐标位以执行者当前位置补全
        String playerBlockX = sender instanceof Player player
                ? Integer.toString(player.getLocation().getBlockX()) : null;
        String playerBlockZ = sender instanceof Player player
                ? Integer.toString(player.getLocation().getBlockZ()) : null;

        if (args.length == 2) {
            List<String> suggestions = new ArrayList<>(List.of("overworld", "nether", "the_end", "chunk", "offset"));
            if (playerBlockX != null) {
                suggestions.add(playerBlockX);
            }
            suggestions.add("confirm");
            return suggestions;
        }

        boolean offsetMode = args[1].equalsIgnoreCase("offset");
        boolean chunkMode = args[1].equalsIgnoreCase("chunk");
        boolean dimensionGiven = !chunkMode && !offsetMode && !isInteger(args[1]);
        if (offsetMode) {
            // 偏移模式的坐标由管理员手动填写（备份中旧岛屿坐标），仅提示 confirm
            return List.of("confirm");
        }
        // 正在补全的参数索引
        int index = args.length - 1;
        List<String> suggestions = new ArrayList<>();
        if (chunkMode) {
            // 本服务端项目自定义修改：chunk 支持单点或对角线，X/Z 在索引 2~5
            if ((index == 2 || index == 4) && playerBlockX != null) {
                suggestions.add(playerBlockX);
            } else if ((index == 3 || index == 5) && playerBlockZ != null) {
                suggestions.add(playerBlockZ);
            }
        } else if (dimensionGiven) {
            // 维度后：X 在索引 2、Z 在索引 3
            if (index == 2 && playerBlockX != null) {
                suggestions.add(playerBlockX);
            } else if (index == 3 && playerBlockZ != null) {
                suggestions.add(playerBlockZ);
            }
        } else {
            // 省略维度：X 在索引 1、Z 在索引 2
            if (index == 1 && playerBlockX != null) {
                suggestions.add(playerBlockX);
            } else if (index == 2 && playerBlockZ != null) {
                suggestions.add(playerBlockZ);
            }
        }
        suggestions.add("confirm");
        return suggestions;
    }
}
