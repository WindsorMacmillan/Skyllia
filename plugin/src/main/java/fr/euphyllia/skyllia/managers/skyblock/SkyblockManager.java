package fr.euphyllia.skyllia.managers.skyblock;

import com.google.common.base.Preconditions;
import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.event.PrepareSkyblockCreateEvent;
import fr.euphyllia.skyllia.api.event.SkyblockRemoveMemberEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.enums.RemovalCause;
import fr.euphyllia.skyllia.api.skyblock.model.IslandSettings;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.api.utils.Keys;
import fr.euphyllia.skyllia.cache.SkyblockCache;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages the creation, retrieval, and modification of Skyblock islands, including member management,
 * island permissions, warps, and other properties.
 */
public class SkyblockManager {

    private static final Logger LOGGER = LogManager.getLogger(SkyblockManager.class);

    private static final int BLOCKS_PER_REGION = 512;
    private static final int REGION_HALF_SIZE = 256;

    private final Skyllia plugin;
    private final SkyblockCache cache;

    private final ConcurrentHashMap<Long, UUID> islandByRegion = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Set<Long>> regionsByIsland = new ConcurrentHashMap<>();

    /**
     * How long a confirmed "no island in this region" answer is remembered.
     * <p>
     * Kept deliberately short: a region can be claimed by a new island at any
     * time, and while {@link #reindexIslandCoverage} proactively clears the
     * negative entries it covers, a short TTL is the safety net for any path
     * that indexes coverage lazily.
     */
    private static final long NEGATIVE_REGION_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    /**
     * Negative cache for region lookups: packed region key → absolute expiry
     * ({@link System#nanoTime()}-based) until which the region is known to
     * contain no island.
     * <p>
     * Without this, every event fired in a region that belongs to no island
     * (the buffer zones between islands — with the default
     * {@code region-distance}, the vast majority of regions) triggers a
     * synchronous SQL query on the tick thread that handles it:
     * {@code PlayerMoveEvent} alone can produce ~20 queries per second per
     * player flying through a buffer zone. A confirmed miss is therefore worth
     * remembering just as much as a hit.
     * <p>
     * Entries are cleared when {@link #reindexIslandCoverage} claims the
     * region for an island, and expire on their own after
     * {@link #NEGATIVE_REGION_TTL_NANOS} otherwise.
     */
    private final ConcurrentHashMap<Long, Long> noIslandRegions = new ConcurrentHashMap<>();

    /**
     * Guards the single database write that claims the next free region for a
     * new island ({@code insertIslands}, an {@code INSERT ... SELECT ...
     * ORDER BY ... LIMIT 1} against the spiral table).
     * <p>
     * That statement is not protected by a uniqueness constraint on
     * (region_x, region_z) on every supported database engine — SQLite and
     * MariaDB rely only on the primary key {@code (island_id, region_x,
     * region_z)}, which does not prevent two different islands from claiming
     * the same coordinates (only PostgreSQL has an explicit partial unique
     * index for this). Concurrent, unsynchronized calls to
     * {@link #createIsland} could therefore race and silently assign the
     * same region to two islands on those engines.
     * <p>
     * Serializing this one short, fast write at the JVM level closes that
     * race regardless of the configured database engine or its isolation
     * level, while still allowing the slow parts of island creation
     * (schematic paste, teleport, world border) to run fully in parallel —
     * see {@code IslandCreationQueue} .
     */
    private static final Object REGION_ALLOCATION_LOCK = new Object();

    public SkyblockManager(Skyllia plugin, SkyblockCache cache) {
        this.plugin = plugin;
        this.cache = cache;
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static int chunkToRegion(int chunk) {
        return chunk >> 5;
    }

    private static int blockToRegion(int block) {
        return block >> 9;
    }

    private static int regionCenterBlock(int region) {
        return (region << 9) + REGION_HALF_SIZE;
    }

    private void unindexIsland(UUID islandId) {
        Set<Long> keys = regionsByIsland.remove(islandId);
        if (keys != null) {
            for (long k : keys) {
                islandByRegion.remove(k, islandId);
            }
        }
    }

    private void reindexIslandCoverage(Island island) {
        RegionCoordinate root = island.getRegionCoordinate();
        if (root == null) return;

        int rootRx = root.x();
        int rootRz = root.z();

        int centerX = regionCenterBlock(rootRx);
        int centerZ = regionCenterBlock(rootRz);

        int radiusBlocks = (int) (island.getSize() / 2.0);

        int minRx = blockToRegion(centerX - radiusBlocks);
        int maxRx = blockToRegion(centerX + radiusBlocks - 1);
        int minRz = blockToRegion(centerZ - radiusBlocks);
        int maxRz = blockToRegion(centerZ + radiusBlocks - 1);

        UUID id = island.getId();

        unindexIsland(id);

        Set<Long> now = ConcurrentHashMap.newKeySet();
        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                long k = pack(rx, rz);
                // This region is now covered by an island: any remembered
                // "no island here" answer is obsolete.
                noIslandRegions.remove(k);
                UUID previous = islandByRegion.put(k, id);
                if (previous != null && !previous.equals(id)) {
                    LOGGER.debug("Region collision: ({},{}) was claimed by {}, now overwritten by {} (root={}, size={})",
                            rx, rz, previous, id, root, island.getSize());
                }
                now.add(k);
            }
        }

