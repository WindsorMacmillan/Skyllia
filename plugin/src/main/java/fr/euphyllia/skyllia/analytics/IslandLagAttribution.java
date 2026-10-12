package fr.euphyllia.skyllia.analytics;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits region MSPT between represented islands and averages the resulting
 * player estimates for each island/dimension key.
 */
public final class IslandLagAttribution {

    private IslandLagAttribution() {
    }

    public static <I, R> @NotNull Map<I, Double> estimate(
            @NotNull List<PlayerSample<I, R>> players,
            @NotNull Map<R, Double> regionMspt,
            @NotNull Map<R, Set<I>> representedIslands,
            @NotNull Map<R, Map<I, Double>> islandWeights) {
        Map<I, Double> totals = new HashMap<>();
        Map<I, Integer> counts = new HashMap<>();

        for (PlayerSample<I, R> player : players) {
            Double mspt = regionMspt.get(player.region());
            Set<I> islands = representedIslands.get(player.region());
            if (mspt == null || !Double.isFinite(mspt) || mspt < 0.0D
                    || islands == null || islands.isEmpty()) {
                continue;
            }

            double denominator = 0.0D;
            Map<I, Double> weights = islandWeights.get(player.region());
            for (I island : islands) {
                denominator += validWeight(weights == null ? null : weights.get(island));
            }

            double estimate = denominator > 0.0D
                    ? mspt * validWeight(weights == null ? null : weights.get(player.island())) / denominator
                    : mspt / islands.size();
            totals.merge(player.island(), estimate, Double::sum);
            counts.merge(player.island(), 1, Integer::sum);
        }

        Map<I, Double> result = new HashMap<>();
        for (Map.Entry<I, Double> entry : totals.entrySet()) {
            result.put(entry.getKey(), entry.getValue() / counts.get(entry.getKey()));
        }
        return Map.copyOf(result);
    }

    private static double validWeight(Double weight) {
        return weight != null && Double.isFinite(weight) && weight > 0.0D ? weight : 0.0D;
    }

    public record PlayerSample<I, R>(@NotNull I island, @NotNull R region) {
    }
}
