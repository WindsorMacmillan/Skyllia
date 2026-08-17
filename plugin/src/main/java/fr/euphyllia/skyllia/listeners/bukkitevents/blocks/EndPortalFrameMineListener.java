package fr.euphyllia.skyllia.listeners.bukkitevents.blocks;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 玩家持续挖掘末地门框架 30 秒后强制破坏该方块。
 * 挖掘期间每 0.5 秒向 actionbar 发送进度条。
 * 权限条件与 {@code BlockBreakPermissions} 一致（检查 block.break）。
 * 松开左键或中断挖掘则重置进度。
 */
public class EndPortalFrameMineListener implements Listener {

    private static final long BREAK_DELAY_TICKS = 600L;   // 30 秒
    private static final long PROGRESS_INTERVAL = 10L;     // 0.5 秒
    private static final int TOTAL_STEPS = (int) (BREAK_DELAY_TICKS / PROGRESS_INTERVAL); // 60 步

    private final Map<UUID, MiningSession> sessions = new ConcurrentHashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onBlockDamage(final BlockDamageEvent event) {
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final UUID playerId = player.getUniqueId();
        final Location blockLoc = block.getLocation();

        // ── 位置变更检查：玩家已有活跃会话但挖的是不同的方块 → 中断 ──
        MiningSession existing = sessions.get(playerId);
        if (existing != null && !existing.blockLoc.equals(blockLoc)) {
            cancelSession(playerId);
        }

        if (block.getType() != Material.END_PORTAL_FRAME) return;
        if (!SkylliaAPI.isWorldSkyblock(block.getWorld())) return;
        if (player.isOp()) return;

        // ── 权限检查：与 BlockBreakPermissions 一致 ──
        final World world = block.getWorld();
        final int bx = block.getX();
        final int by = block.getY();
        final int bz = block.getZ();
        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) return;

        final boolean hasBypass = PlayerUtils.hasPermission(player, "skyllia.player.break.bypass");
        final PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        final PermissionId blockBreakPid = registry.getIfPresent(new NamespacedKey("skyllia", "block.break"));
        final boolean hasPermission = hasBypass || (blockBreakPid != null && SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, blockBreakPid, null, ConfigLoader.general.getDebugSettings().permission()));
        if (!hasPermission) return;

        // 边界检查
        if (!hasBypass) {
            ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, event);
        }

        // 如果已经在挖掘同一个方块，忽略重复触发
        if (existing != null && existing.blockLoc.equals(blockLoc)) return;

        // 创建新会话
        cancelSession(playerId);
        MiningSession session = new MiningSession(player, blockLoc);
        sessions.put(playerId, session);

        // 取消默认的方块破坏行为
        event.setCancelled(true);

        // 启动进度更新链
        scheduleNextProgress(player, session, 0);
        // 启动 30 秒后的强制破坏
        player.getScheduler().runDelayed(SkylliaAPI.getPlugin(), scheduledTask -> {
            if (!session.active.get()) return;
            forceBreakBlock(player, blockLoc);
            cancelSession(playerId);
        }, null, BREAK_DELAY_TICKS);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDamageAbort(final BlockDamageAbortEvent event) {
        cancelSession(event.getPlayer().getUniqueId());
    }

    private void cancelSession(final UUID playerId) {
        MiningSession session = sessions.remove(playerId);
        if (session != null) {
            session.active.set(false);
        }
    }

    private void scheduleNextProgress(final Player player, final MiningSession session, final int step) {
        if (step >= TOTAL_STEPS || !session.active.get()) return;

        player.getScheduler().runDelayed(SkylliaAPI.getPlugin(), scheduledTask -> {
            if (!session.active.get()) return;
            sendProgressBar(player, step + 1);
            scheduleNextProgress(player, session, step + 1);
        }, null, PROGRESS_INTERVAL);
    }

    /**
     * 强制破坏末地门框架方块（不掉落物品），播放粒子效果和音效。
     */
    private void forceBreakBlock(final Player player, final Location loc) {
        if (!player.isOnline()) return;
        if (loc.getBlock().getType() != Material.END_PORTAL_FRAME) return;

        // 再次验证权限
        final World world = loc.getWorld();
        final Island island = ListenersUtils.islandAtBlock(world, loc.getBlockX(), loc.getBlockZ());
        if (island == null) return;

        final boolean hasBypass = PlayerUtils.hasPermission(player, "skyllia.player.break.bypass");
        final PermissionRegistry registry = SkylliaAPI.getPermissionRegistry();
        final PermissionId blockBreakPid = registry.getIfPresent(new NamespacedKey("skyllia", "block.break"));
        final boolean hasPermission = hasBypass || (blockBreakPid != null && SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, blockBreakPid, null, ConfigLoader.general.getDebugSettings().permission()));
        if (!hasPermission) return;

        // 播放方块破碎粒子和玻璃破裂音效
        BlockData blockData = Material.END_PORTAL_FRAME.createBlockData();
        loc.getWorld().spawnParticle(Particle.BLOCK, loc.clone().add(0.5, 0.5, 0.5), 30, 0.4, 0.4, 0.4, blockData);
        loc.getWorld().playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);

        loc.getBlock().setType(Material.AIR, false);
        player.sendActionBar(Component.text("✓ 末地门框架已破坏", NamedTextColor.GREEN));
    }

    private void sendProgressBar(final Player player, final int currentStep) {
        float progress = Math.min(1.0f, (float) currentStep / TOTAL_STEPS);
        int totalDots = 30;
        int filledDots = Math.round(progress * totalDots);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < totalDots; i++) {
            sb.append(i < filledDots ? "§a|" : "§f.");
        }
        int secondsLeft = (int) Math.ceil((1.0 - progress) * (BREAK_DELAY_TICKS / 20));
        sb.append(" §e").append(secondsLeft).append("s");

        player.sendActionBar(Component.text(sb.toString()));
    }

    private static class MiningSession {
        final Location blockLoc;
        final AtomicBoolean active = new AtomicBoolean(true);

        MiningSession(Player player, Location blockLoc) {
            this.blockLoc = blockLoc.clone();
        }
    }
}
