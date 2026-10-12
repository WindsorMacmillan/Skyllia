package fr.euphyllia.skyllia.utils.nms.v26_3;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.ChunkCoordinate;
import fr.euphyllia.skyllia.api.world.WorldFeedback;
import io.papermc.paper.FeatureHooks;
import io.papermc.paper.world.PaperWorldLoader;
import io.papermc.paper.world.migration.WorldFolderMigration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.generator.CraftWorldInfo;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import static net.minecraft.server.MinecraftServer.getServer;

public class WorldNMS extends fr.euphyllia.skyllia.api.utils.nms.WorldNMS {

    private static final Logger log = LoggerFactory.getLogger(WorldNMS.class);

    @Override
    public WorldFeedback.FeedbackWorld createWorld(WorldCreator creator) {
        return createWorldInternal(creator, null, null);
    }

    @Override
    public WorldFeedback.FeedbackWorld createWorld(WorldCreator creator, WorldConfig worldConfig) {
        if (!worldConfig.hasCustomHeight()) {
            return createWorldInternal(creator, null, null);
        }
        net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> holder =
                WorldHeightUtil.registerCustomDimension(creator.name(), worldConfig);
        return createWorldInternal(creator, worldConfig, holder);
    }

    private WorldFeedback.FeedbackWorld createWorldInternal(WorldCreator creator, WorldConfig worldConfig, net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> customHeightHolder) {
        Preconditions.checkArgument(creator != null, "WorldCreator cannot be null");

        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        DedicatedServer console = craftServer.getServer();
        Preconditions.checkState(console.getAllLevels().iterator().hasNext(), "Cannot create additional worlds on STARTUP");

        String name = creator.name();
        ChunkGenerator chunkGenerator = creator.generator();
        BiomeProvider biomeProvider = creator.biomeProvider();
        File folder = new File(craftServer.getWorldContainer(), name);
        World world = craftServer.getWorld(name);

        // Paper start
        World worldByKey = craftServer.getWorld(creator.key());
        if (world != null || worldByKey != null) {
            if (world != worldByKey) {
                return WorldFeedback.Feedback.WORLD_DUPLICATED.toFeedbackWorld();
            }
        }
        // Paper end

        if ((folder.exists()) && (!folder.isDirectory())) {
            return WorldFeedback.Feedback.WORLD_FOLDER_INVALID.toFeedbackWorld();
        }

        if (chunkGenerator == null) {
            chunkGenerator = craftServer.getGenerator(name);
        }

        if (biomeProvider == null) {
            biomeProvider = craftServer.getBiomeProvider(name);
        }

        ResourceKey<LevelStem> actualDimension = switch (creator.environment()) {
            case NORMAL -> LevelStem.OVERWORLD;
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> throw new IllegalArgumentException("Illegal dimension (" + creator.environment() + ")");
        };

        final ResourceKey<net.minecraft.world.level.Level> dimensionKey = CraftNamespacedKey.toResourceKey(Registries.DIMENSION, creator.key());
        WorldLoader.DataLoadContext context = console.worldLoaderContext;
        RegistryAccess.Frozen registryAccess = context.datapackDimensions();
        net.minecraft.core.Registry<LevelStem> contextLevelStemRegistry = registryAccess.lookupOrThrow(Registries.LEVEL_STEM);
        final LevelStem configuredStem = console.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).getValue(actualDimension);
        if (configuredStem == null) {
            throw new IllegalStateException("Missing configured level stem " + actualDimension);
        }

        try {
            WorldFolderMigration.migrateApiWorld(
                    console.storageSource,
                    console.registryAccess(),
                    name,
                    actualDimension,
                    dimensionKey
            );
        } catch (final IOException ex) {
            throw new RuntimeException("Failed to migrate legacy world " + name, ex);
        }

        PaperWorldLoader.LoadedWorldData loadedWorldData = PaperWorldLoader.loadWorldData(
                console,
                dimensionKey,
                name
        );
        final PrimaryLevelData primaryLevelData = (PrimaryLevelData) console.getWorldData();

        WorldGenSettings worldGenSettings = LevelStorageSource.readExistingSavedData(console.storageSource, dimensionKey, console.registryAccess(), WorldGenSettings.TYPE)
                .result()
                .orElse(null);

