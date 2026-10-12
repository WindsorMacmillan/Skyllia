package fr.euphyllia.skyllia.analytics;

import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Relative workload weights used when splitting a region's measured MSPT.
 */
public record LagWeights(
        @NotNull Map<EntityType, Double> entityWeights,
        double chunkWeight,
        double baselineMspt,
        int fittedSamples
) {
    public static final double DEFAULT_ENTITY_WEIGHT = 1.0D;
    public static final double DEFAULT_CHUNK_WEIGHT = 0.1D;

    public LagWeights {
        entityWeights = Map.copyOf(entityWeights);
        chunkWeight = Double.isFinite(chunkWeight) && chunkWeight > 0.0D
                ? chunkWeight
                : DEFAULT_CHUNK_WEIGHT;
        baselineMspt = Double.isFinite(baselineMspt) ? baselineMspt : 0.0D;
    }

    public static LagWeights defaults() {
        return new LagWeights(Map.of(), DEFAULT_CHUNK_WEIGHT, 0.0D, 0);
    }

    public double entityWeight(@NotNull EntityType type) {
        Double weight = entityWeights.get(type);
        return weight != null && Double.isFinite(weight) && weight > 0.0D
                ? weight
                : DEFAULT_ENTITY_WEIGHT;
    }
}
