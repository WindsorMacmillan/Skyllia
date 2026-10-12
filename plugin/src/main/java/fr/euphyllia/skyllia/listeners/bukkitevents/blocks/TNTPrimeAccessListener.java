package fr.euphyllia.skyllia.listeners.bukkitevents.blocks;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.service.TrustService;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.TNTPrimeEvent;

public class TNTPrimeAccessListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrime(final TNTPrimeEvent event) {
        if (event.getCause() != TNTPrimeEvent.PrimeCause.PROJECTILE) return;

        final Entity primingEntity = event.getPrimingEntity();
        if (!(primingEntity instanceof Projectile projectile)
                || !(projectile.getShooter() instanceof Player player)) return;
        if (player.isOp()) return;

        final Block tnt = event.getBlock();
        final Location location = tnt.getLocation();
        final World world = location.getWorld();
        if (world == null || !SkylliaAPI.isWorldSkyblock(world)) return;

        final Island island = ListenersUtils.islandAtBlock(world, location.getBlockX(), location.getBlockZ());
        if (island == null) {
            event.setCancelled(true);
            return;
        }

        if (!isPrivilegedPlayer(player, island)) {
            event.setCancelled(true);
        }
    }

    private boolean isPrivilegedPlayer(final Player player, final Island island) {
        final Players owner = island.getOwner();
        if (owner != null && owner.getMojangId().equals(player.getUniqueId())) return true;

        final Players member = island.getMember(player.getUniqueId());
        if (member != null && member.getRoleType().getValue() >= RoleType.MEMBER.getValue()) return true;

        final TrustService trustService = SkylliaAPI.getTrustService();
        return trustService != null && trustService.isTrusted(island.getId(), player.getUniqueId());
    }
}
