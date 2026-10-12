package fr.euphyllia.skyllia.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IslandLagAttributionTest {

    @Test
    void splitsSharedRegionAndAveragesIslandPlayers() {
        String islandA = "overworld:A";
        String islandB = "overworld:B";

        Map<String, Double> result = IslandLagAttribution.estimate(
                List.of(
                        new IslandLagAttribution.PlayerSample<>(islandA, "region-1"),
                        new IslandLagAttribution.PlayerSample<>(islandB, "region-1"),
                        new IslandLagAttribution.PlayerSample<>(islandB, "region-2")
                ),
                Map.of("region-1", 30.0D, "region-2", 20.0D),
                Map.of(
                        "region-1", Set.of(islandA, islandB),
                        "region-2", Set.of(islandB)
                ),
                Map.of("region-1", Map.of(islandA, 1.0D, islandB, 2.0D),
                        "region-2", Map.of(islandB, 1.0D))
        );

        assertEquals(10.0D, result.get(islandA), 1.0E-9D);
        assertEquals(20.0D, result.get(islandB), 1.0E-9D);
    }

    @Test
    void keepsDimensionsIndependent() {
        String overworld = "overworld:A";
        String nether = "nether:A";

        Map<String, Double> result = IslandLagAttribution.estimate(
                List.of(
                        new IslandLagAttribution.PlayerSample<>(overworld, "overworld-region"),
                        new IslandLagAttribution.PlayerSample<>(nether, "nether-region")
                ),
                Map.of("overworld-region", 12.0D, "nether-region", 7.0D),
                Map.of(
                        "overworld-region", Set.of(overworld),
                        "nether-region", Set.of(nether)
                ),
                Map.of("overworld-region", Map.of(overworld, 3.0D),
                        "nether-region", Map.of(nether, 99.0D))
        );

        assertEquals(12.0D, result.get(overworld), 1.0E-9D);
        assertEquals(7.0D, result.get(nether), 1.0E-9D);
    }

    @Test
    void countsEachRepresentedIslandOnceInDenominator() {
        String islandA = "A";
        String islandB = "B";

        Map<String, Double> result = IslandLagAttribution.estimate(
                List.of(
                        new IslandLagAttribution.PlayerSample<>(islandA, "region"),
                        new IslandLagAttribution.PlayerSample<>(islandB, "region"),
                        new IslandLagAttribution.PlayerSample<>(islandB, "region")
                ),
                Map.of("region", 30.0D),
                Map.of("region", Set.of(islandA, islandB)),
                Map.of("region", Map.of(islandA, 1.0D, islandB, 2.0D))
        );

        assertEquals(10.0D, result.get(islandA), 1.0E-9D);
        assertEquals(20.0D, result.get(islandB), 1.0E-9D);
    }
}
