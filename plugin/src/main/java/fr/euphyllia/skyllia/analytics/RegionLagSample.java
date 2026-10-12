package fr.euphyllia.skyllia.analytics;

import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Immutable region-level observation used to fit island workload weights.
 */
public record RegionLagSample(
        @NotNull String worldName,
        @NotNull World.Environment environment,
        long tickRegionId,
        double mspt,
        int players,
        long loadedChunks,
        @NotNull Map<EntityType, Long> entityCounts,
        long timestamp
) {
    public RegionLagSample {
        entityCounts = Map.copyOf(entityCounts);
    }
}
