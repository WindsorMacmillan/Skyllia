package fr.euphyllia.skyllia.database.sqlite;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.database.DatabaseInitializeQuery;
import fr.euphyllia.skyllia.api.utils.RegionUtils;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SQLiteDatabaseInitialize extends DatabaseInitializeQuery {

    private static final Logger logger = LogManager.getLogger(SQLiteDatabaseInitialize.class);

    private static final String CREATE_ISLANDS_TABLE = """
            CREATE TABLE IF NOT EXISTS islands (
                island_id TEXT NOT NULL,
                region_x  INTEGER NOT NULL,
                region_z  INTEGER NOT NULL,
                disable   INTEGER DEFAULT 0,
                private   INTEGER DEFAULT 0,
                locked    INTEGER DEFAULT 0,
                size      REAL NOT NULL,
                create_time TEXT,
                max_members INTEGER NOT NULL,
                PRIMARY KEY (island_id, region_x, region_z),
                UNIQUE(island_id)
            );
            """;

    private static final String CREATE_ISLANDS_GAMERULE_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_gamerule (
                island_id TEXT NOT NULL,
                flags INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (island_id)
            );
            """;

    private static final String CREATE_ISLANDS_MEMBERS_TABLE = """
            CREATE TABLE IF NOT EXISTS members_in_islands (
                island_id   TEXT NOT NULL,
                uuid_player TEXT NOT NULL,
                player_name TEXT DEFAULT NULL,
                role        TEXT DEFAULT NULL,
                joined      TEXT,
                PRIMARY KEY (island_id, uuid_player),
                FOREIGN KEY(island_id) REFERENCES islands(island_id)
            );
            """;

    private static final String CREATE_ISLANDS_TRUSTED_PLAYERS_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_trusted_players (
                island_id TEXT NOT NULL,
                uuid_player TEXT NOT NULL,
                PRIMARY KEY (island_id, uuid_player),
                FOREIGN KEY (island_id) REFERENCES islands(island_id) ON DELETE CASCADE
            );
            """;

    private static final String CREATE_ISLANDS_WARP_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_warp (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                island_id TEXT NOT NULL,
                warp_name TEXT DEFAULT NULL,
                world_name TEXT DEFAULT NULL,
                x REAL DEFAULT NULL,
                y REAL DEFAULT NULL,
                z REAL DEFAULT NULL,
                pitch REAL DEFAULT NULL,
                yaw REAL DEFAULT NULL,
                UNIQUE(island_id, warp_name)
            );
            """;

    private static final String CREATE_SPIRAL_TABLE = """
            CREATE TABLE IF NOT EXISTS spiral (
                id INTEGER NOT NULL,
                region_x INTEGER NOT NULL,
                region_z INTEGER NOT NULL,
                PRIMARY KEY (id)
            );
            """;

    private static final String CREATE_ISLANDS_PERMISSIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_permissions_v2 (
                  island_id TEXT NOT NULL,
                  role TEXT NOT NULL,
                  words BLOB NOT NULL,
                  PRIMARY KEY (island_id, role)
            );
            """;

    private static final String CREATE_ISLANDS_FLAGS_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_flags (
                island_id  TEXT NOT NULL,
                world_name TEXT NOT NULL,
                words      BLOB NOT NULL,
                PRIMARY KEY (island_id, world_name),
                FOREIGN KEY (island_id) REFERENCES islands(island_id) ON DELETE CASCADE
            );
            """;

    private static final String CREATE_PLAYER_CLEAR_TABLE = """
            CREATE TABLE IF NOT EXISTS player_clear (
                uuid_player TEXT NOT NULL,
                cause TEXT NOT NULL DEFAULT 'ISLAND_DELETED',
                PRIMARY KEY (uuid_player, cause)
            );
            """;

    private static final String INSERT_SPIRAL = """
            INSERT OR IGNORE INTO spiral (id, region_x, region_z) VALUES (?, ?, ?);
            """;

    private static final String CREATE_ISLANDS_INDEX = """
            CREATE INDEX IF NOT EXISTS region_xz_disabled
                ON islands (region_x, region_z, disable);
            """;

    /**
     * Guarantees, at the database level, that at most one active
     * (non-disabled) island may ever hold a given region — a partial unique
     * index, deliberately scoped to {@code disable = 0}.
     * <p>
     * Island deletion is a soft-delete (see {@code updateDisable}): the row
     * stays forever with {@code disable = 1}, and its former region is
     * legitimately reassigned to a later island. A plain, non-partial unique
     * index on (region_x, region_z) would incorrectly reject that reuse the
     * moment a region is claimed a second time, so the WHERE clause is not
     * optional here.
     * <p>
     * This closes, at the schema level and regardless of any
     * application-level locking, a gap that nothing else in SQLite's schema
     * covered: without it, two different island_id rows could share the same
     * active (region_x, region_z).
     */
    private static final String CREATE_ISLANDS_REGION_UNIQUE = """
            CREATE UNIQUE INDEX IF NOT EXISTS islands_region_unique
                ON islands (region_x, region_z)
                WHERE disable = 0;
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

    private static final String CREATE_PERMISSION_REGISTRY_TABLE = """
            CREATE TABLE IF NOT EXISTS permission_registry (
                idx INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                node TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
              );
            
            """;

    private static final String CREATE_ISLAND_CENTER_LOCATIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS island_center_locations (
                island_id  TEXT NOT NULL,
                world_name TEXT NOT NULL,
                center_x   REAL NOT NULL,
                center_y   REAL NOT NULL,
                center_z   REAL NOT NULL,
                PRIMARY KEY (island_id, world_name),
                FOREIGN KEY (island_id) REFERENCES islands(island_id) ON DELETE CASCADE
            );
            """;

    private static final String CREATE_ISLANDS_BUILD_HEIGHT_TABLE = """
            CREATE TABLE IF NOT EXISTS islands_build_height (
                island_id  TEXT    NOT NULL,
                world_name TEXT    NOT NULL,
                min_height INTEGER NOT NULL,
                max_height INTEGER NOT NULL,
                PRIMARY KEY (island_id, world_name),
                FOREIGN KEY (island_id) REFERENCES islands (island_id) ON DELETE CASCADE
            );
            """;

    private final DatabaseLoader databaseLoader;

    public SQLiteDatabaseInitialize(DatabaseLoader databaseLoader) {
        this.databaseLoader = databaseLoader;
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
        exec(CREATE_ISLANDS_MEMBERS_TABLE);
        exec(CREATE_ISLANDS_TRUSTED_PLAYERS_TABLE);
        exec(CREATE_ISLANDS_WARP_TABLE);
        exec(CREATE_SPIRAL_TABLE);
        exec(CREATE_ISLANDS_PERMISSIONS_TABLE);
        exec(CREATE_ISLANDS_FLAGS_TABLE);
        exec(CREATE_PLAYER_CLEAR_TABLE);
        exec(CREATE_ISLANDS_GAMERULE_TABLE);
        exec(CREATE_PERMISSION_REGISTRY_TABLE);
        exec(CREATE_ISLAND_CENTER_LOCATIONS_TABLE);
        exec(CREATE_ISLANDS_BUILD_HEIGHT_TABLE);

        exec(CREATE_ISLANDS_INDEX);
        exec(CREATE_SPIRAL_INDEX);
    }

    private void applyMigrations() {
        if (!hasColumn("islands", "locked")) {
            exec("ALTER TABLE islands ADD COLUMN locked INTEGER DEFAULT 0;");
        }
        if (!hasColumn("islands_flags", "world_name")) {
            migrateV4ToV5();
        }
        ensureRegionUniqueConstraint();
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

        exec(CREATE_ISLANDS_REGION_UNIQUE);
    }

    private boolean hasColumn(String table, String column) {
        Boolean exists = SQLExecute.queryMap(
                databaseLoader,
                "PRAGMA table_info(" + table + ");",
                null,
                rs -> {
                    try {
                        while (rs.next()) {
                            if (column.equalsIgnoreCase(rs.getString("name"))) return true;
                        }
                    } catch (SQLException ignored) {
                    }
                    return false;
                }
        );
        return exists != null && exists;
    }

    private void initializeSpiralTable() {
        int distancePerIsland = ConfigLoader.general.getIslandSettings().regionDistance();
        if (distancePerIsland <= 0) {
            logger.log(Level.FATAL,
                    "You must set a value greater than 1 for region distance per island (config/config.toml -> settings.island.region-distance). " +
                            "If you're using an earlier version of the plugin, set the value to 1 to avoid any bugs, otherwise increase the distance.");
            return;
        }

        Runnable spiralTask = () -> {
            List<SQLiteSpiralBatchInserter.IslandData> islandDataList = new ArrayList<>();
            for (int i = 1; i < ConfigLoader.general.getIslandSettings().maxIslands(); i++) {
                RegionCoordinate position = RegionUtils.computeNewIslandRegionPosition(i);
                islandDataList.add(new SQLiteSpiralBatchInserter.IslandData(
                        i,
                        position.x() * distancePerIsland,
                        position.z() * distancePerIsland
                ));
            }

            SQLiteSpiralBatchInserter batchInserter = new SQLiteSpiralBatchInserter(INSERT_SPIRAL, islandDataList);

            SQLExecute.work(databaseLoader, batchInserter::run);
        };

        Bukkit.getAsyncScheduler().runNow(SkylliaAPI.getPlugin(), task -> spiralTask.run());
    }

    private void exec(String sql) {
        SQLExecute.update(databaseLoader, sql, null);
    }

    private void migrateV4ToV5() {
        exec("""
                CREATE TABLE IF NOT EXISTS islands_flags_new (
                    island_id  TEXT NOT NULL,
                    world_name TEXT NOT NULL,
                    words      BLOB NOT NULL,
                    PRIMARY KEY (island_id, world_name),
                    FOREIGN KEY (island_id) REFERENCES islands(island_id) ON DELETE CASCADE
                );
                """);

        String firstWorld = SkylliaAPI.getRegisteredWorlds().isEmpty()
                ? ""
                : SkylliaAPI.getRegisteredWorlds().getFirst().getWorldName();

        SQLExecute.update(databaseLoader,
                "INSERT INTO islands_flags_new (island_id, world_name, words) SELECT island_id, ?, words FROM islands_flags;",
                List.of(firstWorld));

        exec("DROP TABLE islands_flags;");
        exec("ALTER TABLE islands_flags_new RENAME TO islands_flags;");

        logger.info("Migration V4 -> V5 applied: islands_flags now has per-world support.");
    }
}
