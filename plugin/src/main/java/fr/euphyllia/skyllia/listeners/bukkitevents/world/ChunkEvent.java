package fr.euphyllia.skyllia.listeners.bukkitevents.world;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public class ChunkEvent implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent event) {
        final World world = event.getWorld();
        if (!SkylliaAPI.isWorldSkyblock(world.getName())) return;

        final int chunkX = event.getChunk().getX();
        final int chunkZ = event.getChunk().getZ();

        Bukkit.getAsyncScheduler().runNow(Skyllia.getInstance(), unused1 ->
                SkylliaAPI.getIslandByChunk(chunkX, chunkZ));
    }
}