        if (worldGenSettings == null) {
            WorldOptions worldOptions = new WorldOptions(creator.seed(), creator.generateStructures(), creator.bonusChest());

            DedicatedServerProperties.WorldDimensionData properties = new DedicatedServerProperties.WorldDimensionData(GsonHelper.parse((creator.generatorSettings().isEmpty()) ? "{}" : creator.generatorSettings()), creator.type().name().toLowerCase(Locale.ROOT));
            WorldDimensions worldDimensions = properties.create(context.datapackWorldRegistries());

            WorldDimensions.Complete complete = worldDimensions.bake(contextLevelStemRegistry);
            if (complete.dimensions().getValue(actualDimension) == null) {
                throw new IllegalStateException("Missing generated level stem " + actualDimension + " for world " + name);
            }

            worldGenSettings = new WorldGenSettings(worldOptions, worldDimensions);
            registryAccess = complete.dimensionsRegistryAccess();
            loadedWorldData.levelOverrides().setHardcore(creator.hardcore());
            loadedWorldData = new PaperWorldLoader.LoadedWorldData(
                    loadedWorldData.bukkitName(),
                    loadedWorldData.uuid(),
                    loadedWorldData.pdc(),
                    loadedWorldData.levelOverrides()
            );
        }
        final WorldGenSettings genSettingsFinal = worldGenSettings;

        contextLevelStemRegistry = registryAccess.lookupOrThrow(Registries.LEVEL_STEM);

        if (console.options.has("forceUpgrade")) {
            net.minecraft.server.Main.forceUpgrade(console.storageSource, DataFixers.getDataFixer(), console.options.has("eraseCache"), () -> true, registryAccess, console.options.has("recreateRegionFiles"));
        }

        long biomeZoomSeed = BiomeManager.obfuscateSeed(genSettingsFinal.options().seed());
        LevelStem customStem = genSettingsFinal.dimensions().get(actualDimension).orElse(null);
        if (customStem == null) {
            customStem = contextLevelStemRegistry.getValue(actualDimension);
        }
        // Custom height: override LevelStem with patched DimensionType
        if (customHeightHolder != null && customStem != null) {
            customStem = new LevelStem(customHeightHolder, customStem.generator());
        }
        if (customStem == null) {
            throw new IllegalStateException("Missing level stem for world " + name + " using key " + actualDimension);
        }

        WorldInfo worldInfo = new CraftWorldInfo(loadedWorldData.bukkitName(), CraftNamespacedKey.fromMinecraft(dimensionKey.identifier()), genSettingsFinal.options().seed(), primaryLevelData.enabledFeatures(), creator.environment(), customStem.type().value(), customStem.generator(), getServer().registryAccess(), loadedWorldData.uuid());
        if (biomeProvider == null && chunkGenerator != null) {
            biomeProvider = chunkGenerator.getDefaultBiomeProvider(worldInfo);
        }

        final SavedDataStorage savedDataStorage = new SavedDataStorage(console.storageSource.getDimensionPath(dimensionKey).resolve(LevelResource.DATA.id()), console.getFixerUpper(), console.registryAccess());
        savedDataStorage.set(WorldGenSettings.TYPE, new WorldGenSettings(genSettingsFinal.options(), genSettingsFinal.dimensions()));
        List<CustomSpawner> list = ImmutableList.of(
                new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(savedDataStorage)
        );

        ServerLevel serverLevel = new ServerLevel(
                console,
                Util.backgroundExecutor(),
                console.storageSource,
                genSettingsFinal,
                dimensionKey,
                customStem,
                primaryLevelData.isDebugWorld(),
                biomeZoomSeed,
                creator.environment() == World.Environment.NORMAL ? list : ImmutableList.of(),
                true,
                actualDimension,
                creator.environment(),
                chunkGenerator,
                biomeProvider,
                savedDataStorage,
                loadedWorldData
        );

        console.addLevel(serverLevel);
        console.initWorld(serverLevel, creator);

        serverLevel.setSpawnSettings(true);

        craftServer.getServer().prepareLevel(serverLevel);

