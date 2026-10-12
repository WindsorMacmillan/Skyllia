package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.analytics.IslandBlockTopStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the read-only island lag ranking inventory.
 */
public final class IslandTopGui {

    private static final int INVENTORY_SIZE = 54;
    private static final NamespacedKey GUI_MARKER = new NamespacedKey("skyllia", "island_top_gui");
    private static final int PLAYER_LINE_WIDTH = 25;
    // 排行内容整体上移一行：每排槽位统一减去 9，四排头像占据第 1~4 行
    private static final int[] RANKING_SLOTS = {
            4,
            12, 14,
            20, 22, 24,
            28, 30, 32, 34
    };
    // 本服务端项目自定义修改：数据异常岛屿上移至第 5 行第 2~8 格（槽位 37~43）
    private static final int[] ABNORMAL_SLOTS = {37, 38, 39, 40, 41, 42, 43};
    // 本服务端项目自定义修改：第 6 行第 2~8 格（槽位 46~52）用于展示方块类排名
    private static final int[] BLOCK_TOP_SLOTS = {46, 47, 48, 49, 50, 51, 52};
    private static final NamedTextColor[] ENTITY_LINE_COLORS = {
            NamedTextColor.RED,
            NamedTextColor.GOLD,
            NamedTextColor.YELLOW,
            NamedTextColor.WHITE
    };
    private static final String[] RANK_LABELS = {
            "全服第一卡：",
            "第二卡：",
            "第三卡：",
            "第四卡：",
            "第五卡：",
            "第六卡：",
            "第七卡：",
            "第八卡：",
            "第九卡：",
            "第十卡："
    };
    private static final NamedTextColor[] RANK_LABEL_COLORS = {
            NamedTextColor.RED,
            NamedTextColor.GOLD,
            NamedTextColor.YELLOW,
            NamedTextColor.WHITE
    };
    private static final int ENTITY_COLUMN_GAP = 1;
    private static final Map<String, Integer> ZH_CN_ENTITY_NAME_LENGTHS = Map.<String, Integer>ofEntries(
            Map.entry("entity.minecraft.acacia_boat", 5),
            Map.entry("entity.minecraft.acacia_chest_boat", 7),
            Map.entry("entity.minecraft.allay", 2),
            Map.entry("entity.minecraft.area_effect_cloud", 5),
            Map.entry("entity.minecraft.armadillo", 2),
            Map.entry("entity.minecraft.armor_stand", 3),
            Map.entry("entity.minecraft.arrow", 1),
            Map.entry("entity.minecraft.axolotl", 3),
            Map.entry("entity.minecraft.bamboo_chest_raft", 4),
            Map.entry("entity.minecraft.bamboo_raft", 2),
            Map.entry("entity.minecraft.bat", 2),
            Map.entry("entity.minecraft.bee", 2),
            Map.entry("entity.minecraft.birch_boat", 4),
            Map.entry("entity.minecraft.birch_chest_boat", 6),
            Map.entry("entity.minecraft.blaze", 3),
            Map.entry("entity.minecraft.block_display", 6),
            Map.entry("entity.minecraft.boat", 1),
            Map.entry("entity.minecraft.bogged", 2),
            Map.entry("entity.minecraft.breeze", 3),
            Map.entry("entity.minecraft.breeze_wind_charge", 2),
            Map.entry("entity.minecraft.camel", 2),
            Map.entry("entity.minecraft.camel_husk", 4),
            Map.entry("entity.minecraft.cat", 1),
            Map.entry("entity.minecraft.cave_spider", 4),
            Map.entry("entity.minecraft.cherry_boat", 4),
            Map.entry("entity.minecraft.cherry_chest_boat", 6),
            Map.entry("entity.minecraft.chest_boat", 3),
            Map.entry("entity.minecraft.chest_minecart", 4),
            Map.entry("entity.minecraft.chicken", 1),
            Map.entry("entity.minecraft.cod", 2),
            Map.entry("entity.minecraft.command_block_minecart", 6),
            Map.entry("entity.minecraft.copper_golem", 3),
            Map.entry("entity.minecraft.cow", 1),
            Map.entry("entity.minecraft.creaking", 2),
            Map.entry("entity.minecraft.creaking_transient", 2),
            Map.entry("entity.minecraft.creeper", 3),
            Map.entry("entity.minecraft.cushion", 2),
            Map.entry("entity.minecraft.dark_oak_boat", 5),
            Map.entry("entity.minecraft.dark_oak_chest_boat", 7),
            Map.entry("entity.minecraft.dolphin", 2),
            Map.entry("entity.minecraft.donkey", 1),
            Map.entry("entity.minecraft.dragon_fireball", 5),
            Map.entry("entity.minecraft.drowned", 2),
            Map.entry("entity.minecraft.egg", 5),
            Map.entry("entity.minecraft.elder_guardian", 5),
            Map.entry("entity.minecraft.end_crystal", 4),
            Map.entry("entity.minecraft.ender_dragon", 3),
            Map.entry("entity.minecraft.ender_pearl", 7),
            Map.entry("entity.minecraft.enderman", 3),
            Map.entry("entity.minecraft.endermite", 3),
            Map.entry("entity.minecraft.evoker", 3),
            Map.entry("entity.minecraft.evoker_fangs", 5),
            Map.entry("entity.minecraft.experience_bottle", 7),
            Map.entry("entity.minecraft.experience_orb", 3),
            Map.entry("entity.minecraft.eye_of_ender", 4),
            Map.entry("entity.minecraft.falling_block", 5),
            Map.entry("entity.minecraft.falling_block_type", 5),
            Map.entry("entity.minecraft.fireball", 2),
            Map.entry("entity.minecraft.firework_rocket", 4),
            Map.entry("entity.minecraft.fishing_bobber", 2),
            Map.entry("entity.minecraft.fox", 2),
            Map.entry("entity.minecraft.frog", 2),
            Map.entry("entity.minecraft.furnace_minecart", 4),
            Map.entry("entity.minecraft.ghast", 2),
            Map.entry("entity.minecraft.giant", 2),
            Map.entry("entity.minecraft.glow_item_frame", 7),
            Map.entry("entity.minecraft.glow_squid", 4),
            Map.entry("entity.minecraft.goat", 2),
            Map.entry("entity.minecraft.guardian", 3),
            Map.entry("entity.minecraft.happy_ghast", 4),
            Map.entry("entity.minecraft.hoglin", 3),
            Map.entry("entity.minecraft.hopper_minecart", 4),
            Map.entry("entity.minecraft.horse", 1),
            Map.entry("entity.minecraft.husk", 2),
            Map.entry("entity.minecraft.illusioner", 3),
            Map.entry("entity.minecraft.interaction", 4),
            Map.entry("entity.minecraft.iron_golem", 3),
            Map.entry("entity.minecraft.item", 2),
            Map.entry("entity.minecraft.item_display", 6),
            Map.entry("entity.minecraft.item_frame", 5),
            Map.entry("entity.minecraft.jungle_boat", 4),
            Map.entry("entity.minecraft.jungle_chest_boat", 6),
            Map.entry("entity.minecraft.killer_bunny", 3),
            Map.entry("entity.minecraft.leash_knot", 3),
            Map.entry("entity.minecraft.lightning_bolt", 3),
            Map.entry("entity.minecraft.lingering_potion", 4),
            Map.entry("entity.minecraft.llama", 2),
            Map.entry("entity.minecraft.llama_spit", 4),
            Map.entry("entity.minecraft.magma_cube", 3),
            Map.entry("entity.minecraft.mangrove_boat", 4),
            Map.entry("entity.minecraft.mangrove_chest_boat", 6),
            Map.entry("entity.minecraft.mannequin", 4),
            Map.entry("entity.minecraft.marker", 2),
            Map.entry("entity.minecraft.minecart", 2),
            Map.entry("entity.minecraft.mooshroom", 2),
            Map.entry("entity.minecraft.mule", 1),
            Map.entry("entity.minecraft.nautilus", 3),
            Map.entry("entity.minecraft.oak_boat", 3),
            Map.entry("entity.minecraft.oak_chest_boat", 5),
            Map.entry("entity.minecraft.ocelot", 2),
            Map.entry("entity.minecraft.ominous_item_spawner", 7),
            Map.entry("entity.minecraft.painting", 1),
            Map.entry("entity.minecraft.pale_oak_boat", 5),
            Map.entry("entity.minecraft.pale_oak_chest_boat", 7),
            Map.entry("entity.minecraft.panda", 2),
            Map.entry("entity.minecraft.parched", 2),
            Map.entry("entity.minecraft.parrot", 2),
            Map.entry("entity.minecraft.phantom", 2),
            Map.entry("entity.minecraft.pig", 1),
            Map.entry("entity.minecraft.piglin", 2),
            Map.entry("entity.minecraft.piglin_brute", 4),
            Map.entry("entity.minecraft.pillager", 3),
            Map.entry("entity.minecraft.player", 2),
            Map.entry("entity.minecraft.polar_bear", 3),
            Map.entry("entity.minecraft.poplar_boat", 3),
            Map.entry("entity.minecraft.poplar_chest_boat", 5),
            Map.entry("entity.minecraft.potion", 2),
            Map.entry("entity.minecraft.pufferfish", 2),
            Map.entry("entity.minecraft.rabbit", 2),
            Map.entry("entity.minecraft.ravager", 3),
            Map.entry("entity.minecraft.salmon", 2),
            Map.entry("entity.minecraft.sheep", 2),
            Map.entry("entity.minecraft.shulker", 3),
            Map.entry("entity.minecraft.shulker_bullet", 3),
            Map.entry("entity.minecraft.silverfish", 2),
            Map.entry("entity.minecraft.skeleton", 2),
            Map.entry("entity.minecraft.skeleton_horse", 3),
            Map.entry("entity.minecraft.slime", 3),
            Map.entry("entity.minecraft.small_fireball", 3),
            Map.entry("entity.minecraft.sniffer", 3),
            Map.entry("entity.minecraft.snow_golem", 3),
            Map.entry("entity.minecraft.snowball", 2),
            Map.entry("entity.minecraft.spawner_minecart", 5),
            Map.entry("entity.minecraft.spectral_arrow", 3),
            Map.entry("entity.minecraft.spider", 2),
            Map.entry("entity.minecraft.splash_potion", 4),
            Map.entry("entity.minecraft.spruce_boat", 4),
            Map.entry("entity.minecraft.spruce_chest_boat", 6),
            Map.entry("entity.minecraft.squid", 2),
            Map.entry("entity.minecraft.stray", 3),
            Map.entry("entity.minecraft.strider", 3),
            Map.entry("entity.minecraft.sulfur_cube", 3),
            Map.entry("entity.minecraft.tadpole", 2),
            Map.entry("entity.minecraft.text_display", 6),
            Map.entry("entity.minecraft.tnt", 7),
            Map.entry("entity.minecraft.tnt_minecart", 5),
            Map.entry("entity.minecraft.trader_llama", 4),
            Map.entry("entity.minecraft.trident", 3),
            Map.entry("entity.minecraft.tropical_fish", 3),
            Map.entry("entity.minecraft.turtle", 2),
            Map.entry("entity.minecraft.vex", 2),
            Map.entry("entity.minecraft.villager", 2),
            Map.entry("entity.minecraft.vindicator", 3),
            Map.entry("entity.minecraft.wandering_trader", 4),
            Map.entry("entity.minecraft.warden", 3),
            Map.entry("entity.minecraft.wind_charge", 2),
            Map.entry("entity.minecraft.witch", 2),
            Map.entry("entity.minecraft.wither", 2),
            Map.entry("entity.minecraft.wither_skeleton", 4),
            Map.entry("entity.minecraft.wither_skull", 4),
            Map.entry("entity.minecraft.wolf", 1),
            Map.entry("entity.minecraft.zoglin", 5),
            Map.entry("entity.minecraft.zombie", 2),
            Map.entry("entity.minecraft.zombie_horse", 3),
            Map.entry("entity.minecraft.zombie_nautilus", 5),
            Map.entry("entity.minecraft.zombie_villager", 4),
            Map.entry("entity.minecraft.zombified_piglin", 4)
    );
    private static final Component EMPTY_TEXT = plain(Component.text("无", NamedTextColor.GRAY));
    private static final Component NOT_LOADED_TEXT = plain(Component.text("未加载", NamedTextColor.GRAY));
    // 本服务端项目自定义修改：方块类排名标题以 & 颜色代码书写，用 legacyAmpersand 解析
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private IslandTopGui() {
    }

