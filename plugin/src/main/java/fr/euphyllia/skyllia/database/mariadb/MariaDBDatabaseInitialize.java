package fr.euphyllia.skyllia.database.mariadb;

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

public class MariaDBDatabaseInitialize extends DatabaseInitializeQuery {

    private static final Logger logger = LogManager.getLogger(MariaDBDatabaseInitialize.class);

    private static final String CREATE_ISLANDS_TABLE = """
            CREATE TABLE IF NOT EXISTS islands (
                island_id CHAR(36) NOT NULL,
                disable TINYINT DEFAULT 0,
                region_x INT NOT NULL,
                region_z INT NOT NULL,
                private TINYINT DEFAULT 0,
                size DOUBLE NOT NULL,
                create_time TIMESTAMP,
                max_members INT NOT NULL,
                PRIMARY KEY (island_id, region_x, region_z)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_ISLANDS_GAMERULE_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_gamerule (
                island_id CHAR(36) NOT NULL,
                flags INT UNSIGNED NOT NULL DEFAULT 0,
                PRIMARY KEY (island_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_ISLANDS_MEMBERS_TABLE = """
            CREATE TABLE IF NOT EXISTS members_in_islands (
                island_id CHAR(36) NOT NULL,
                uuid_player CHAR(36) NOT NULL,
                player_name VARCHAR(40) DEFAULT NULL,
                role VARCHAR(40) DEFAULT NULL,
                joined TIMESTAMP,
                PRIMARY KEY (island_id, uuid_player),
                CONSTRAINT members_in_islands_FK FOREIGN KEY (island_id) REFERENCES islands (island_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_ISLANDS_TRUSTED_PLAYERS_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_trusted_players (
                island_id CHAR(36) NOT NULL,
                uuid_player CHAR(36) NOT NULL,
                PRIMARY KEY (island_id, uuid_player),
                CONSTRAINT islands_trusted_players_FK FOREIGN KEY (island_id)
                    REFERENCES islands (island_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_ISLANDS_WARP_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_warp (
                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                island_id CHAR(36) NOT NULL,
                warp_name VARCHAR(100) DEFAULT NULL,
                world_name VARCHAR(100) DEFAULT NULL,
                x DOUBLE DEFAULT NULL,
                y DOUBLE DEFAULT NULL,
                z DOUBLE DEFAULT NULL,
                pitch FLOAT DEFAULT NULL,
                yaw FLOAT DEFAULT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY unique_warp_per_island (island_id, warp_name),
                CONSTRAINT islands_warp_FK FOREIGN KEY (island_id) REFERENCES islands (island_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_SPIRAL_TABLE = """
            CREATE TABLE IF NOT EXISTS spiral (
                id INT NOT NULL,
                region_x INT NOT NULL,
                region_z INT NOT NULL,
                PRIMARY KEY (id),
                INDEX idx_region_x (region_x),
                INDEX idx_region_z (region_z)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_ISLANDS_PERMISSIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_permissions_v2 (
                island_id CHAR(36) NOT NULL,
                role VARCHAR(40) NOT NULL,
                words LONGBLOB NOT NULL,
                PRIMARY KEY (`island_id`, `role`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_ISLANDS_FLAGS_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_flags (
                island_id  CHAR(36)     NOT NULL,
                world_name VARCHAR(255) NOT NULL,
                words      LONGBLOB     NOT NULL,
                PRIMARY KEY (island_id, world_name),
                CONSTRAINT islands_flags_FK FOREIGN KEY (island_id) REFERENCES islands (island_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_PLAYER_CLEAR_TABLE = """
            CREATE TABLE IF NOT EXISTS player_clear (
                uuid_player CHAR(36) NOT NULL,
                cause VARCHAR(50) NOT NULL DEFAULT 'ISLAND_DELETED',
                PRIMARY KEY (uuid_player, cause)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String INSERT_SPIRAL = """
            INSERT IGNORE INTO spiral (id, region_x, region_z) VALUES (?, ?, ?);
            """;

    private static final String CREATE_ISLANDS_INDEX = """
            CREATE INDEX IF NOT EXISTS region_xz_disabled
            ON islands (region_x, region_z, disable);
            """;

    /**
     * Guarantees, at the database level, that at most one active
     * (non-disabled) island may ever hold a given region.
     * <p>
     * Unlike PostgreSQL (see {@code PostgreSQLDatabaseInitialize}, which uses
     * a native partial unique index), MariaDB/MySQL has no {@code WHERE}
     * clause on {@code CREATE INDEX}. This emulates the same semantics with
     * the standard workaround: two virtual generated columns that evaluate
     * to NULL for disabled islands and to the real coordinates for active
     * ones, with a unique index over that pair. SQL treats every NULL as
     * distinct for uniqueness purposes, so any number of disabled islands
     * can keep sharing old coordinates — island deletion is a soft-delete
     * (see {@code updateDisable}), and a former region is legitimately
     * reassigned to a later island — while at most one active island may
     * ever hold a given region.
     * <p>
     * This closes, at the schema level and regardless of any
     * application-level locking, a gap that nothing else in MariaDB's schema
     * covered: without it, two different island_id rows could share the same
     * active (region_x, region_z).
     */
    private static final String ADD_REGION_X_ACTIVE_COLUMN = """
            ALTER TABLE islands
                ADD COLUMN IF NOT EXISTS region_x_active INT
                GENERATED ALWAYS AS (CASE WHEN disable = 0 THEN region_x END) VIRTUAL;
            """;

    private static final String ADD_REGION_Z_ACTIVE_COLUMN = """
            ALTER TABLE islands
                ADD COLUMN IF NOT EXISTS region_z_active INT
                GENERATED ALWAYS AS (CASE WHEN disable = 0 THEN region_z END) VIRTUAL;
            """;

    private static final String CREATE_ISLANDS_REGION_UNIQUE = """
            CREATE UNIQUE INDEX IF NOT EXISTS islands_region_unique_active
            ON islands (region_x_active, region_z_active);
            """;

    private static final String FIND_DUPLICATE_ACTIVE_REGIONS = """
            SELECT region_x, region_z, GROUP_CONCAT(island_id) AS island_ids, COUNT(*) AS cnt
            FROM islands
            WHERE disable = 0
            GROUP BY region_x, region_z
            HAVING COUNT(*) > 1;
            """;

    private static final String CREATE_SPIRAL_INDEX = """
            CREATE INDEX IF NOT EXISTS region_xz
            ON spiral (region_x, region_z);
            """;

    private static final String CREATE_MEMBERS_BY_PLAYER_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_member_by_player
            ON members_in_islands (uuid_player, role, island_id);
            """;

    private static final String CREATE_MEMBER_BY_ISLAND_ROLE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_member_by_island_role
            ON members_in_islands (island_id, role, uuid_player);
            """;

    private static final String CREATE_PERMISSION_REGISTRY_TABLE = """
            CREATE TABLE IF NOT EXISTS permission_registry (
              idx INT NOT NULL AUTO_INCREMENT,
              node VARCHAR(255) NOT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (node),
              UNIQUE KEY uq_permission_registry_idx (idx)
            );
            
            """;

    private static final String CREATE_ISLAND_CENTER_LOCATIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS island_center_locations (
                island_id  CHAR(36)     NOT NULL,
                world_name VARCHAR(255) NOT NULL,
                center_x   DOUBLE       NOT NULL,
                center_y   DOUBLE       NOT NULL,
                center_z   DOUBLE       NOT NULL,
                PRIMARY KEY (island_id, world_name),
                CONSTRAINT island_center_locations_FK FOREIGN KEY (island_id) REFERENCES islands (island_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;

    private static final String CREATE_ISLANDS_BUILD_HEIGHT_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_build_height (
                island_id  CHAR(36)     NOT NULL,
                world_name VARCHAR(255) NOT NULL,
                min_height INT          NOT NULL,
                max_height INT          NOT NULL,
                PRIMARY KEY (island_id, world_name),
                CONSTRAINT islands_build_height_FK
                    FOREIGN KEY (island_id) REFERENCES islands (island_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;


    public final int regionDistance;
    public final int maxIslands;
    private final DatabaseLoader databaseLoader;
    private final int configVersion;

    public MariaDBDatabaseInitialize(@NotNull DatabaseLoader databaseLoader) {
        this.databaseLoader = databaseLoader;
        this.configVersion = ConfigLoader.database.getConfigVersion();
        this.regionDistance = ConfigLoader.general.getIslandSettings().regionDistance();
        this.maxIslands = ConfigLoader.general.getIslandSettings().maxIslands();
    }

    @Override
    public Boolean init() {
        createDatabaseAndTables();
        applyMigrations();
        initializeSpiralTable();
        return true;
    }

    private void createDatabaseAndTables() {
        exec(CREATE_ISLANDS_TABLE);
        exec(CREATE_PERMISSION_REGISTRY_TABLE);
        exec(CREATE_ISLANDS_MEMBERS_TABLE);
        exec(CREATE_ISLANDS_TRUSTED_PLAYERS_TABLE);
        exec(CREATE_ISLANDS_WARP_TABLE);
        exec(CREATE_SPIRAL_TABLE);
        exec(CREATE_ISLANDS_PERMISSIONS_TABLE);
        exec(CREATE_ISLANDS_FLAGS_TABLE);
        exec(CREATE_PLAYER_CLEAR_TABLE);
        exec(CREATE_ISLANDS_GAMERULE_TABLE);
        exec(CREATE_ISLANDS_INDEX);
        exec(CREATE_SPIRAL_INDEX);
        exec(CREATE_MEMBERS_BY_PLAYER_INDEX);
        exec(CREATE_MEMBER_BY_ISLAND_ROLE_INDEX);
        exec(CREATE_ISLAND_CENTER_LOCATIONS_TABLE);
        exec(CREATE_ISLANDS_BUILD_HEIGHT_TABLE);
    }

    private void applyMigrations() {
        if (configVersion <= 1) {
            exec("ALTER TABLE islands MODIFY size DOUBLE;");
            exec("""
                    ALTER TABLE islands_gamerule
                    DROP PRIMARY KEY,
                    ADD PRIMARY KEY (island_id) USING BTREE;
                    """);
        }

        exec("ALTER TABLE islands ADD COLUMN IF NOT EXISTS locked TINYINT(1) NOT NULL DEFAULT 0;");

        exec("""
                ALTER TABLE player_clear
                DROP PRIMARY KEY,
                ADD PRIMARY KEY (uuid_player, cause);
                """);

        if (configVersion < 5) {
            migrateV4ToV5();
        }

        ensureRegionUniqueConstraint();
    }

    /**
     * Creates the generated-column based unique index described at
     * {@link #ADD_REGION_X_ACTIVE_COLUMN}, unless pre-existing data already
     * violates it — which could happen on a server that previously allowed
     * island creations to run concurrently (including via the queue-bypass
     * permission, which has always skipped the creation queue entirely). In
     * that case the migration is skipped and the conflicting islands are
     * logged explicitly, rather than letting it fail with a generic,
     * hard-to-act-on SQL error.
     */
    private void ensureRegionUniqueConstraint() {
        List<String> duplicates = SQLExecute.queryMap(databaseLoader, FIND_DUPLICATE_ACTIVE_REGIONS, null, rs -> {
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

        exec(ADD_REGION_X_ACTIVE_COLUMN);
        exec(ADD_REGION_Z_ACTIVE_COLUMN);
        exec(CREATE_ISLANDS_REGION_UNIQUE);
    }

    private void initializeSpiralTable() {
        if (regionDistance <= 0) {
            logger.log(Level.FATAL, "Invalid region distance.");
            return;
        }

        Runnable spiralTask = () -> {
            List<IslandData> islandDataList = new ArrayList<>();
            for (int i = 1; i < maxIslands; i++) {
                RegionCoordinate pos = RegionUtils.computeNewIslandRegionPosition(i);
                islandDataList.add(new IslandData(
                        i,
                        pos.x() * regionDistance,
                        pos.z() * regionDistance
                ));
            }

            SQLExecute.work(databaseLoader, connection ->
                    new SpiralBatchInserter(INSERT_SPIRAL, islandDataList).run(connection)
            );
        };

        Bukkit.getAsyncScheduler().runNow(SkylliaAPI.getPlugin(), t -> spiralTask.run());
    }

    private void exec(String sql) {
        SQLExecute.update(databaseLoader, sql, null);
    }

    private void migrateV4ToV5() {
        exec("""
                ALTER TABLE islands_flags
                DROP PRIMARY KEY;
                """);

        exec("""
                ALTER TABLE islands_flags
                ADD COLUMN IF NOT EXISTS world_name VARCHAR(255) NOT NULL DEFAULT '';
                """);

        String firstWorld = SkylliaAPI.getRegisteredWorlds().isEmpty()
                ? ""
                : SkylliaAPI.getRegisteredWorlds().getFirst().getWorldName();

        SQLExecute.update(databaseLoader,
                "UPDATE islands_flags SET world_name = ? WHERE world_name = '';",
                List.of(firstWorld));

        exec("""
                ALTER TABLE islands_flags
                ALTER COLUMN world_name DROP DEFAULT;
                """);

        exec("""
                ALTER TABLE islands_flags
                ADD PRIMARY KEY (island_id, world_name);
                """);

        logger.info("Migration V4 -> V5 applied: islands_flags now has per-world support.");
    }
}