        return WorldFeedback.Feedback.SUCCESS.toFeedbackWorld(serverLevel.getWorld());
    }

    @Override
    public Map<Material, Integer> getCountAllBlocksInChunk(World world, int chunkX, int chunkZ) {
        Map<Material, Integer> blockCounts = new java.util.EnumMap<>(Material.class);
        net.minecraft.server.level.ServerLevel nms = ((CraftWorld) world).getHandle();

        LevelChunk chunk = nms.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            chunk = nms.getChunkSource().getChunk(chunkX, chunkZ, true);
        }
        if (chunk == null) {
            return blockCounts;
        }

        final LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return blockCounts;
        }

        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) continue;

            section.getStates().count((state, count) -> {
                if (state.isAir()) return;
                Material mat = state.getBukkitMaterial();
                blockCounts.merge(mat, count, Integer::sum);
            });
        }

        return blockCounts;
    }

    // 本服务端项目自定义修改：离线（不加载区块）统计区块内方块，用于光荣榜方块类排名。
    // 走服务端自身的存储层：ChunkMap#read 直接读取区域文件原始 NBT（不建 LevelChunk、
    // 不取 ticket、不触发光照/POI/实体），经 datafixer 升级后由 SerializableChunkData#parse
    // 解析出已解码的方块调色板，仅统计方块。可在异步线程批量调用。
    @Override
    public Map<Material, Integer> getCountAllBlocksInChunkFromDisk(World world, int chunkX, int chunkZ) {
        Map<Material, Integer> blockCounts = new java.util.EnumMap<>(Material.class);
        net.minecraft.server.level.ServerLevel nms = ((CraftWorld) world).getHandle();
        try {
            java.util.Optional<CompoundTag> raw = nms.getChunkSource().chunkMap
                    .read(new ChunkPos(chunkX, chunkZ)).join();
            if (raw.isEmpty()) {
                // 区块尚未生成 / 区域文件不存在（ChunkMap#read 两种情况均返回空）
                return blockCounts;
            }
            // ChunkMap#read 返回未升级的磁盘 NBT，须经 datafixer 升级后再解析
            CompoundTag tag = nms.getChunkSource().chunkMap.upgradeChunkTag(raw.get());
            SerializableChunkData data = SerializableChunkData.parse(
                    nms, nms.palettedContainerFactory(), tag);
            if (data == null) {
                return blockCounts;
            }
            for (SerializableChunkData.SectionData sectionData : data.sectionData()) {
                LevelChunkSection section = sectionData.chunkSection();
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                section.getStates().count((state, count) -> {
                    if (state.isAir()) return;
                    blockCounts.merge(state.getBukkitMaterial(), count, Integer::sum);
                });
            }
        } catch (RuntimeException exception) {
            log.warn("离线统计区块失败：{} {} {}", world.getName(), chunkX, chunkZ, exception);
        }
        return blockCounts;
    }

    // 本服务端项目自定义修改：返回维度目录（含 region/poi/entities 子目录）绝对路径
    @Override
    public String getDimensionFolder(World world) {
        try {
            ServerLevel nms = ((CraftWorld) world).getHandle();
            MinecraftServer server = nms.getServer();
            Path path = server.storageSource.getDimensionPath(nms.dimension());
            return path.toAbsolutePath().toString();
        } catch (RuntimeException exception) {
            log.warn("无法解析世界 {} 的维度目录", world.getName(), exception);
            return null;
        }
    }

    // 本服务端项目自定义修改：返回世界文件夹绝对路径（取自服务端 LevelStorageAccess，
    // 不使用可能返回相对路径的 World#getWorldFolder）
    @Override
    public String getWorldFolder(World world) {
        try {
            ServerLevel nms = ((CraftWorld) world).getHandle();
            Path path = nms.getServer().storageSource.getLevelDirectory().path();
            return path.toAbsolutePath().toString();
        } catch (RuntimeException exception) {
            log.warn("无法解析世界 {} 的世界文件夹", world.getName(), exception);
            return null;
        }
    }

    // 本服务端项目自定义修改：返回服务端根目录（世界文件夹的父目录）
    @Override
    public String getServerRoot(World world) {
        try {
            ServerLevel nms = ((CraftWorld) world).getHandle();
            Path path = nms.getServer().storageSource.getLevelDirectory().path()
                    .toAbsolutePath().getParent();
            return path == null ? null : path.toString();
        } catch (RuntimeException exception) {
            log.warn("无法解析服务端根目录", exception);
            return null;
        }
    }

    // 本服务端项目自定义修改：以「整体覆写」方式从备份恢复一个区域。
    // 全程走服务端自身的 RegionFileStorage（保证内存中的区域文件头部与数据一致），
    // 不加载任何区块、不触发方块更新；备份中缺失的区块写入 null 即删除，实现覆盖而非合并。
    @Override
    public boolean restoreChunksFromBackup(World world, String backupDimensionFolder,
                                           int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                           boolean restoreChunk, boolean restorePoi, boolean restoreEntities,
                                           java.util.function.IntConsumer progress) {
        ServerLevel nms = ((CraftWorld) world).getHandle();
        MinecraftServer server = nms.getServer();
        Path backupDimension = Paths.get(backupDimensionFolder);
        RegionStorageInfo chunkInfo = new RegionStorageInfo(
                server.storageSource.getLevelId(), nms.dimension(), "chunk");
        RegionStorageInfo poiInfo = new RegionStorageInfo(
                server.storageSource.getLevelId(), nms.dimension(), "poi");
        RegionStorageInfo entityInfo = new RegionStorageInfo(
                server.storageSource.getLevelId(), nms.dimension(), "entities");

        boolean ok = true;
        if (restoreChunk) {
            ok &= restoreOneCategory(nms, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
                    backupDimension.resolve("region"), minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    chunkInfo, progress, 0, 0);
        }
        if (restorePoi) {
            ok &= restoreOneCategory(nms, MoonriseRegionFileIO.RegionFileType.POI_DATA,
                    backupDimension.resolve("poi"), minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    poiInfo, progress, 0, 0);
        }
        if (restoreEntities) {
            ok &= restoreOneCategory(nms, MoonriseRegionFileIO.RegionFileType.ENTITY_DATA,
                    backupDimension.resolve("entities"), minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    entityInfo, progress, 0, 0);
        }
        try {
            // 排空挂起 I/O 任务并把区域文件强制落盘（MCA 下为真正的 fsync）
            MoonriseRegionFileIO.flush(nms);
            MoonriseRegionFileIO.flushRegionStorages(nms);
        } catch (IOException | RuntimeException exception) {
            log.error("恢复后刷盘失败：{} 区块 {}..{},{}..{}",
                    world.getName(), minChunkX, maxChunkX, minChunkZ, maxChunkZ, exception);
            ok = false;
        }
        return ok;
    }

    // 本服务端项目自定义修改：带区域偏移的覆盖式恢复。
    // 目标区块 t 对应备份区块 t - 偏移×32，从而实现"旧岛屿区域 → 新岛屿区域"的映射。
    @Override
    public boolean restoreChunksFromBackupOffset(World world, String backupDimensionFolder,
                                                 int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                                 int regionOffsetX, int regionOffsetZ,
                                                 boolean restoreChunk, boolean restorePoi, boolean restoreEntities,
                                                 java.util.function.IntConsumer progress) {
        ServerLevel nms = ((CraftWorld) world).getHandle();
        MinecraftServer server = nms.getServer();
        Path backupDimension = Paths.get(backupDimensionFolder);
        RegionStorageInfo chunkInfo = new RegionStorageInfo(
                server.storageSource.getLevelId(), nms.dimension(), "chunk");
        RegionStorageInfo poiInfo = new RegionStorageInfo(
                server.storageSource.getLevelId(), nms.dimension(), "poi");
        RegionStorageInfo entityInfo = new RegionStorageInfo(
                server.storageSource.getLevelId(), nms.dimension(), "entities");

        int chunkOffsetX = regionOffsetX << 5;
        int chunkOffsetZ = regionOffsetZ << 5;
        boolean ok = true;
        if (restoreChunk) {
            ok &= restoreOneCategory(nms, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
                    backupDimension.resolve("region"), minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    chunkInfo, progress, chunkOffsetX, chunkOffsetZ);
        }
        if (restorePoi) {
            ok &= restoreOneCategory(nms, MoonriseRegionFileIO.RegionFileType.POI_DATA,
                    backupDimension.resolve("poi"), minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    poiInfo, progress, chunkOffsetX, chunkOffsetZ);
        }
        if (restoreEntities) {
            ok &= restoreOneCategory(nms, MoonriseRegionFileIO.RegionFileType.ENTITY_DATA,
                    backupDimension.resolve("entities"), minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    entityInfo, progress, chunkOffsetX, chunkOffsetZ);
        }
        try {
            MoonriseRegionFileIO.flush(nms);
            MoonriseRegionFileIO.flushRegionStorages(nms);
        } catch (IOException | RuntimeException exception) {
            log.error("偏移恢复后刷盘失败：{} 区块 {}..{},{}..{}",
                    world.getName(), minChunkX, maxChunkX, minChunkZ, maxChunkZ, exception);
            ok = false;
        }
        return ok;
    }

    // 本服务端项目自定义修改：诊断——报告恢复源/目标路径与备份中可读到的区块数，不做任何修改
    @Override
    public java.util.List<String> checkRestoreSource(World world, String backupDimensionFolder,
                                                     int regionX, int regionZ) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        ServerLevel nms = ((CraftWorld) world).getHandle();
        MinecraftServer server = nms.getServer();
        lines.add("世界: " + world.getName() + "  维度: " + nms.dimension().identifier());
        lines.add("实机维度目录: " + server.storageSource.getDimensionPath(nms.dimension()).toAbsolutePath());
        lines.add("备份维度目录（插件解析）: " + backupDimensionFolder);
        lines.add("区域: r." + regionX + "." + regionZ + ".mca");
        RegionStorageInfo info = new RegionStorageInfo(
                server.storageSource.getLevelId(), nms.dimension(), "chunk");
        Path backupDim = Paths.get(backupDimensionFolder);
        for (String category : new String[]{"region", "poi", "entities"}) {
            Path folder = backupDim.resolve(category);
            Path file = folder.resolve("r." + regionX + "." + regionZ + ".mca");
            boolean folderExists = Files.isDirectory(folder);
            boolean fileExists = Files.isRegularFile(file);
            lines.add("[" + category + "] 目录存在=" + folderExists + "  文件存在=" + fileExists + "  路径=" + file);
            if (!fileExists) {
                lines.add("    → 备份中缺少该区域文件；恢复将【清空】此类别对应范围");
                continue;
            }
            // 尝试以旁路存储读取，统计能读到的区块数（只读，不写）
            int readable = 0;
            try (RegionFileStorage backup = new RegionFileStorage(info, folder, true)) {
                int baseChunkX = regionX << 5;
                int baseChunkZ = regionZ << 5;
                for (int dx = 0; dx < 32; dx++) {
                    for (int dz = 0; dz < 32; dz++) {
                        try {
                            if (backup.read(new ChunkPos(baseChunkX + dx, baseChunkZ + dz)) != null) {
                                readable++;
                            }
                        } catch (IOException ignored) {
                            // 单个区块读取失败按不可读计
                        }
                    }
                }
            } catch (IOException exception) {
                lines.add("    → 打开备份文件失败: " + exception.getMessage());
                continue;
            }
            lines.add("    → 备份中可读取区块数: " + readable + " / 1024");
        }
        return lines;
    }

    // 本服务端项目自定义修改：恢复单个类别（region/poi/entities）的指定区块范围。
    // 通过 MoonriseRegionFileIO.getControllerFor 取得「活库」存储（复用服务端已缓存的
    // RegionFile，保证头部一致）；备份则是独立打开的旁路存储。
    private boolean restoreOneCategory(ServerLevel nms, MoonriseRegionFileIO.RegionFileType type,
                                       Path backupFolder, int minChunkX, int minChunkZ,
                                       int maxChunkX, int maxChunkZ,
                                       RegionStorageInfo info, java.util.function.IntConsumer progress,
                                       int chunkOffsetX, int chunkOffsetZ) {
        RegionFileStorage live = MoonriseRegionFileIO.getControllerFor(nms, type).getCache();
        boolean ok = true;
        // 本服务端项目自定义修改：目标区域 = 备份区域 + 区域偏移（chunkOffset 为 32 的整数倍）
        int regionOffsetX = chunkOffsetX >> 5;
        int regionOffsetZ = chunkOffsetZ >> 5;
        int minRegionX = minChunkX >> 5;
        int maxRegionX = maxChunkX >> 5;
        int minRegionZ = minChunkZ >> 5;
        int maxRegionZ = maxChunkZ >> 5;
        // 本服务端项目自定义修改：诊断——记录每个类别是否有备份文件、实际写入与清空的区块数
        int written = 0;
        int cleared = 0;
        int missingFiles = 0;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                int regionBaseChunkX = regionX << 5;
                int regionBaseChunkZ = regionZ << 5;
                int fromX = Math.max(minChunkX, regionBaseChunkX);
                int fromZ = Math.max(minChunkZ, regionBaseChunkZ);
                int toX = Math.min(maxChunkX, regionBaseChunkX + 31);
                int toZ = Math.min(maxChunkZ, regionBaseChunkZ + 31);
                // 备份中的区域坐标 = 目标区域 - 区域偏移
                int backupRegionX = regionX - regionOffsetX;
                int backupRegionZ = regionZ - regionOffsetZ;
                Path backupFile = backupFolder.resolve("r." + backupRegionX + "." + backupRegionZ + ".mca");
                if (!Files.isRegularFile(backupFile)) {
                    // 本服务端项目自定义修改：备份文件不存在则清空该范围内活库区块，
                    // 绝不打开（写入型打开会创建空文件而污染备份目录）
                    missingFiles++;
                    cleared += (toX - fromX + 1) * (toZ - fromZ + 1);
                    ok &= clearLiveRange(live, fromX, fromZ, toX, toZ, progress);
                    continue;
                }
                try (RegionFileStorage backup = new RegionFileStorage(info, backupFolder, true)) {
                    for (int chunkX = fromX; chunkX <= toX; chunkX++) {
                        for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
                            ChunkPos targetPos = new ChunkPos(chunkX, chunkZ);
                            // 备份中的区块坐标 = 目标区块 - 区块偏移
                            ChunkPos backupPos = new ChunkPos(chunkX - chunkOffsetX, chunkZ - chunkOffsetZ);
                            CompoundTag tag;
                            try {
                                tag = backup.read(backupPos);
                            } catch (IOException exception) {
                                log.warn("读取备份区块失败：{} {},{}", backupFile.getFileName(),
                                        backupPos.x(), backupPos.z(), exception);
                                ok = false;
                                if (progress != null) progress.accept(1);
                                continue;
                            }
                            try {
                                // 备份有则覆写、无则删除（clear）
                                live.write(targetPos, tag);
                                if (tag != null) {
                                    written++;
                                } else {
                                    cleared++;
                                }
                            } catch (IOException exception) {
                                log.warn("覆写区块失败：{} {},{}", nms.dimension().identifier(), chunkX, chunkZ, exception);
                                ok = false;
                            }
                            if (progress != null) progress.accept(1);
                        }
                    }
                } catch (IOException exception) {
                    log.error("打开备份区域文件失败：{}", backupFile, exception);
                    ok = false;
                }
            }
        }
        // 本服务端项目自定义修改：诊断——输出该类别实际写入/清空与缺失的备份文件数
        log.info("[恢复诊断] {} 类别 {}：写入 {} 个区块，清空 {} 个区块，缺失备份文件 {} 个（源目录 {}）",
                nms.dimension().identifier(), type, written, cleared, missingFiles, backupFolder);
        return ok;
    }

    // 本服务端项目自定义修改：清空活库指定区块范围内的区块
    private boolean clearLiveRange(RegionFileStorage live, int fromX, int fromZ,
                                   int toX, int toZ, java.util.function.IntConsumer progress) {
        boolean ok = true;
        for (int chunkX = fromX; chunkX <= toX; chunkX++) {
            for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
                try {
                    live.write(new ChunkPos(chunkX, chunkZ), null);
                } catch (IOException exception) {
                    ok = false;
                }
                if (progress != null) progress.accept(1);
            }
        }
        return ok;
    }

    @Override
    public void resetChunk(World craftWorld, ChunkCoordinate position) {
        final ServerLevel nms = ((CraftWorld) craftWorld).getHandle();
        final int chunkX = position.x();
        final int chunkZ = position.z();

        LevelChunk chunk = nms.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            chunk = nms.getChunkSource().getChunk(chunkX, chunkZ, true);
        }
        if (chunk == null) {
            TickThread.ensureTickThread(nms, chunkX, chunkZ, "Cannot reset chunk asynchronously");
            return;
        }

        boolean hasAnyPlayer = false;
        for (Entity entity : FeatureHooks.getChunkEntities(nms, chunkX, chunkZ)) {
            if (entity instanceof Player) {
                hasAnyPlayer = true;
            } else {
                entity.remove();
            }
        }

        final LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }

        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;
        final BlockState airState = Blocks.AIR.defaultBlockState();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            final LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            final int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            final int baseY = sectionY << 4;

            for (int ly = 0; ly < 16; ly++) {
                final int worldY = baseY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    final int worldZ = baseZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        final int worldX = baseX + lx;

                        final BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) {
                            continue;
                        }
                        final BlockPos blockPos = new BlockPos(worldX, worldY, worldZ);

                        Block block = craftWorld.getBlockAt(worldX, worldY, worldZ);
                        org.bukkit.block.BlockState bukkitState = block.getState(false);
                        if (bukkitState instanceof PersistentDataHolder) {
                            PersistentDataContainer container = ((PersistentDataHolder) bukkitState).getPersistentDataContainer();
                            for (NamespacedKey key : container.getKeys()) {
                                container.remove(key);
                            }
                        }
                        chunk.removeBlockEntity(blockPos);
                        section.setBlockState(lx, ly, lz, airState, false);
                    }
                }
            }

            section.recalcBlockCounts();
        }

        if (hasAnyPlayer) {
            LevelChunk finalChunk = chunk;
            nms.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunkX, chunkZ), false)
                    .forEach(player -> {
                        player.connection.send(
                                new ClientboundLevelChunkWithLightPacket(
                                        finalChunk,
                                        nms.getLightEngine(),
                                        null,
                                        null
                                )
                        );
                    });
        }

        chunk.markUnsaved();
    }

    /**
     * Gets the current location TPS.
     *
     * @param location the location for which to get the TPS
     * @return current location TPS (5s, 15s, 1m, 5m, 15m in Folia-Server), or null if the region doesn't exist
     */
    @Override
    public double @Nullable [] getTPS(Location location) {
        return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getTPSHelper(location);
    }

    /**
     * Gets the current chunk TPS.
     *
     * @param chunk the chunk for which to get the TPS
     * @return current location TPS (5s, 15s, 1m, 5m, 15m in Folia-Server), or null if the region doesn't exist
     */
    @Override
    public double @Nullable [] getTPS(Chunk chunk) {
        return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getTPSHelper(chunk);
    }

    /**
     * Gets the average tick times for a specific location.
     *
     * @param location the location for which to get the average tick times
     * @return an array of average tick times, or null if the region doesn't exist
     */
    @Override
    public double @Nullable [] getAverageTickTimes(Location location) {
        return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getAverageTickTimesHelper(location);
    }

    /**
     * Gets the average tick times for a specific chunk.
     *
     * @param chunk the chunk for which to get the average tick times
     * @return an array of average tick times, or null if the region doesn't exist
     */
    @Override
    public double @Nullable [] getAverageTickTimes(Chunk chunk) {
        return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getAverageTickTimesHelper(chunk);
    }

    @Override
    public @Nullable Long getTickRegionId(Location location) {
        return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getTickRegionIdHelper(location);
    }

    @Override
    public List<Entity> getEntities(World craftWorld, final @Nullable Entity except, final BoundingBox boundingBox, Predicate<? super Entity> filter) {
        final ServerLevel nms = ((CraftWorld) craftWorld).getHandle();
        AABB bb = new AABB(boundingBox.getMinX(), boundingBox.getMinY(), boundingBox.getMinZ(), boundingBox.getMaxX(), boundingBox.getMaxY(), boundingBox.getMaxZ());
        final List<net.minecraft.world.entity.Entity> entityList = new java.util.ArrayList<>();

        net.minecraft.world.entity.Entity exceptNms = except != null ? ((CraftEntity) except).getHandle() : null;
        nms.moonrise$getEntityLookup().getEntities(exceptNms, bb, entityList, Predicates.alwaysTrue());

        List<Entity> bukkitEntityList = new ArrayList<>(entityList.size());

        for (net.minecraft.world.entity.Entity entity : entityList) {
            Entity bukkitEntity = entity.getBukkitEntity();
            if (filter == null || filter.test(bukkitEntity)) {
                bukkitEntityList.add(bukkitEntity);
            }
        }

        return bukkitEntityList;
    }
}