    public static Inventory create(List<Entry> entries, boolean[] loadedDimensions) {
        return create(entries, loadedDimensions, List.of(), Map.of());
    }

    /**
     * 本服务端项目自定义修改：新增 overload，支持在底部一行展示数据异常的岛屿。
     *
     * @param entries          排行榜条目（最多 {@link #RANKING_SLOTS} 条）
     * @param loadedDimensions 各维度是否已加载
     * @param abnormal         数据异常的岛屿条目，占用第 5 行的 2~8 格
     */
    public static Inventory create(List<Entry> entries, boolean[] loadedDimensions, List<Entry> abnormal) {
        return create(entries, loadedDimensions, abnormal, Map.of());
    }

    /**
     * 本服务端项目自定义修改：完整 overload。
     *
     * @param entries          排行榜条目（最多 {@link #RANKING_SLOTS} 条）
     * @param loadedDimensions 各维度是否已加载
     * @param abnormal         数据异常的岛屿条目，占用第 5 行第 2~8 格
     * @param blockTop         方块类排名，键为类别、值为该类别的前十名，占用第 6 行第 2~8 格
     */
    public static Inventory create(List<Entry> entries, boolean[] loadedDimensions, List<Entry> abnormal,
                                   Map<IslandBlockTopStore.Category, List<IslandBlockTopStore.Ranking>> blockTop) {
        Inventory inventory = Bukkit.createInventory(null, INVENTORY_SIZE,
                Component.text("光荣榜",NamedTextColor.RED));

        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, createFiller());
        }

        int count = Math.min(entries.size(), RANKING_SLOTS.length);
        for (int index = 0; index < count; index++) {
            inventory.setItem(RANKING_SLOTS[index], createHead(entries.get(index), loadedDimensions, index));
        }

        // 本服务端项目自定义修改：数据异常岛屿填充第 5 行第 2~8 格
        int abnormalCount = Math.min(abnormal.size(), ABNORMAL_SLOTS.length);
        for (int index = 0; index < abnormalCount; index++) {
            inventory.setItem(ABNORMAL_SLOTS[index], createAbnormalHead(abnormal.get(index), loadedDimensions));
        }

        // 本服务端项目自定义修改：方块类排名填充第 6 行第 2~8 格（枚举顺序即展示顺序）
        IslandBlockTopStore.Category[] categories = IslandBlockTopStore.Category.values();
        int blockCount = Math.min(categories.length, BLOCK_TOP_SLOTS.length);
        for (int index = 0; index < blockCount; index++) {
            IslandBlockTopStore.Category category = categories[index];
            inventory.setItem(BLOCK_TOP_SLOTS[index],
                    createBlockTopIcon(category, blockTop.getOrDefault(category, List.of())));
        }
        return inventory;
    }

    // 本服务端项目自定义修改：构建方块类排名图标。标题使用类别自带的 & 颜色代码，
    // lore 每行展示一个岛屿（序号、岛主名、数量），数量列仿照实体列表对齐：
    // 取最长的“序号. 岛主名”显示宽度，其余行补空格，使所有数量左对齐于最长行的数量列
    // （最长行仅隔一个空格）。
    private static ItemStack createBlockTopIcon(IslandBlockTopStore.Category category,
                                                List<IslandBlockTopStore.Ranking> rankings) {
        ItemStack item = new ItemStack(category.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(LEGACY_AMPERSAND.deserialize(category.title())));

        List<Component> lore = new ArrayList<>();
        if (rankings.isEmpty()) {
            lore.add(EMPTY_TEXT);
        } else {
            // 本服务端项目自定义修改：先求最长的“序号. 岛主名”显示宽度
            int maxLeftWidth = 0;
            for (int rank = 0; rank < rankings.size(); rank++) {
                maxLeftWidth = Math.max(maxLeftWidth, leftWidth(rank, rankings.get(rank).ownerName()));
            }
            for (int rank = 0; rank < rankings.size(); rank++) {
                IslandBlockTopStore.Ranking entry = rankings.get(rank);
                NamedTextColor nameColor = RANK_LABEL_COLORS[Math.min(rank, RANK_LABEL_COLORS.length - 1)];
                String prefix = (rank + 1) + ". ";
                String name = entry.ownerName();
                int padding = maxLeftWidth - (prefix.length() + displayWidth(name)) + 1;
                lore.add(plain(Component.text(prefix, NamedTextColor.DARK_GRAY)
                        .append(Component.text(name, nameColor))
                        .append(Component.text(" ".repeat(Math.max(1, padding)), NamedTextColor.GRAY))
                        .append(Component.text(Long.toString(entry.count()), NamedTextColor.WHITE))));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // 本服务端项目自定义修改：“{序号}. {岛主名}”的显示宽度（序号数字 + ". " + 名字宽度）
    private static int leftWidth(int rank, @NotNull String name) {
        return Integer.toString(rank + 1).length() + 2 + displayWidth(name);
    }

    // 本服务端项目自定义修改：估算文本显示宽度（CJK 全角字符按 2 计），用于列对齐
    private static int displayWidth(@NotNull String text) {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += isWide(text.charAt(index)) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(char character) {
        return (character >= 0x1100 && character <= 0x115F)
                || (character >= 0x2E80 && character <= 0xA4CF && character != 0x303F)
                || (character >= 0xAC00 && character <= 0xD7A3)
                || (character >= 0xF900 && character <= 0xFAFF)
                || (character >= 0xFE30 && character <= 0xFE6F)
                || (character >= 0xFF00 && character <= 0xFF60)
                || (character >= 0xFFE0 && character <= 0xFFE6);
    }

    // 本服务端项目自定义修改：构建数据异常岛屿的头颅，标题前缀标记为异常
    private static ItemStack createAbnormalHead(Entry entry, boolean[] loadedDimensions) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (entry.ownerId() != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.ownerId()));
        }
        meta.displayName(plain(Component.text("⚠ 数据异常：", NamedTextColor.DARK_RED)
                .append(Component.text(entry.ownerName(), NamedTextColor.YELLOW))
                .append(Component.text("的岛屿", NamedTextColor.AQUA))));

        List<Component> lore = new ArrayList<>();
        lore.addAll(formatPlayers(entry.players()));
        // 本服务端项目自定义修改：异常状态插入在玩家名下方一行，暗红色，以空格分隔
        Component abnormalLine = formatAbnormalLine(entry.abnormalTypes());
        if (abnormalLine != null) {
            lore.add(abnormalLine);
        }
        lore.add(formatMsptLine(entry.mspt(), loadedDimensions));
        lore.add(plain(Component.text("实体总数", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("：", NamedTextColor.GRAY))
                .append(Component.text(Long.toString(entry.entityTotal()), NamedTextColor.RED))));
        lore.addAll(formatEntityCounts(entry.entityCounts()));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // 本服务端项目自定义修改：将异常状态拼接为一行暗红色文本，多个状态以空格分隔
    private static @Nullable Component formatAbnormalLine(@NotNull List<String> abnormalTypes) {
        if (abnormalTypes.isEmpty()) {
            return null;
        }
        return plain(Component.text(String.join(" ", abnormalTypes), NamedTextColor.DARK_RED));
    }

    private static ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        meta.lore(List.of());
        meta.setHideTooltip(true);
        meta.getPersistentDataContainer().set(GUI_MARKER, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isIslandTopInventory(@NotNull Inventory inventory) {
        if (inventory.getSize() != INVENTORY_SIZE) {
            return false;
        }
        ItemStack marker = inventory.getItem(0);
        return marker != null && marker.hasItemMeta()
                && marker.getItemMeta().getPersistentDataContainer()
                .has(GUI_MARKER, PersistentDataType.BYTE);
    }

    private static ItemStack createHead(Entry entry, boolean[] loadedDimensions, int rankIndex) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (entry.ownerId() != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.ownerId()));
        }
        Component rankLabel = Component.text(RANK_LABELS[rankIndex], rankLabelColor(rankIndex));
        meta.displayName(plain(rankLabel
                .append(Component.text(entry.ownerName(), NamedTextColor.YELLOW))
                .append(Component.text("的岛屿", NamedTextColor.AQUA))));

        List<Component> lore = new ArrayList<>();
        lore.addAll(formatPlayers(entry.players()));
        lore.add(formatMsptLine(entry.mspt(), loadedDimensions));
        lore.add(plain(Component.text("实体总数", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("：", NamedTextColor.GRAY))
                .append(Component.text(Long.toString(entry.entityTotal()), NamedTextColor.GOLD))));
        lore.addAll(formatEntityCounts(entry.entityCounts()));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static NamedTextColor rankLabelColor(int rankIndex) {
        return RANK_LABEL_COLORS[Math.min(rankIndex, RANK_LABEL_COLORS.length - 1)];
    }

    private static List<Component> formatPlayers(List<String> names) {
        if (names.isEmpty()) {
            return List.of(EMPTY_TEXT);
        }

        List<Component> lines = new ArrayList<>();
        Component line = Component.empty();
        int lineLength = 0;
        for (String name : names) {
            int separatorLength = lineLength == 0 ? 0 : 2;
            if (lineLength > 0 && lineLength + separatorLength + name.length() > PLAYER_LINE_WIDTH) {
                lines.add(plain(line));
                line = Component.empty();
                lineLength = 0;
            }
            if (lineLength > 0) {
                line = line.append(Component.text(", ", NamedTextColor.GRAY));
                lineLength += 2;
            }
            line = line.append(Component.text(name, NamedTextColor.GRAY));
            lineLength += name.length();
        }
        if (lineLength > 0) {
            lines.add(plain(line));
        }
        return lines;
    }

    private static Component formatMsptLine(double[] mspt, boolean[] loadedDimensions) {
        return plain(Component.text("mspt", NamedTextColor.AQUA)
                .append(Component.text("：", NamedTextColor.GRAY))
                .append(formatMspt(valueAt(mspt, 0), isLoaded(loadedDimensions, 0), NamedTextColor.GREEN))
                .append(Component.text("，", NamedTextColor.GRAY))
                .append(formatMspt(valueAt(mspt, 1), isLoaded(loadedDimensions, 1), NamedTextColor.RED))
                .append(Component.text("，", NamedTextColor.GRAY))
                .append(formatMspt(valueAt(mspt, 2), isLoaded(loadedDimensions, 2), NamedTextColor.DARK_PURPLE)));
    }

    private static List<Component> formatEntityCounts(List<EntityCount> entityCounts) {
        if (entityCounts.isEmpty()) {
            return List.of();
        }

        int firstColumnWidth = 0;
        for (int index = 0; index < entityCounts.size(); index += 2) {
            firstColumnWidth = Math.max(firstColumnWidth, entityCountWidth(entityCounts.get(index)));
        }

        List<Component> lines = new ArrayList<>();
        for (int index = 0; index < entityCounts.size(); index += 2) {
            NamedTextColor color = ENTITY_LINE_COLORS[Math.min(index / 2, ENTITY_LINE_COLORS.length - 1)];
            EntityCount first = entityCounts.get(index);
            Component line = formatEntityCount(first, color);
            if (index + 1 < entityCounts.size()) {
                int padding = firstColumnWidth - entityCountWidth(first) + ENTITY_COLUMN_GAP;
                line = line.append(Component.text(" ".repeat(padding), NamedTextColor.GRAY))
                        .append(formatEntityCount(entityCounts.get(index + 1), color));
            }
            lines.add(plain(line));
        }
        return lines;
    }

    private static int entityCountWidth(EntityCount entityCount) {
        int translatedNameLength = 2 * ZH_CN_ENTITY_NAME_LENGTHS.getOrDefault(
                entityCount.type().translationKey(), entityCount.type().name().length()) - 1;
        return translatedNameLength + 1 + Long.toString(entityCount.count()).length();
    }

    private static Component formatEntityCount(EntityCount entityCount, NamedTextColor color) {
        return Component.translatable(entityCount.type().translationKey())
                .color(color)
                .append(Component.text("×" + entityCount.count(), NamedTextColor.GRAY));
    }

    private static Component formatMspt(double value, boolean loaded, NamedTextColor color) {
        if (!loaded) {
            return NOT_LOADED_TEXT;
        }
        return Component.text(String.format(Locale.ROOT, "%.2f", value), color);
    }

    private static double valueAt(double[] values, int index) {
        return values != null && index < values.length ? values[index] : 0.0D;
    }

    private static boolean isLoaded(boolean[] loadedDimensions, int index) {
        return loadedDimensions != null && index < loadedDimensions.length && loadedDimensions[index];
    }

    private static Component plain(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public record Entry(
            @NotNull UUID islandId,
            @Nullable UUID ownerId,
            @NotNull String ownerName,
            @NotNull List<String> players,
            double[] mspt,
            long entityTotal,
            @NotNull List<EntityCount> entityCounts,
            // 本服务端项目自定义修改：该岛屿命中的异常状态（如“疑似掉落物未销毁”），
            // 仅数据异常板块展示；无异常时为空列表。
            @NotNull List<String> abnormalTypes
    ) {
    }

    public record EntityCount(@NotNull EntityType type, long count) {
    }
}
