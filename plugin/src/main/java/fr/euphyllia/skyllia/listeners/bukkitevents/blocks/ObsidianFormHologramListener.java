package fr.euphyllia.skyllia.listeners.bukkitevents.blocks;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.listeners.permissions.player.ConvertObsidianToLavaPermissions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class ObsidianFormHologramListener implements Listener {

    private static final String OBSIDIAN_RESTORE_HINT = "§6失误了？用§f桶§6对§5黑曜石§6右键\n§c可还原岩浆，下次小心~";
    private static final Component OBSIDIAN_RESTORE_HINT_COMPONENT = LegacyComponentSerializer.legacySection()
            .deserialize(OBSIDIAN_RESTORE_HINT);

    private final Skyllia plugin;
    private final NamespacedKey EXPIRE_KEY;
    private final ConcurrentHashMap<Long, java.util.UUID> holograms = new ConcurrentHashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(ObsidianFormHologramListener.class);

    public ObsidianFormHologramListener(Skyllia plugin) {
        this.plugin = plugin;
        this.EXPIRE_KEY = new NamespacedKey(plugin, "hint_expire");
    }

    @EventHandler
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        Material newType = event.getNewState().getType();

        // 1) 圆石 → 深板岩圆石（y<0）
        if (newType == Material.COBBLESTONE && block.getY() < 0) {
            event.getNewState().setType(Material.COBBLED_DEEPSLATE);
            return;
        }

        // 2) 黑曜石悬浮字逻辑
        if (newType != Material.OBSIDIAN) return;

        ConvertObsidianToLavaPermissions.addAllowedObsidian(block.getLocation());

        long posKey = positionToKey(block.getLocation());
        java.util.UUID existingId = holograms.get(posKey);
        if (existingId != null) {
            TextDisplay existing = (TextDisplay) Bukkit.getEntity(existingId);
            if (existing != null && existing.isValid()) {
                existing.getPersistentDataContainer().set(EXPIRE_KEY,
                        PersistentDataType.LONG,
                        System.currentTimeMillis() + 15_000L);
                existing.getScheduler().runDelayed(plugin,
                        scheduledTask -> {
                            existing.remove();
                            holograms.remove(posKey);
                        }, null, 20 * 15);
                //logger.info("[悬浮字] 刷新已有悬浮字于 {}", block.getLocation());
                return;
            } else {
                holograms.remove(posKey);
            }
        }
        Location hologramLoc = block.getLocation().add(0.5, 1.0, 0.5);
        TextDisplay display = block.getWorld().spawn(hologramLoc, TextDisplay.class, d -> {
            d.text(OBSIDIAN_RESTORE_HINT_COMPONENT);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setSeeThrough(true);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBillboard(Display.Billboard.CENTER);
            d.setLineWidth(200);
            d.getPersistentDataContainer().set(EXPIRE_KEY,
                    PersistentDataType.LONG,
                    System.currentTimeMillis() + 15_000L);
        });

        holograms.put(posKey, display.getUniqueId());
        display.getScheduler().runDelayed(plugin,
                scheduledTask -> {
                    display.remove();
                    holograms.remove(posKey);
                }, null, 20 * 15);
        //logger.info("[悬浮字] 创建新悬浮字于 {}", block.getLocation());
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TextDisplay display)) return;

        Long expire = display.getPersistentDataContainer().get(EXPIRE_KEY, PersistentDataType.LONG);
        if (expire == null) return;

        if (System.currentTimeMillis() >= expire) {
            display.getScheduler().execute(plugin, display::remove, null, 1);
        }
    }

    @EventHandler
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof TextDisplay display)) return;
        if (!OBSIDIAN_RESTORE_HINT_COMPONENT.equals(display.text())) return;

        Long expire = display.getPersistentDataContainer().get(EXPIRE_KEY, PersistentDataType.LONG);
        if (expire != null && System.currentTimeMillis() < expire) {
            long delayTicks = Math.max(1L, (expire - System.currentTimeMillis() + 49L) / 50L);
            display.getScheduler().runDelayed(plugin, scheduledTask -> removeHologram(display), null, delayTicks);
            return;
        }

        display.getScheduler().execute(plugin, () -> removeHologram(display), null, 1);
    }

    private long positionToKey(Location loc) {
        return ((long) loc.getBlockX() << 32) | (loc.getBlockZ() & 0xFFFFFFFFL);
    }

    private void removeHologram(TextDisplay display) {
        holograms.remove(positionToKey(display.getLocation()));
        display.remove();
    }
}
