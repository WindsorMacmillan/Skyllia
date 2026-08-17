package fr.euphyllia.skylliachallenge.gui;

import dev.triumphteam.gui.builder.item.PaperItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.challenge.Challenge;
import fr.euphyllia.skylliachallenge.managers.ChallengeManagers;
import fr.euphyllia.skylliachallenge.requirement.BankRequirement;
import fr.euphyllia.skylliachallenge.requirement.BlockBreakRequirement;
import fr.euphyllia.skylliachallenge.requirement.CraftRequirement;
import fr.euphyllia.skylliachallenge.requirement.EcoRequirement;
import fr.euphyllia.skylliachallenge.requirement.EnchantRequirement;
import fr.euphyllia.skylliachallenge.requirement.FishRequirement;
import fr.euphyllia.skylliachallenge.requirement.ItemRequirement;
import fr.euphyllia.skylliachallenge.requirement.KillEntityRequirement;
import fr.euphyllia.skylliachallenge.requirement.PlayerConsumeRequirement;
import fr.euphyllia.skylliachallenge.requirement.PotionRequirement;
import fr.euphyllia.skylliachallenge.storage.ProgressStorage;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ChallengeGui {

    // 每页最多显示的挑战数（前5行 × 9列）
    private static final int PAGE_SIZE = 45;

    // 5个级别的定义
    private static final int LEVEL_COUNT = 5;
    private static final String[] LEVEL_NAMES = {"入门", "前期", "中期", "后期", "终极"};
    private enum RequirementState {
        UNKNOWN,
        MET,
        NOT_MET
    }
    private final Map<Integer, Challenge> pendingUpdates = new ConcurrentHashMap<>();
    private static final Material[] LEVEL_MATERIALS = {
            Material.IRON_BLOCK,
            Material.GOLD_BLOCK,
            Material.EMERALD_BLOCK,
            Material.DIAMOND_BLOCK,
            Material.NETHERITE_BLOCK
    };
    // 底部导航栏各槽位列（行固定为第6行）
    // 槽位：1=上一页, 2=空白, 3=入门, 4=前期, 5=中期, 6=后期, 7=终极, 8=空白, 9=下一页
    private static final int NAV_ROW = 6;
    private static final int COL_PREV = 1;
    private static final int COL_NEXT = 9;
    private static final int[] COL_LEVELS = {3, 4, 5, 6, 7}; // 对应 level 1~5

    private static final DecimalFormat NF = new DecimalFormat("#,###");
    private static final Logger log = LoggerFactory.getLogger(ChallengeGui.class);

    private final SkylliaChallenge plugin;
    private final ChallengeManagers manager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ChallengeGui(SkylliaChallenge plugin, ChallengeManagers manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * 打开GUI入口，默认打开第1级别第1子页
     */
    public void open(Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(player, "addons.challenge.player.no-island");
            return;
        }

        // 找到当前已解锁的最高级别（如果全部未解锁则至少为1）
        int highestUnlocked = 1;
        for (int lvl = 1; lvl <= LEVEL_COUNT; lvl++) {
            if (isLevelUnlocked(lvl, island)) {
                highestUnlocked = lvl;
            } else {
                break; // 一旦遇到未解锁，后面都不解锁
            }
        }

        open(player, highestUnlocked, 1);
    }

    /**
     * 打开指定级别（level 1~5）和该级别内的子页（subPage 从1开始）
     *
     * @param player  玩家
     * @param level   挑战级别 1~5
     * @param subPage 该级别内的子页码，从1开始
     */
    public void open(Player player, int level, int subPage) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(player, "addons.challenge.player.no-island");
            return;
        }

        // 如果目标级别未解锁，自动切换到最高已解锁级别
        if (!isLevelUnlocked(level, island)) {
            for (int l = LEVEL_COUNT; l >= 1; l--) {
                if (isLevelUnlocked(l, island)) {
                    level = l;
                    subPage = 1;
                    break;
                }
            }
            ConfigLoader.language.sendMessage(player, "addons.challenge.level.locked"); // 可选提示
        }

        // 获取当前级别的所有挑战，按 positionGUI 的 page/row/column 或按加载顺序排列
        int finalLevel = level;
        List<Challenge> levelChallenges = manager.getChallenges().stream()
                .filter(c -> c.isShowInGUI() && c.getLevel() == finalLevel)
                .toList();

        int totalChallenges = levelChallenges.size();
        int totalSubPages = Math.max(1, (int) Math.ceil((double) totalChallenges / PAGE_SIZE));
        // 边界修正
        int safeSubPage = Math.max(1, Math.min(subPage, totalSubPages));
        boolean multiPage = totalChallenges > PAGE_SIZE;

        // 当前子页的挑战切片
        int fromIndex = (safeSubPage - 1) * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, totalChallenges);
        List<Challenge> pageChallenges = levelChallenges.subList(fromIndex, toIndex);

        Gui gui = Gui.gui()
                .title(buildTitle(player, level, safeSubPage, totalSubPages))
                .rows(6)
                .disableAllInteractions()
                .create();

        // ── 填充挑战图标 ──
        fillChallengeItems(gui, player, pageChallenges);

        // ── 将前5行中未被占用的槽位填上灰色玻璃板 ──
        for (int row = 1; row <= 5; row++) {
            for (int col = 1; col <= 9; col++) {
                int slot = (row - 1) * 9 + (col - 1);
                if (gui.getGuiItem(slot) == null) {
                    gui.setItem(slot, emptyPane());
                }
            }
        }

        // ── 底部导航栏 ──
        buildNavBar(gui, player, island, level, safeSubPage, totalSubPages, multiPage);

        int finalLevel1 = level;
        int finalSubPage = subPage;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            for (Map.Entry<Integer, Challenge> entry : pendingUpdates.entrySet()) {
                int slot = entry.getKey();
                Challenge c = entry.getValue();
                int times = ProgressStorage.getTimesCompleted(island.getId(), c.getId());
                boolean fully = c.getMaxTimes() >= 0 && times >= c.getMaxTimes();
                if (fully) {
                    // 已完成，跳过更新（或简单设置为发光但保持未知需求）
                    player.getScheduler().run(plugin, unused1 -> {
                        // 直接构建发光占位图标，需求仍为灰色
                        GuiItem updated = buildFullGuiItem(player, island, c, finalLevel1, finalSubPage, times, true, false);
                        gui.updateItem(slot, updated);
                    }, null);
                } else {
                    boolean can = manager.canComplete(island, c, player);
                    player.getScheduler().run(plugin, unused2 -> {
                        GuiItem updated = buildFullGuiItem(player, island, c, finalLevel1, finalSubPage, times, false, can);
                        gui.updateItem(slot, updated);
                    }, null);
                }
            }
            pendingUpdates.clear();
        });

        player.getScheduler().run(plugin, unused3 -> gui.open(player), null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 私有方法
    // ─────────────────────────────────────────────────────────────────────────

    private Component buildTitle(Player player, int level, int subPage, int totalSubPages) {
        String levelName = LEVEL_NAMES[level - 1];
        if (totalSubPages > 1) {
            return ConfigLoader.language.translate(player.locale(),
                    "addons.challenge.display.title",
                    Map.of(
                            "%level%", levelName,
                            "%page%", String.valueOf(subPage),
                            "%total%", String.valueOf(totalSubPages)
                    ), false);
        }
        return ConfigLoader.language.translate(player.locale(),
                "addons.challenge.display.title",
                Map.of("%level%", levelName, "%page%", "1", "%total%", "1"),
                false);
    }

    /**
     * 将挑战填入 GUI 前5行。
     * 优先使用挑战自身的 positionGUI（row 1~5, column 1~9）；
     * 若位置冲突或未配置则按顺序填入空槽。
     */
    private void fillChallengeItems(Gui gui, Player player,
                                    List<Challenge> challenges) {
        boolean[][] occupied = new boolean[6][10];
        List<Challenge> fallback = new ArrayList<>();

        for (Challenge c : challenges) {
            Challenge.PositionGUI pos = c.getPositionGUI();
            if (pos != null && pos.row() >= 1 && pos.row() <= 5 && pos.column() >= 1 && pos.column() <= 9
                    && !occupied[pos.row()][pos.column()]) {
                occupied[pos.row()][pos.column()] = true;
                int slot = (pos.row() - 1) * 9 + (pos.column() - 1);
                gui.setItem(slot, buildPlaceholderGuiItem(player, c));
                pendingUpdates.put(slot, c);
            } else {
                fallback.add(c);
            }
        }

        outer:
        for (Challenge c : fallback) {
            for (int row = 1; row <= 5; row++) {
                for (int col = 1; col <= 9; col++) {
                    if (!occupied[row][col]) {
                        occupied[row][col] = true;
                        int slot = (row - 1) * 9 + (col - 1);
                        gui.setItem(slot, buildPlaceholderGuiItem(player, c));
                        pendingUpdates.put(slot, c);
                        continue outer;
                    }
                }
            }
        }
    }

    private dev.triumphteam.gui.guis.GuiItem buildFullGuiItem(Player player, Island island,
                                                              Challenge c, int level, int subPage,
                                                              int times, boolean fullyCompleted, boolean can) {
        ItemStack base = c.getGuiItem().clone();
        List<Component> lore = new ArrayList<>(c.getLore());
        lore.add(miniMessage.deserialize("<gray>--------------------</gray>"));
        if(fullyCompleted) {
            lore.add(Component.text("挑战已完成", NamedTextColor.BLUE)
                    .decoration(TextDecoration.ITALIC, false));
        }
        else {
            lore.add(ConfigLoader.language.translate(player.locale(), "addons.challenge.display.progression",
                    Map.of(
                            "%progression%", String.valueOf(times),
                            "%max_times%", c.getMaxTimes() >= 0 ? String.valueOf(c.getMaxTimes()) : "∞"
                    ), false));
        }

        if (c.getRequirements() != null && !c.getRequirements().isEmpty()) {
            lore.add(ConfigLoader.language.translate(player.locale(), "addons.challenge.display.requirements", Map.of(), false));
            for (ChallengeRequirement req : c.getRequirements()) {
                if (fullyCompleted) {
                    // 已完成挑战，需求全部灰色未知
                    lore.add(requirementLine(player.locale(), req.getDisplay(player.locale()), RequirementState.UNKNOWN));
                } else switch (req) {
                    case ItemRequirement ir -> {
                        long collected = ir.getDisplayProgress(player, island);
                        boolean met = collected >= ir.count();
                        lore.add(requirementLine(player.locale(), ir.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, ir.count()));
                    }
                    case CraftRequirement cr -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), cr.requirementId());
                        boolean met = collected >= cr.count();
                        lore.add(requirementLine(player.locale(), cr.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, cr.count()));
                    }
                    case BankRequirement br -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), br.requirementId());
                        boolean met = collected >= br.amount();
                        lore.add(requirementLine(player.locale(), br.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, (int)br.amount()));
                    }
                    case BlockBreakRequirement bbr -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), bbr.requirementId());
                        boolean met = collected >= bbr.count();
                        lore.add(requirementLine(player.locale(), bbr.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, bbr.count()));
                    }
                    case EcoRequirement er -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), er.requirementId());
                        boolean met = collected >= er.count();
                        lore.add(requirementLine(player.locale(), er.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, (int)er.count()));
                    }
                    case EnchantRequirement ee -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), ee.requirementId());
                        boolean met = collected >= ee.count();
                        lore.add(requirementLine(player.locale(), ee.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, ee.count()));
                    }
                    case FishRequirement fr -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), fr.requirementId());
                        boolean met = collected >= fr.count();
                        lore.add(requirementLine(player.locale(), fr.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, fr.count()));
                    }
                    case KillEntityRequirement ker -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), ker.requirementId());
                        boolean met = collected >= ker.count();
                        lore.add(requirementLine(player.locale(), ker.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, ker.count()));
                    }
                    case PlayerConsumeRequirement pcr -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), pcr.requirementId());
                        boolean met = collected >= pcr.count();
                        lore.add(requirementLine(player.locale(), pcr.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, pcr.count()));
                    }
                    case PotionRequirement pr -> {
                        long collected = ProgressStoragePartial.getPartial(island.getId(), c.getId(), pr.requirementId());
                        boolean met = collected >= pr.count();
                        lore.add(requirementLine(player.locale(), pr.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET, collected, pr.count()));
                    }
                    default -> {
                        boolean met = req.isMet(player, island);
                        lore.add(requirementLine(player.locale(), req.getDisplay(player.locale()),
                                met ? RequirementState.MET : RequirementState.NOT_MET));
                    }
                }
            }
        }

        if (!fullyCompleted) {
            lore.add(can
                    ? ConfigLoader.language.translate(player.locale(), "addons.challenge.display.can-validate", Map.of(), false)
                    : ConfigLoader.language.translate(player.locale(), "addons.challenge.display.cannot-validate", Map.of(), false));
            long remaining = manager.getRemainingCooldownMillis(island, c);
            if (remaining > 0) {
                lore.add(Component.text("").append(
                        ConfigLoader.language.translate(player.locale(), "addons.challenge.display.cooldown",
                                Map.of("%time_left%", ChallengeManagers.formatDurationShort(remaining)), false)));
            }
        }

        // 统一去除斜体
        List<Component> finalLore = lore.stream()
                .map(comp -> comp.decoration(TextDecoration.ITALIC, false)).collect(Collectors.toList());

        PaperItemBuilder builder = PaperItemBuilder.from(base).lore(finalLore)
                .name(miniMessage.deserialize(c.getName()).decoration(TextDecoration.ITALIC, false));
        if (fullyCompleted) builder.glow(true);

        return builder.asGuiItem(unused4 -> {
            if (!fullyCompleted && manager.complete(island, c, player)) {
                ConfigLoader.language.sendMessage(player, "addons.challenge.player.complete",
                        Map.of("%challenge_name%", c.getName()));
            }
            Bukkit.getAsyncScheduler().runNow(plugin, unused5 -> open(player, level, subPage));
        });
    }

    private dev.triumphteam.gui.guis.GuiItem buildPlaceholderGuiItem(Player player,
                                                                     Challenge c) {
        ItemStack base = c.getGuiItem().clone();
        List<Component> lore = new ArrayList<>(c.getLore());
        lore.add(miniMessage.deserialize("<gray>--------------------</gray>"));
        lore.add(ConfigLoader.language.translate(player.locale(), "addons.challenge.display.progression",
                Map.of("%progression%", "0",
                        "%max_times%", c.getMaxTimes() >= 0 ? String.valueOf(c.getMaxTimes()) : "∞"), false));

        if (c.getRequirements() != null && !c.getRequirements().isEmpty()) {
            lore.add(ConfigLoader.language.translate(player.locale(), "addons.challenge.display.requirements", Map.of(), false));
            for (ChallengeRequirement req : c.getRequirements()) {
                lore.add(requirementLine(player.locale(), req.getDisplay(player.locale()), RequirementState.UNKNOWN));
            }
        }

        lore.add(ConfigLoader.language.translate(player.locale(), "addons.challenge.display.cannot-validate", Map.of(), false));
        if (c.getGuiLore() != null) lore.addAll(c.getGuiLore());

        List<Component> finalLore = lore.stream()
                .map(comp -> comp.decoration(TextDecoration.ITALIC, false))
                .collect(Collectors.toList());
        return PaperItemBuilder.from(base).lore(finalLore)
                .name(miniMessage.deserialize(c.getName()).decoration(TextDecoration.ITALIC, false))
                .asGuiItem(unused6 -> { /* 初始无操作 */ });
    }

    /**
     * 构建底部第6行导航栏。
     * 布局：[上一页][空白][入门][前期][中期][后期][终极][空白][下一页]
     * 上一页/下一页：仅当该级别挑战超过45个时显示，否则为灰色玻璃板占位。
     */
    private void buildNavBar(Gui gui, Player player, Island island,
                             int currentLevel, int currentSubPage, int totalSubPages, boolean multiPage) {

        // ── 上一页 ──
        if (multiPage) {
            gui.setItem(NAV_ROW, COL_PREV,
                    PaperItemBuilder.from(new ItemStack(Material.ARROW))
                            .name(ConfigLoader.language.translate(player.locale(),
                                            "addons.challenge.display.previous", Map.of(), false)
                                    .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                            .asGuiItem(unused7 -> {
                                int prev = currentSubPage > 1 ? currentSubPage - 1 : totalSubPages;
                                Bukkit.getAsyncScheduler().runNow(plugin,
                                        unused8 -> open(player, currentLevel, prev));
                            }));
        } else {
            gui.setItem(NAV_ROW, COL_PREV, emptyPane());
        }

        // ── 下一页 ──
        if (multiPage) {
            gui.setItem(NAV_ROW, COL_NEXT,
                    PaperItemBuilder.from(new ItemStack(Material.ARROW))
                            .name(ConfigLoader.language.translate(player.locale(),
                                    "addons.challenge.display.next", Map.of(), false))
                            .asGuiItem(unused9 -> {
                                int next = currentSubPage < totalSubPages ? currentSubPage + 1 : 1;
                                Bukkit.getAsyncScheduler().runNow(plugin,
                                        unused10 -> open(player, currentLevel, next));
                            }));
        } else {
            gui.setItem(NAV_ROW, COL_NEXT, emptyPane());
        }

        // ── 空白槽（列2和列8）──
        gui.setItem(NAV_ROW, 2, emptyPane());
        gui.setItem(NAV_ROW, 8, emptyPane());

        int highestUnlockedLevel = 1;
        for (int lvl = 1; lvl <= LEVEL_COUNT; lvl++) {
            if (isLevelUnlocked(lvl, island)) {
                highestUnlockedLevel = lvl;
            } else {
                break;
            }
        }

        // ── 5个级别按钮（列3~7）──
        for (int i = 0; i < LEVEL_COUNT; i++) {
            final int lvl = i + 1;
            boolean unlocked = isLevelUnlocked(lvl, island);
            boolean isCurrent = (lvl == currentLevel);

            Material mat = unlocked ? LEVEL_MATERIALS[i] : Material.TINTED_GLASS;

            // 统计挑战
            List<Challenge> levelChallenges = manager.getChallenges().stream()
                    .filter(c -> c.isShowInGUI() && c.getLevel() == lvl)
                    .toList();
            long totalCount = levelChallenges.size();
            long completedCount = levelChallenges.stream()
                    .filter(c -> ProgressStorage.getTimesCompleted(island.getId(), c.getId()) >= 1)
                    .count();

            // 获取原始名称（支持 MiniMessage 标签）
            String rawName = manager.getLevelName(lvl);
            Component parsedName = miniMessage.deserialize(rawName);

            // 当前级别加上黄色箭头并保留原有颜色，非当前级别若无颜色则设为灰色
            if (isCurrent) {
                parsedName = Component.text("当前级别：").color(NamedTextColor.YELLOW).append(parsedName);
            } else {
                parsedName = parsedName.colorIfAbsent(NamedTextColor.GRAY);
            }
            Component name = parsedName.decoration(TextDecoration.ITALIC, false);

            List<Component> loreList = new ArrayList<>();
            loreList.add(Component.text("挑战数量：" + totalCount, NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            loreList.add(Component.text("已完成：" + completedCount, NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));

            // 描述（levels.yml）
            List<String> descLines = manager.getLevelDescription(lvl);
            if (!descLines.isEmpty()) {
                for (String line : descLines) {
                    if (!line.isEmpty()) {
                        loreList.add(miniMessage.deserialize(line)
                                .decoration(TextDecoration.ITALIC, false));
                    }
                }
            }

            // ── 解锁进度提示（仅最高已解锁级别且下一级未解锁时）──
            if (lvl == highestUnlockedLevel && lvl < LEVEL_COUNT) {
                int threshold = switch (lvl) {
                    case 1 -> 4;
                    case 2 -> 3;
                    case 3 -> 2;
                    case 4 -> 1;
                    default -> 0;
                };
                int uncompleted = manager.getUncompletedCount(lvl, island);
                int need = uncompleted - threshold;
                if (need > 0) {
                    loreList.add(Component.text("距下一级解锁还需完成 " + need + " 个挑战", NamedTextColor.GOLD)
                            .decoration(TextDecoration.ITALIC, false));
                }
            } else if (!unlocked) {
                // 未解锁级别显示红色条件
                int threshold = switch (lvl) {
                    case 2 -> 4;
                    case 3 -> 3;
                    case 4 -> 2;
                    case 5 -> 1;
                    default -> 0;
                };
                String condition = "你可以跳过 " +threshold+" 个"+ (lvl - 1) + "级挑战以解锁此级别";
                loreList.add(Component.text(condition, NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
            }
            // 已解锁但不是最高级别 → 不显示任何进度提示

            PaperItemBuilder builder = PaperItemBuilder.from(new ItemStack(mat))
                    .name(name)
                    .lore(loreList);

            if (isCurrent && unlocked) {
                builder.glow(true);
            }

            gui.setItem(NAV_ROW, COL_LEVELS[i],
                    builder.asGuiItem(unused11 -> {
                        if (lvl != currentLevel && unlocked) {
                            Bukkit.getAsyncScheduler().runNow(plugin,
                                    unused12 -> open(player, lvl, 1));
                        }
                    }));
        }
    }

    // 判断指定级别是否可访问（上一级未完成数 ≤ 阈值）
    private boolean isLevelUnlocked(int level, Island island) {
        if (level <= 1) return true;
        int threshold = switch (level - 1) {
            case 1 -> 4;
            case 2 -> 3;
            case 3 -> 2;
            case 4 -> 1;
            default -> Integer.MAX_VALUE;
        };
        return manager.getUncompletedCount(level - 1, island) <= threshold;
    }

    /**
     * 生成灰色玻璃板占位图标（无交互）
     */
    private GuiItem emptyPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        pane.getItemMeta().setHideTooltip(true);
        return PaperItemBuilder.from(pane)
                .name(Component.empty())
                .asGuiItem();
    }

    private void countRequirement(Locale locale, List<Component> lore, Number collected, Number count, Component display) {
        boolean met = collected.doubleValue() >= count.doubleValue();
        Component loreCount = ConfigLoader.language.translate(locale,
                "addons.challenge.display.requirement.count",
                Map.of(
                        "%display%", miniMessage.serialize(display),
                        "%collected%", NF.format(collected),
                        "%required%", NF.format(count),
                        "%met%", met ? "✓" : "✗"
                ), false);
        lore.add(loreCount);
    }

    // 有进度数字的需求行（如物品、方块破坏等）
    private Component requirementLine(Locale locale, Component display, RequirementState state,
                                      long collected, long required) {
        String displayStr = miniMessage.serialize(display);
        String color = "gray";
        String suffix = "";
        long showCollected = 0;
        long showRequired = 0;
        switch (state) {
            case UNKNOWN -> {
                color = "gray";
                suffix = "";
            }
            case MET -> {
                color = "green";
                suffix = " ✓";
                showCollected = collected;
                showRequired = required;
            }
            case NOT_MET -> {
                color = "red";
                suffix = " ✗";
                showCollected = collected;
                showRequired = required;
            }
        }
        String line = String.format("<%s><!italic>%s 进度 %s/%s%s</%s>",
                color, displayStr, NF.format(showCollected), NF.format(showRequired), suffix, color);
        return miniMessage.deserialize(line);
    }

    // 无进度数字的需求行（如附近实体、附近方块、药水等）
    private Component requirementLine(Locale locale, Component display, RequirementState state) {
        String displayStr = miniMessage.serialize(display);
        String color = "gray";
        String suffix = "";
        switch (state) {
            case UNKNOWN -> {
                color = "gray";
                suffix = "";
            }
            case MET -> {
                color = "green";
                suffix = " ✓";
            }
            case NOT_MET -> {
                color = "red";
                suffix = " ✗";
            }
        }
        String line = String.format("<%s><!italic>%s%s</%s>", color, displayStr, suffix, color);
        return miniMessage.deserialize(line);
    }
}