package fr.euphyllia.skyllia.api.service;

import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service that manages trusted players on islands.
 * <p>
 * A player added as trusted on an island is granted the same permissions
 * as those defined for the {@link RoleType#MEMBER} role, without officially
 * being part of the island.
 * <p>
 * This class is thread-safe: read and write operations can be performed
 * concurrently from multiple threads without external synchronization.
 */
public class TrustService {
    /**
     * Mapping between an island's unique identifier and the set of trusted
     * players associated with it.
     */
    private final ConcurrentHashMap<UUID, Set<UUID>> trustedByIsland = new ConcurrentHashMap<>();

    /** Replaces the in-memory cache with data loaded from the island database. */
    public void load(Map<UUID, Set<UUID>> trustedPlayers) {
        trustedByIsland.clear();
        if (trustedPlayers == null) return;
        trustedPlayers.forEach((islandId, players) -> {
            if (islandId == null || players == null || players.isEmpty()) return;
            Set<UUID> copy = ConcurrentHashMap.newKeySet();
            copy.addAll(players);
            trustedByIsland.put(islandId, copy);
        });
    }

    /**
     * Adds a player to the trusted list of an island.
     * <p>
     * If the island does not yet have a trusted list, one is created
     * automatically.
     *
     * @param islandId the unique identifier of the island
     * @param playerId the unique identifier of the player to add
     * @return {@code true} if the player was successfully added,
     * {@code false} if they were already trusted on this island
     */
    public boolean addTrusted(UUID islandId, UUID playerId) {
        return trustedByIsland
                .computeIfAbsent(islandId, __ -> ConcurrentHashMap.newKeySet())
                .add(playerId);
    }

    /**
     * Removes a player from the trusted list of an island.
     * <p>
     * If the list becomes empty after removal, the entry associated with
     * the island is also discarded to avoid keeping unused collections
     * in memory.
     *
     * @param islandId the unique identifier of the island
     * @param playerId the unique identifier of the player to remove
     * @return {@code true} if the player was successfully removed,
     * {@code false} if the island does not exist or if the player
     * was not trusted on this island
     */
    public boolean removeTrusted(UUID islandId, UUID playerId) {
        Set<UUID> set = trustedByIsland.get(islandId);
        if (set == null) return false;
        boolean removed = set.remove(playerId);
        if (set.isEmpty()) trustedByIsland.remove(islandId, set);
        return removed;
    }

    /**
     * Checks whether a player is part of the trusted list of an island.
     *
     * @param islandId the unique identifier of the island
     * @param playerId the unique identifier of the player to check
     * @return {@code true} if the player is trusted on this island,
     * {@code false} otherwise
     */
    public boolean isTrusted(UUID islandId, UUID playerId) {
        Set<UUID> set = trustedByIsland.get(islandId);
        return set != null && set.contains(playerId);
    }

    /**
     * Returns an immutable copy of the trusted players of an island.
     * <p>
     * The returned copy is independent of the internal collection: any
     * subsequent modification of the trusted players will not be reflected
     * in the returned {@link Set}.
     *
     * @param islandId the unique identifier of the island
     * @return an immutable {@link Set} containing the UUIDs of the trusted
     * players, or {@code null} if the island has no trusted players
     */
    public @Nullable Set<UUID> getTrusted(UUID islandId) {
        Set<UUID> set = trustedByIsland.get(islandId);
        return set != null ? Set.copyOf(set) : null;
    }

    /**
     * Clears the entire trusted list of a given island.
     * <p>
     * Useful when an island is deleted or its settings are reset.
     *
     * @param islandId the unique identifier of the island whose trusted
     *                 list must be cleared
     */
    public void clearIsland(UUID islandId) {
        trustedByIsland.remove(islandId);
    }

    /**
     * Clears the trusted lists of all registered islands.
     * <p>
     * This method completely wipes the service state. Use with caution,
     * for example during a full plugin reload.
     */
    public void clearAll() {
        trustedByIsland.clear();
    }
}