        LOGGER.debug("Reindexed island {} root=({},{}) size={} -> regions rx[{}..{}] rz[{}..{}] ({} regions)",
                id, rootRx, rootRz, island.getSize(),
                minRx, maxRx, minRz, maxRz,
                (maxRx - minRx + 1) * (maxRz - minRz + 1));

        regionsByIsland.put(id, now);
    }

    public void cacheIslandAndIndex(Island island) {
        cache.putIsland(island);
        reindexIslandCoverage(island);
    }

    /**
     * Creates a new island with the specified {@link IslandSettings}.
     * <p>
     * Concurrency: safe to call concurrently from multiple threads (e.g. from
     * {@code IslandCreationQueue} running several creations in parallel). The
     * step that claims a region for the island is internally serialized via
     * {@link #REGION_ALLOCATION_LOCK}, so two concurrent calls can never be
     * assigned the same region.
     *
     * @param islandId   The UUID of the new island.
     * @param islandType The settings to apply to the new island.
     * @param owners     The owner of the island, must have {@link RoleType#OWNER}.
     * @return {@code true} if the island was successfully created,
     * {@code false} if creation was cancelled or an error occurred.
     * @throws IllegalArgumentException If any argument is null.
     * @throws IllegalStateException    If the owner's island ID is already set.
     */
    public Boolean createIsland(UUID islandId, IslandSettings islandType, Players owners) {
        return createIsland(islandId, islandType, owners, null);
    }

    /**
     * Creates an island at a specific region instead of allocating the next
     * available location from the spiral table.
     *
     * @param islandId   the new island identifier
     * @param islandType the settings to apply
     * @param owners     the island owner
     * @param region     the region to claim
     * @return {@code true} if the island was created at the requested region
     */
    public Boolean createIslandAtRegion(UUID islandId, IslandSettings islandType, Players owners, RegionCoordinate region) {
        Preconditions.checkArgument(region != null, "region cannot be null");
        return createIsland(islandId, islandType, owners, region);
    }

    private Boolean createIsland(UUID islandId, IslandSettings islandType, Players owners, @Nullable RegionCoordinate region) {
        Preconditions.checkArgument(islandType != null, "islandType cannot be null");
        Preconditions.checkArgument(islandId != null, "islandId cannot be null");
        Preconditions.checkArgument(owners != null, "owners cannot be null");
        if (!owners.getRoleType().equals(RoleType.OWNER)) {
            LOGGER.error("Only owners can be created for islands");
            return false;
        }
        PrepareSkyblockCreateEvent event = new PrepareSkyblockCreateEvent(islandId, islandType);
        Bukkit.getPluginManager().callEvent(event);

        // Check if the event was cancelled
        if (event.isCancelled()) {
            return false;
        }

        try {
            // Construct a new IslandHook instance before inserting it into the database
            Island futureIsland = new IslandHook(
                    event.getIslandId(),
                    event.getIslandSettings().maxMembers(),
                    region,
                    event.getIslandSettings().rayon(),
                    null
            );

            boolean success;
            synchronized (REGION_ALLOCATION_LOCK) {
                var islandDataQuery = plugin.getInterneAPI().getIslandQuery().getIslandDataQuery();
                success = region == null
                        ? islandDataQuery.insertIslands(futureIsland)
                        : islandDataQuery.insertIslandAtRegion(futureIsland);
            }

            if (success) {
                var permQuery = plugin.getInterneAPI()
                        .getIslandQuery()
                        .getIslandPermissionQuery();
                if (permQuery != null && ConfigLoader.permissionsV2 != null) {
                    String typeKey = event.getIslandSettings().name();

                    var blobs = ConfigLoader.permissionsV2.buildDefaultRoleBlobs(typeKey);
                    for (var entry : blobs.entrySet()) {
                        permQuery.saveRole(event.getIslandId(), entry.getKey(), entry.getValue());
                    }

                    if (ConfigLoader.islandFlags != null) {
                        for (WorldConfig world : SkylliaAPI.getRegisteredWorlds()) {
                            byte[] flagsBlob = ConfigLoader.islandFlags.buildDefaultFlagBlob(typeKey, world.getWorldName());
                            permQuery.saveIslandFlags(event.getIslandId(), flagsBlob, world.getWorldName());
                        }
                    }
                }

                owners.setIslandId(event.getIslandId());
                plugin.getInterneAPI()
                        .getIslandQuery()
                        .getIslandMemberQuery()
                        .updateMember(futureIsland, owners);

                invalidateIsland(event.getIslandId());
                // Load and index the freshly created island right away. This
                // closes the window where a recent negative region entry
                // (a player flying through the buffer zone moments before)
                // could mask the new island's regions, and warms the cache
                // before the owner is teleported onto it.
                loadIslandFromDb(event.getIslandId());
                return true;
            } else {
                LOGGER.fatal("Exception while creating a new island asynchronously");
                return false;
            }
        } catch (Exception e) {
            LOGGER.fatal("Exception before starting async creation", e);
            return false;
        }
    }

    public void invalidateIsland(UUID islandId) {
        cache.invalidateIsland(islandId);
        unindexIsland(islandId);
    }

    public @Nullable Players getOwnerByIslandId(UUID islandId) {
        Players cached = cache.getOwner(islandId, () -> loadOwnerFromDb(islandId));
        if (cached != null) return cached;

        return loadOwnerFromDb(islandId);
    }

    /**
     * Loads the island owner from the database and populates the cache.
     * Called synchronously on a cold miss, and asynchronously as the
     * stale-while-revalidate reload.
     */
    private @Nullable Players loadOwnerFromDb(UUID islandId) {
        Players owner = plugin.getInterneAPI().getIslandQuery().getIslandMemberQuery().getOwnerByIslandId(islandId);
        if (owner != null) cache.putOwner(islandId, owner);
        return owner;
    }

    /**
     * Retrieves an island by its unique islandId.
     *
     * @param islandId The UUID of the island.
     * @return A {@link CompletableFuture} containing the {@link Island}, or {@code null} if not found.
     */
    public Island getIslandByIslandId(UUID islandId) {
        Island cached = cache.getIsland(islandId, () -> loadIslandFromDb(islandId));
        if (cached != null) return cached;

        return loadIslandFromDb(islandId);
    }

    /**
     * Loads an island by id from the database, populating the cache and the
     * region index. Called synchronously on a cold miss, and asynchronously as
     * the stale-while-revalidate reload.
     */
    private @Nullable Island loadIslandFromDb(UUID islandId) {
        Island island = plugin.getInterneAPI().getIslandQuery().getIslandDataQuery().getIslandByIslandId(islandId);
        if (island != null) {
            cacheIslandAndIndex(island);
        }
        return island;
    }

    public List<Island> getAllIslandsValid() {
        return this.plugin.getInterneAPI().getIslandQuery().getIslandDataQuery().getAllIslandsValid();
    }

    /**
     * Disables or enables an island.
     *
     * @param island   The {@link Island} to update.
     * @param disabled {@code true} to disable, {@code false} to enable.
     * @return A {@link CompletableFuture} with {@code true} if successfully updated, {@code false} otherwise.
     */
    public Boolean disableIsland(Island island, boolean disabled) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandUpdateQuery().updateDisable(island, disabled);
        if (ok) {
            invalidateIsland(island.getId());
        }
        return ok;
    }

    /**
     * Checks if an island is currently disabled.
     *
     * @param island The {@link Island}.
     * @return A {@link CompletableFuture} with {@code true} if disabled, {@code false} otherwise.
     */
    public Boolean isDisabledIsland(Island island) {
        SkyblockCache.IslandStateSnapshot state = getStateSnapshot(island);
        return state.disabled();
    }

    /**
     * Returns the island state snapshot, serving a fresh or stale cached value
     * when available and falling back to a synchronous load on a cold miss.
     */
    private SkyblockCache.IslandStateSnapshot getStateSnapshot(Island island) {
        SkyblockCache.IslandStateSnapshot state = cache.getState(island.getId(), () -> loadStateSnapshotFromDb(island));
        if (state != null) return state;
        return loadStateSnapshotFromDb(island);
    }

    /**
     * Loads the full island state (disabled, private, locked, max members, size)
     * from the database in one pass and populates the state cache. Called
     * synchronously on a cold miss, and asynchronously as the
     * stale-while-revalidate reload.
     */
    private SkyblockCache.IslandStateSnapshot loadStateSnapshotFromDb(Island island) {
        var updateQuery = plugin.getInterneAPI().getIslandQuery().getIslandUpdateQuery();
        boolean disabled = updateQuery.isDisabledIsland(island);
        boolean priv = updateQuery.isPrivateIsland(island);
        boolean locked = updateQuery.isLockedIsland(island);
        int max = plugin.getInterneAPI().getIslandQuery().getIslandDataQuery().getMaxMemberInIsland(island);
        double size = island.getSize();

        SkyblockCache.IslandStateSnapshot snapshot =
                new SkyblockCache.IslandStateSnapshot(disabled, priv, locked, max, size);
        cache.putState(island.getId(), snapshot);
        return snapshot;
    }

    /**
     * Sets whether an island is private.
     *
     * @param island        The {@link Island}.
     * @param privateIsland {@code true} for private, {@code false} for public.
     * @return A {@link CompletableFuture} with {@code true} if the state was successfully updated.
     */
    public Boolean setPrivateIsland(Island island, boolean privateIsland) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandUpdateQuery().updatePrivate(island, privateIsland);
        if (ok) {
            cache.invalidateState(island.getId());
        }
        return ok;
    }

    /**
     * Checks if an island is private.
     *
     * @param island The {@link Island}.
     * @return A {@link CompletableFuture} with {@code true} if private, {@code false} otherwise.
     */
    public Boolean isPrivateIsland(Island island) {
        return getStateSnapshot(island).priv();
    }

    public @Nullable Island getIslandByRegion(int rx, int rz) {
        long key = pack(rx, rz);
        UUID islandId = islandByRegion.get(key);

        // Fix: Prevent wrong cached island keys returned to a new query
        if (islandId != null) {
            Island cachedIsland = cache.getIsland(islandId);
            // Validate the cachedIsland position
            if (cachedIsland != null) {
                RegionCoordinate reg = cachedIsland.getRegionCoordinate();
                if (reg.x() != rx || reg.z() != rz) {
                    islandByRegion.remove(key); // Clean the wrong key
                    islandId = null;            // Force query again
                }
            }
        }
        if (islandId != null) {
            final UUID knownId = islandId;
            Island cached = cache.getIsland(knownId, () -> loadIslandFromDb(knownId));
            if (cached != null) {
                return cached;
            }
            Island fromDbById = loadIslandFromDb(knownId);
            if (fromDbById != null) {
                return fromDbById;
            } else {
                islandByRegion.remove(key, islandId);
            }
        }

        // Negative cache: the database recently confirmed there is no island
        // in this region. Answer without a JDBC round-trip on the tick thread.
        Long negativeUntil = noIslandRegions.get(key);
        if (negativeUntil != null) {
            if (System.nanoTime() < negativeUntil) {
                return null;
            }
            noIslandRegions.remove(key, negativeUntil);
        }

        RegionCoordinate position = new RegionCoordinate(rx, rz);
        Island island = plugin.getInterneAPI()
                .getIslandQuery().getIslandDataQuery().getIslandByRegion(position);
        if (island != null) {
            cacheIslandAndIndex(island);
        } else {
            noIslandRegions.put(key, System.nanoTime() + NEGATIVE_REGION_TTL_NANOS);
        }
        return island;
    }

    private int regionRadiusFromConfig() {
        int d = ConfigLoader.general.getIslandSettings().regionDistance();
        if (d <= 0) return 1;
        int maxRadiusBlocks = d / 2;
        return Math.max(1, (int) Math.ceil(maxRadiusBlocks / 512.0));
    }

    public @Nullable Island getIslandByChunk(Chunk chunk) {
        if (chunk == null) return null;
        return getIslandByChunk(chunk.getX(), chunk.getZ());
    }

    public @Nullable Island getIslandByChunk(int chunkX, int chunkZ) {
        return getIslandByRegion(chunkToRegion(chunkX), chunkToRegion(chunkZ));
    }

    public @Nullable Island getIslandByRegion(RegionCoordinate region) {
        if (region == null) return null;
        return getIslandByRegion(region.x(), region.z());
    }

    /**
     * @deprecated Use {@link #getIslandByRegion(RegionCoordinate)} instead.
     */
    @Deprecated(forRemoval = true, since = "3.x")
    @ApiStatus.ScheduledForRemoval(inVersion = "4.x")
    public @Nullable Island getIslandByRegion(Position position) {
        if (position == null) return null;
        return getIslandByRegion(position.x(), position.z());
    }


    /**
     * Retrieves the island owned by a specific player.
     *
     * @param playerId The player's UUID.
     * @return A {@link CompletableFuture} that completes with the {@link Island}, or {@code null} if none.
     */
    public @Nullable Island getIslandByOwner(UUID playerId) {
        UUID cachedIslandId = cache.getIslandIdByPlayer(playerId, () -> loadIslandByOwnerFromDb(playerId));
        if (cachedIslandId != null) {
            Island island = cache.getIsland(cachedIslandId, () -> loadIslandFromDb(cachedIslandId));
            if (island != null) return island;
        }

        return loadIslandByOwnerFromDb(playerId);
    }

    private @Nullable Island loadIslandByOwnerFromDb(UUID playerId) {
        Island island = plugin.getInterneAPI().getIslandQuery().getIslandDataQuery().getIslandByOwnerId(playerId);
        if (island != null) {
            cache.putIslandIdByPlayer(playerId, island.getId());
            cacheIslandAndIndex(island);
        }
        return island;
    }

    /**
     * Retrieves any island the player is a member of (not necessarily the owner).
     *
     * @param playerId The player's UUID.
     * @return A {@link CompletableFuture} that completes with the {@link Island}, or {@code null} if none.
     */
    public @Nullable Island getIslandByPlayerId(UUID playerId) {
        UUID cachedIslandId = cache.getIslandIdByPlayer(playerId, () -> loadIslandByPlayerFromDb(playerId));
        if (cachedIslandId != null) {
            Island cachedIsland = cache.getIsland(cachedIslandId, () -> loadIslandFromDb(cachedIslandId));
            if (cachedIsland != null) return cachedIsland;
        }

        return loadIslandByPlayerFromDb(playerId);
    }

    /**
     * Cache-only variant of {@link #getIslandByPlayerId(UUID)} for callers that
     * must never block on a JDBC round-trip (e.g. PlaceholderAPI resolution on
     * the main thread). On a miss, an asynchronous load is scheduled and
     * {@code null} is returned; the value becomes available on a later call.
     */
    public @Nullable Island getIslandByPlayerIdCachedOnly(UUID playerId) {
        UUID cachedIslandId = cache.getIslandIdByPlayer(playerId, () -> loadIslandByPlayerFromDb(playerId));
        if (cachedIslandId != null) {
            Island cachedIsland = cache.getIsland(cachedIslandId, () -> loadIslandFromDb(cachedIslandId));
            if (cachedIsland != null) return cachedIsland;
        }

        cache.refreshAsync(
                new SkyblockCache.RefreshKey(SkyblockCache.DOMAIN_PLAYER_LINK, playerId),
                () -> loadIslandByPlayerFromDb(playerId)
        );
        return null;
    }

    /**
     * Loads the island a player belongs to from the database, populating both
     * the player link and the island caches. Called synchronously on a cold
     * miss, and asynchronously as the stale-while-revalidate reload.
     */
    private @Nullable Island loadIslandByPlayerFromDb(UUID playerId) {
        Island island = plugin.getInterneAPI().getIslandQuery().getIslandDataQuery().getIslandByPlayerId(playerId);
        if (island != null) {
            cache.putIslandIdByPlayer(playerId, island.getId());
            cacheIslandAndIndex(island);
        }
        return island;
    }

    /**
     * Adds a warp to an island.
     *
     * @param islandId       The UUID of the island.
     * @param name           The name of the warp.
     * @param playerLocation The warp location.
     */
    public Boolean addWarpsIsland(UUID islandId, String name, Location playerLocation) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandWarpQuery().updateWarp(islandId, name, playerLocation);
        if (ok) {
            cache.invalidateWarps(islandId);
        }
        return ok;
    }

    /**
     * Deletes a warp from an island.
     *
     * @param islandId The UUID of the island.
     * @param name     The warp name.
     */
    public Boolean delWarpsIsland(UUID islandId, String name) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandWarpQuery().deleteWarp(islandId, name);
        if (ok) {
            cache.invalidateWarps(islandId);
        }
        return ok;
    }

    /**
     * Retrieves a warp by its name from an island.
     *
     * @param islandId The UUID of the island.
     * @param name     The warp name.
     */
    public @Nullable WarpIsland getWarpIslandByName(UUID islandId, String name) {
        WarpIsland cached = cache.getWarp(islandId, name, () -> loadWarpFromDb(islandId, name));
        if (cached != null) return cached;

        return loadWarpFromDb(islandId, name);
    }

    private @Nullable WarpIsland loadWarpFromDb(UUID islandId, String name) {
        WarpIsland warp = plugin.getInterneAPI().getIslandQuery().getIslandWarpQuery().getWarpByName(islandId, name);
        if (warp != null) cache.putWarp(islandId, name, warp);
        return warp;
    }

    /**
     * Retrieves all warps for an island.
     *
     * @param islandId The UUID of the island.
     */
    public @Nullable List<WarpIsland> getWarpsIsland(UUID islandId) {
        List<WarpIsland> cached = cache.getWarps(islandId, () -> loadWarpsFromDb(islandId));
        if (cached != null) return cached;

        return loadWarpsFromDb(islandId);
    }

    private @Nullable List<WarpIsland> loadWarpsFromDb(UUID islandId) {
        List<WarpIsland> warps = plugin.getInterneAPI().getIslandQuery().getIslandWarpQuery().getListWarp(islandId);
        if (warps != null) cache.putWarps(islandId, warps);
        return warps;
    }

    /**
     * Cache-only variant of {@link #getWarpsIsland(UUID)} for callers that must
     * never block on a JDBC round-trip (e.g. PlaceholderAPI handlers on the main
     * thread). On a miss, an asynchronous load is scheduled and an empty list is
     * returned; the data becomes available on a later call.
     */
    public List<WarpIsland> getWarpsIslandCachedOnly(UUID islandId) {
        List<WarpIsland> cached = cache.getWarps(islandId, () -> loadWarpsFromDb(islandId));
        if (cached != null) return cached;

        cache.refreshAsync(
                new SkyblockCache.RefreshKey(SkyblockCache.DOMAIN_WARPS, islandId),
                () -> loadWarpsFromDb(islandId)
        );
        return List.of();
    }

    /**
     * Updates or adds a member on the specified island.
     *
     * @param island  The {@link Island}.
     * @param players The {@link Players} object representing the member.
     */
    public Boolean updateMember(Island island, Players players) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandMemberQuery().updateMember(island, players);
        if (ok) {
            cache.invalidateMembers(island.getId());
            cache.invalidatePlayerLink(players.getMojangId());
            island.invalidateCompiledPermissions();
        }
        return ok;
    }

    /**
     * Retrieves all members on the specified island.
     *
     * @param island The {@link Island}.
     */
    public List<Players> getMembersInIsland(Island island) {
        List<Players> cached = cache.getMembers(island.getId(), () -> loadMembersFromDb(island));
        if (cached != null) return cached;

        List<Players> members = loadMembersFromDb(island);
        return members != null ? members : List.of();
    }

    /**
     * Cache-only variant of {@link #getMembersInIsland(Island)} for callers that
     * must never block on a JDBC round-trip (e.g. PlaceholderAPI handlers on the
     * main thread). On a miss, an asynchronous load is scheduled and an empty
     * list is returned; the data becomes available on a later call.
     */
    public List<Players> getMembersInIslandCachedOnly(Island island) {
        List<Players> cached = cache.getMembers(island.getId(), () -> loadMembersFromDb(island));
        if (cached != null) return cached;

        cache.refreshAsync(
                new SkyblockCache.RefreshKey(SkyblockCache.DOMAIN_MEMBERS, island.getId()),
                () -> loadMembersFromDb(island)
        );
        return List.of();
    }

    private @Nullable List<Players> loadMembersFromDb(Island island) {
        List<Players> members = plugin.getInterneAPI().getIslandQuery().getIslandMemberQuery().getMembersInIsland(island);
        if (members != null) cache.putMembers(island.getId(), members);
        return members;
    }

    public List<Players> getBannedMembersInIsland(Island island) {
        List<Players> cached = cache.getBanned(island.getId(), () -> loadBannedFromDb(island));
        if (cached != null) return cached;

        List<Players> banned = loadBannedFromDb(island);
        return banned != null ? banned : List.of();
    }

    /**
     * Cache-only variant of {@link #getBannedMembersInIsland(Island)}; same
     * contract as {@link #getMembersInIslandCachedOnly(Island)}.
     */
    public List<Players> getBannedMembersInIslandCachedOnly(Island island) {
        List<Players> cached = cache.getBanned(island.getId(), () -> loadBannedFromDb(island));
        if (cached != null) return cached;

        cache.refreshAsync(
                new SkyblockCache.RefreshKey(SkyblockCache.DOMAIN_BANNED, island.getId()),
                () -> loadBannedFromDb(island)
        );
        return List.of();
    }

    private @Nullable List<Players> loadBannedFromDb(Island island) {
        List<Players> banned = plugin.getInterneAPI().getIslandQuery().getIslandMemberQuery().getBannedMembersInIsland(island);
        if (banned != null) cache.putBanned(island.getId(), banned);
        return banned;
    }

    /**
     * Gets a specific member by their UUID from the island.
     *
     * @param island   The {@link Island}.
     * @param playerId The player's UUID.
     */
    public Players getMemberInIsland(Island island, UUID playerId) {
        UUID islandId = island.getId();
        var cachedRole = cache.getRole(islandId, playerId);
        if (cachedRole != null) {
            if (cachedRole == RoleType.VISITOR) return null;
            List<Players> members = cache.getMembers(islandId);
            if (members != null) {
                for (Players p : members) {
                    if (p.getMojangId().equals(playerId)) return p;
                }
            }
            return new Players(playerId, "", islandId, cachedRole);
        }

        List<Players> members = cache.getMembers(islandId);
        if (members != null) {
            for (Players p : members) {
                if (p.getMojangId().equals(playerId)) {
                    cache.putRole(islandId, playerId, p.getRoleType());
                    return p;
                }
            }
            cache.putRole(islandId, playerId, RoleType.VISITOR);
            return null;
        }
        Players fromDb = plugin.getInterneAPI()
                .getIslandQuery()
                .getIslandMemberQuery()
                .getPlayersIsland(island, playerId);
        if (fromDb != null) {
            cache.putRole(islandId, playerId, fromDb.getRoleType());
            return fromDb;
        }
        cache.putRole(islandId, playerId, RoleType.VISITOR);
        return null;
    }

    /**
     * Gets a specific member by name from the island.
     *
     * @param island     The {@link Island}.
     * @param playerName The player's name.
     */
    public @Nullable Players getMemberInIsland(Island island, String playerName) {
        if (playerName == null || playerName.isBlank()) return null;

        UUID islandId = island.getId();
        RoleType cached = cache.getRoleByName(islandId, playerName);
        if (cached != null) {
            if (cached == RoleType.VISITOR) return null;

            List<Players> members = cache.getMembers(islandId);
            if (members != null) {
                for (Players p : members) {
                    if (p.getLastKnowName() != null
                            && p.getLastKnowName().equalsIgnoreCase(playerName)) {
                        return p;
                    }
                }
            }

            return new Players(null, playerName, islandId, cached);
        }

        List<Players> members = cache.getMembers(islandId);
        if (members != null) {
            for (Players p : members) {
                if (p.getLastKnowName() != null && p.getLastKnowName().equalsIgnoreCase(playerName)) {
                    cache.putRole(islandId, p.getMojangId(), p.getRoleType());
                    cache.putRoleByName(islandId, playerName, p.getRoleType());
                    return p;
                }
            }
            cache.putRoleByName(islandId, playerName, RoleType.VISITOR);
            return null;
        }

        Players fromDb = plugin.getInterneAPI()
                .getIslandQuery()
                .getIslandMemberQuery()
                .getPlayersIsland(island, playerName);

        if (fromDb != null) {
            cache.putRole(islandId, fromDb.getMojangId(), fromDb.getRoleType());
            if (fromDb.getLastKnowName() != null) {
                cache.putRoleByName(islandId, fromDb.getLastKnowName().toLowerCase(java.util.Locale.ROOT), fromDb.getRoleType());
            }
            return fromDb;
        }

        cache.putRoleByName(islandId, playerName, RoleType.VISITOR);
        return null;
    }

    /**
     * Adds a record to clear a member on their next login.
     *
     * @param playerId The player's UUID.
     * @param cause    The {@link RemovalCause}.
     * @return A {@link CompletableFuture} with {@code true} if successful, {@code false} otherwise.
     */
    public Boolean addClearMemberNextLogin(UUID playerId, RemovalCause cause) {
        return plugin.getInterneAPI().getIslandQuery().getIslandMemberQuery().addMemberClear(playerId, cause);
    }

    /**
     * Deletes a scheduled member clear record.
     *
     * @param playerId The player's UUID.
     * @param cause    The {@link RemovalCause}.
     * @return A {@link CompletableFuture} with {@code true} if deleted, {@code false} otherwise.
     */
    public Boolean deleteClearMember(UUID playerId, RemovalCause cause) {
        return plugin.getInterneAPI().getIslandQuery().getIslandMemberQuery().deleteMemberClear(playerId, cause);
    }

    /**
     * Checks if a clear member record exists.
     *
     * @param playerId The player's UUID.
     * @param cause    The {@link RemovalCause}.
     * @return A {@link CompletableFuture} with {@code true} if exists, {@code false} otherwise.
     */
    public Boolean checkClearMemberExist(UUID playerId, RemovalCause cause) {
        return plugin.getInterneAPI().getIslandQuery().getIslandMemberQuery().checkClearMemberExist(playerId, cause);
    }

    /**
     * Deletes a member from the island and fires {@link SkyblockRemoveMemberEvent}.
     *
     * @param island    The {@link Island}.
     * @param oldMember The {@link Players} object representing the member to delete.
     * @param cause     The reason the member is being removed.
     * @return {@code true} if deleted, {@code false} otherwise.
     */
    public Boolean deleteMember(Island island, Players oldMember, RemovalCause cause) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandMemberQuery().deleteMember(island, oldMember);
        if (ok) {
            new SkyblockRemoveMemberEvent(island, oldMember, cause).callEvent();
            cache.invalidateMembers(island.getId());
            cache.invalidatePlayerLink(oldMember.getMojangId());
            island.invalidateCompiledPermissions();
        }
        return ok;
    }

    /**
     * Retrieves the owner of the island.
     *
     * @param island The {@link Island}.
     * @return A {@link CompletableFuture} with the {@link Players} object of the owner, or null if none.
     */
    public @Nullable Players getOwnerByIslandID(Island island) {
        return getOwnerByIslandId(island.getId());
    }

    /**
     * Retrieves the maximum number of members allowed in the island.
     *
     * @param island The {@link Island}.
     * @return A {@link CompletableFuture} with the max member count, or -1 if not found.
     */
    public Integer getMaxMemberInIsland(Island island) {
        // Note: previously this path invalidated the state cache after a DB
        // fetch instead of populating it, forcing a fresh query on every call.
        // Routing through the shared snapshot loader fixes that.
        return getStateSnapshot(island).maxMembers();
    }

    /**
     * Sets the maximum number of members allowed in the island.
     *
     * @param island   The {@link Island}.
     * @param newValue The new maximum.
     * @return A {@link CompletableFuture} with {@code true} if successfully updated, {@code false} otherwise.
     */
    public Boolean setMaxMemberInIsland(Island island, int newValue) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandUpdateQuery().setMaxMemberInIsland(island, newValue);
        if (ok) {
            cache.invalidateState(island.getId());
        }
        return ok;
    }

    /**
     * Sets the size of the island.
     *
     * @param island   The {@link Island}.
     * @param newValue The new size (radius).
     * @return A {@link CompletableFuture} with {@code true} if successfully updated, {@code false} otherwise.
     */
    public Boolean setSizeIsland(Island island, double newValue) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandUpdateQuery().setSizeIsland(island, newValue);
        if (!ok) return false;
        cache.invalidateState(island.getId());

        Island reloaded = plugin.getInterneAPI().getIslandQuery().getIslandDataQuery().getIslandByIslandId(island.getId());
        if (reloaded != null) cacheIslandAndIndex(reloaded);
        else invalidateIsland(island.getId());

        return true;
    }

    /**
     * Met à jour l'état "locked" de l'île (vaut true pendant un delete).
     */
    public Boolean setLockedIsland(Island island, boolean locked) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getIslandUpdateQuery().setLockedIsland(island, locked);
        if (ok) {
            cache.invalidateState(island.getId());
        }
        return ok;
    }

    /**
     * Vérifie si l'île est actuellement "locked".
     */
    public Boolean isLockedIsland(Island island) {
        return getStateSnapshot(island).locked();
    }

    public boolean updateCenterLocation(Island island, Location location) {
        return plugin.getInterneAPI()
                .getIslandQuery()
                .getIslandDataQuery()
                .upsertCenterLocation(island.getId(), location);
    }

    public List<Location> getCenterLocations(Island island) {
        return plugin.getInterneAPI()
                .getIslandQuery()
                .getIslandDataQuery()
                .getCenterLocations(island.getId());
    }

    public @Nullable String getIslandName(Island island) {
        String islandName = cache.getIslandName(island.getId(), () -> loadIslandNameFromDb(island));
        if (islandName == null) {
            islandName = loadIslandNameFromDb(island);
        }
        return islandName;
    }

    private @Nullable String loadIslandNameFromDb(Island island) {
        String islandName = SkylliaAPI.getIslandCustomDataQuery().get(
                Keys.NAMESPACE_KEY_EXTRA, island, Keys.KEY_NAME, PersistentDataType.STRING
        );
        if (islandName != null) {
            cache.putIslandName(island.getId(), islandName);
        }
        return islandName;
    }

    public boolean updateIslandName(Island island, @Nullable String name) {
        if (name == null) {
            cache.invalidateIslandName(island.getId());
            return SkylliaAPI.getIslandCustomDataQuery().remove(
                    Keys.NAMESPACE_KEY_EXTRA, island, Keys.KEY_NAME
            );
        }
        cache.putIslandName(island.getId(), name);
        return SkylliaAPI.getIslandCustomDataQuery().set(
                Keys.NAMESPACE_KEY_EXTRA, island, Keys.KEY_NAME, PersistentDataType.STRING, name
        );
    }

    public @Nullable String getIslandDescription(Island island) {
        String description = cache.getIslandDescription(island.getId(), () -> loadIslandDescriptionFromDb(island));
        if (description == null) {
            description = loadIslandDescriptionFromDb(island);
        }
        return description;
    }

    private @Nullable String loadIslandDescriptionFromDb(Island island) {
        String description = SkylliaAPI.getIslandCustomDataQuery().get(
                Keys.NAMESPACE_KEY_EXTRA, island, Keys.KEY_DESCRIPTION, PersistentDataType.STRING
        );
        if (description != null) {
            cache.putIslandDescription(island.getId(), description);
        }
        return description;
    }

    public boolean updateIslandDescription(Island island, @Nullable String description) {
        if (description == null) {
            cache.invalidateIslandDescription(island.getId());
            return SkylliaAPI.getIslandCustomDataQuery().remove(
                    Keys.NAMESPACE_KEY_EXTRA, island, Keys.KEY_DESCRIPTION
            );
        }
        cache.putIslandDescription(island.getId(), description);
        return SkylliaAPI.getIslandCustomDataQuery().set(
                Keys.NAMESPACE_KEY_EXTRA, island, Keys.KEY_DESCRIPTION, PersistentDataType.STRING, description
        );
    }
}
