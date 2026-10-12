package fr.euphyllia.skyllia.database.postgresql;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.database.DatabaseInitializeQuery;
import fr.euphyllia.skyllia.api.skyblock.IslandData;
import fr.euphyllia.skyllia.api.utils.RegionUtils;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class PostgreSQLDatabaseInitialize extends DatabaseInitializeQuery {

    private static final Logger logger = LogManager.getLogger(PostgreSQLDatabaseInitialize.class);

    private static final String CREATE_ISLANDS_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.islands (
                island_id    UUID PRIMARY KEY,
                disable      BOOLEAN NOT NULL DEFAULT FALSE,
                region_x     INTEGER NOT NULL,
                region_z     INTEGER NOT NULL,
                "private"    BOOLEAN NOT NULL DEFAULT FALSE,
                locked       BOOLEAN NOT NULL DEFAULT FALSE,
                size         DOUBLE PRECISION NOT NULL,
                create_time  TIMESTAMPTZ NOT NULL DEFAULT now(),
                max_members  INTEGER NOT NULL
            );
            """;

    /**
     * Guarantees, at the database level, that at most one active
     * (non-disabled) island may ever hold a given region — a partial unique
     * index, deliberately scoped to {@code disable = FALSE}.
     * <p>
     * Island deletion is a soft-delete (see {@code updateDisable}): the row
     * stays forever with {@code disable = TRUE}, and its former region is
     * legitimately reassigned to a later island. A plain, non-partial unique
     * index on (region_x, region_z) would incorrectly reject that reuse the
     * moment a region is claimed a second time, so the WHERE clause is not
     * optional here.
     */
    private static final String CREATE_ISLANDS_REGION_UNIQUE = """
            CREATE UNIQUE INDEX IF NOT EXISTS islands_region_unique
            ON %s.islands (region_x, region_z)
            WHERE disable = FALSE;
            """;

    private static final String FIND_DUPLICATE_ACTIVE_REGIONS = """
            SELECT region_x, region_z, STRING_AGG(island_id::text, ',') AS island_ids, COUNT(*) AS cnt
            FROM %s.islands
            WHERE disable = FALSE
            GROUP BY region_x, region_z
            HAVING COUNT(*) > 1;
            """;

    private static final String CREATE_ISLANDS_GAMERULE_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.islands_gamerule (
                island_id UUID PRIMARY KEY REFERENCES %s.islands(island_id) ON DELETE CASCADE,
                flags     BIGINT NOT NULL DEFAULT 0
            );
            """;

    private static final String CREATE_ISLANDS_MEMBERS_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.members_in_islands (
                island_id    UUID NOT NULL REFERENCES %s.islands(island_id) ON DELETE CASCADE,
                uuid_player  UUID NOT NULL,
                player_name  VARCHAR(40),
                role         VARCHAR(40),
                joined       TIMESTAMPTZ,
                PRIMARY KEY (island_id, uuid_player)
            );
            """;

    private static final String CREATE_ISLANDS_TRUSTED_PLAYERS_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.islands_trusted_players (
                island_id UUID NOT NULL REFERENCES %s.islands(island_id) ON DELETE CASCADE,
                uuid_player UUID NOT NULL,
                PRIMARY KEY (island_id, uuid_player)
            );
            """;

    private static final String CREATE_ISLANDS_WARP_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.islands_warp (
                id          BIGSERIAL PRIMARY KEY,
                island_id   UUID NOT NULL REFERENCES %s.islands(island_id) ON DELETE CASCADE,
                warp_name   VARCHAR(100),
                world_name  VARCHAR(100),
                x           DOUBLE PRECISION,
                y           DOUBLE PRECISION,
                z           DOUBLE PRECISION,
                pitch       REAL,
                yaw         REAL,
                UNIQUE (island_id, warp_name)
            );
            """;

    private static final String CREATE_SPIRAL_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.spiral (
                id       INTEGER PRIMARY KEY,
                region_x INTEGER NOT NULL,
                region_z INTEGER NOT NULL
            );
            """;

    private static final String CREATE_ISLANDS_PERMISSIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.islands_permissions_v2 (
                island_id UUID NOT NULL REFERENCES %s.islands(island_id) ON DELETE CASCADE,
                role TEXT NOT NULL,
                words BYTEA NOT NULL,
                PRIMARY KEY (island_id, role)
            );
            """;

    private static final String CREATE_ISLANDS_FLAGS_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.islands_flags (
                island_id  UUID         NOT NULL REFERENCES %s.islands(island_id) ON DELETE CASCADE,
                world_name VARCHAR(255) NOT NULL,
                words      BYTEA        NOT NULL,
                PRIMARY KEY (island_id, world_name)
            );
            """;

    private static final String CREATE_PLAYER_CLEAR_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.player_clear (
                uuid_player UUID NOT NULL,
                cause       VARCHAR(50) NOT NULL DEFAULT 'ISLAND_DELETED',
                PRIMARY KEY (uuid_player, cause)
            );
            """;

    private static final String CREATE_ISLANDS_INDEX = """
            CREATE INDEX IF NOT EXISTS region_xz_disabled
            ON %s.islands (region_x, region_z, disable);
            """;

    private static final String CREATE_SPIRAL_INDEX = """
            CREATE INDEX IF NOT EXISTS region_xz
            ON %s.spiral (region_x, region_z);
            """;

    private static final String CREATE_MEMBERS_BY_PLAYER_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_member_by_player
            ON %s.members_in_islands (uuid_player, role, island_id);
            """;

    private static final String CREATE_MEMBER_BY_ISLAND_ROLE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_member_by_island_role
            ON %s.members_in_islands (island_id, role, uuid_player);
            """;

    private static final String INSERT_SPIRAL = """
            INSERT INTO %s.spiral (id, region_x, region_z)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO NOTHING;
            """;

    private static final String CREATE_PERMISSION_REGISTRY_SEQ = """
            CREATE SEQUENCE IF NOT EXISTS %s.permission_registry_idx_seq;
            """;

    private static final String CREATE_PERMISSION_REGISTRY_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.permission_registry (
                node TEXT PRIMARY KEY,                     -- "namespace:key"
                idx  INTEGER NOT NULL UNIQUE DEFAULT nextval('%s.permission_registry_idx_seq'),
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            """;

    private static final String CREATE_ISLAND_CENTER_LOCATIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.island_center_locations (
                island_id  UUID         NOT NULL REFERENCES %s.islands(island_id) ON DELETE CASCADE,
                world_name VARCHAR(255) NOT NULL,
                center_x   DOUBLE PRECISION NOT NULL,
                center_y   DOUBLE PRECISION NOT NULL,
                center_z   DOUBLE PRECISION NOT NULL,
                PRIMARY KEY (island_id, world_name)
            );
            """;

    private static final String CREATE_ISLANDS_BUILD_HEIGHT_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.islands_build_height (
                island_id  UUID         NOT NULL,
                world_name VARCHAR(255) NOT NULL,
                min_height INT          NOT NULL,
                max_height INT          NOT NULL,
                PRIMARY KEY (island_id, world_name),
                CONSTRAINT islands_build_height_FK
                    FOREIGN KEY (island_id) REFERENCES %s.islands (island_id) ON DELETE CASCADE
            );
            """;

    private static final String MIGRATE_BUILD_HEIGHT_ISLAND_ID_TO_UUID = """
            ALTER TABLE %s.islands_build_height
            ALTER COLUMN island_id TYPE UUID
            USING island_id::uuid;
            """;

    private final String schema;
    private final DatabaseLoader databaseLoader;
    private final int regionDistance;
    private final int maxIslands;
    private final int configVersion;

    public PostgreSQLDatabaseInitialize(@NotNull DatabaseLoader databaseLoader) {
        this(databaseLoader, "public");
    }

    public PostgreSQLDatabaseInitialize(@NotNull DatabaseLoader databaseLoader, @NotNull String schema) {
        this.databaseLoader = databaseLoader;
        this.schema = schema;
        this.configVersion = ConfigLoader.database.getConfigVersion();
        this.regionDistance = ConfigLoader.general.getIslandSettings().regionDistance();
        this.maxIslands = ConfigLoader.general.getIslandSettings().maxIslands();
    }

    private static String sanitizeIdent(String ident) {
        return ident.replaceAll("[^a-zA-Z0-9_]", "");
    }

    @Override
    public Boolean init() {
        createSchemaIfNeeded();
        createDatabaseAndTables();
        applyMigrations();
        initializeSpiralTable();
        return true;
    }

    private void createSchemaIfNeeded() {
        final String s = sanitizeIdent(schema);
        if ("public".equalsIgnoreCase(s)) return;

        SQLExecute.update(databaseLoader,
                "CREATE SCHEMA IF NOT EXISTS " + s + ";",
                null
        );
    }

    private void createDatabaseAndTables() {
        final String s = sanitizeIdent(schema);

        exec(CREATE_ISLANDS_TABLE.formatted(s));
        ensureRegionUniqueConstraint(s);

        exec(CREATE_ISLANDS_MEMBERS_TABLE.formatted(s, s));
        exec(CREATE_ISLANDS_TRUSTED_PLAYERS_TABLE.formatted(s, s));
        exec(CREATE_ISLANDS_WARP_TABLE.formatted(s, s));
        exec(CREATE_SPIRAL_TABLE.formatted(s));
        exec(CREATE_ISLANDS_PERMISSIONS_TABLE.formatted(s, s));
        exec(CREATE_ISLANDS_FLAGS_TABLE.formatted(s, s));
        exec(CREATE_PLAYER_CLEAR_TABLE.formatted(s));
        exec(CREATE_ISLANDS_GAMERULE_TABLE.formatted(s, s));

        exec(CREATE_PERMISSION_REGISTRY_SEQ.formatted(s));
        exec(CREATE_PERMISSION_REGISTRY_TABLE.formatted(s, s));

        exec(CREATE_ISLANDS_INDEX.formatted(s));
        exec(CREATE_SPIRAL_INDEX.formatted(s));
        exec(CREATE_MEMBERS_BY_PLAYER_INDEX.formatted(s));
        exec(CREATE_MEMBER_BY_ISLAND_ROLE_INDEX.formatted(s));
        exec(CREATE_ISLAND_CENTER_LOCATIONS_TABLE.formatted(s, s));
        exec(CREATE_ISLANDS_BUILD_HEIGHT_TABLE.formatted(s, s));
    }

    private void applyMigrations() {
        final String s = sanitizeIdent(schema);
        if (configVersion < 5) {
            migrateV4ToV5(s);
        }
        if (configVersion < 6) {
            migrateV5ToV6(s);
        }
    }

    /**
     * Creates {@link #CREATE_ISLANDS_REGION_UNIQUE}, unless pre-existing data
     * already violates it — which could happen on a server that previously
     * allowed island creations to run concurrently (including via the
     * queue-bypass permission, which has always skipped the creation queue
     * entirely). In that case the index creation is skipped and the
     * conflicting islands are logged explicitly, rather than letting it fail
     * with a generic, hard-to-act-on SQL error.
     */
    private void ensureRegionUniqueConstraint(String s) {
        List<String> duplicates = SQLExecute.queryMap(databaseLoader, FIND_DUPLICATE_ACTIVE_REGIONS.formatted(s), null, rs -> {
            List<String> found = new ArrayList<>();
            try {
                while (rs.next()) {
                    found.add("(%d,%d) -> islands [%s]".formatted(
                            rs.getInt("region_x"), rs.getInt("region_z"), rs.getString("island_ids")));
                }
            } catch (Exception e) {
                logger.log(Level.ERROR, "Failed to scan for duplicate active regions", e);
            }
            return found;
        });

        if (duplicates != null && !duplicates.isEmpty()) {
            logger.log(Level.ERROR, "══════════════════════════════════════════════════════");
            logger.log(Level.ERROR, "  Found {} active island(s) sharing a region with another active island:", duplicates.size());
            for (String d : duplicates) {
                logger.log(Level.ERROR, "    {}", d);
            }
            logger.log(Level.ERROR, "  The unique-region safety index was NOT created. Disable (or move) all");
            logger.log(Level.ERROR, "  but one island per listed region, then restart the server to apply it.");
            logger.log(Level.ERROR, "══════════════════════════════════════════════════════");
            return;
        }

        exec(CREATE_ISLANDS_REGION_UNIQUE.formatted(s));
    }

    private void initializeSpiralTable() {
        int distancePerIsland = regionDistance;
        if (distancePerIsland <= 0) {
            logger.log(Level.FATAL,
                    "You must set a value greater than 1 for region distance per island (config/config.toml -> settings.island.region-distance). " +
                            "If you're using an earlier version of the plugin, set the value to 1 to avoid any bugs, otherwise increase the distance.");
            return;
        }

        Runnable spiralTask = () -> {
            List<IslandData> islandDataList = new ArrayList<>();
            for (int i = 1; i < maxIslands; i++) {
                RegionCoordinate position = RegionUtils.computeNewIslandRegionPosition(i);
                islandDataList.add(new IslandData(
                        i,
                        position.x() * distancePerIsland,
                        position.z() * distancePerIsland
                ));
            }

            final String s = sanitizeIdent(schema);
            final String insertSql = INSERT_SPIRAL.formatted(s);

            SQLExecute.work(databaseLoader, connection -> {
                PostgreSQLSpiralBatchInserter batch = new PostgreSQLSpiralBatchInserter(insertSql, islandDataList);
                batch.run(connection);
            });
        };

        Bukkit.getAsyncScheduler().runNow(SkylliaAPI.getPlugin(), scheduledTask -> spiralTask.run());
    }

    private void exec(String sql) {
        SQLExecute.update(databaseLoader, sql, null);
    }

    private void migrateV4ToV5(String s) {
        exec("""
                ALTER TABLE %s.islands_flags
                DROP CONSTRAINT IF EXISTS islands_flags_pkey;
                """.formatted(s));

        exec("""
                ALTER TABLE %s.islands_flags
                ADD COLUMN IF NOT EXISTS world_name VARCHAR(255) NOT NULL DEFAULT '';
                """.formatted(s));

        String firstWorld = SkylliaAPI.getRegisteredWorlds().isEmpty()
                ? ""
                : SkylliaAPI.getRegisteredWorlds().getFirst().getWorldName();

        SQLExecute.update(databaseLoader,
                "UPDATE " + s + ".islands_flags SET world_name = ? WHERE world_name = '';",
                List.of(firstWorld));

        exec("""
                ALTER TABLE %s.islands_flags
                ALTER COLUMN world_name DROP DEFAULT;
                """.formatted(s));

        exec("""
                ALTER TABLE %s.islands_flags
                ADD CONSTRAINT islands_flags_pkey PRIMARY KEY (island_id, world_name);
                """.formatted(s));

        logger.info("Migration V4 -> V5 applied: islands_flags now has per-world support.");
    }

    private void migrateV5ToV6(String s) {
        exec(MIGRATE_BUILD_HEIGHT_ISLAND_ID_TO_UUID.formatted(s));

        logger.info("Migration V5 -> V6 applied: islands_build_height.island_id is now UUID.");
    }
}
