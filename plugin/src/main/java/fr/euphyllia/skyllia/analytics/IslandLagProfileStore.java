package fr.euphyllia.skyllia.analytics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores region-level observations and periodically refits entity/chunk weights.
 */
public final class IslandLagProfileStore {

    private static final Logger LOGGER = LogManager.getLogger(IslandLagProfileStore.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final int FILE_VERSION = 1;
    private static final int MAX_SAMPLES = 4096;
    private static final int MIN_FIT_SAMPLES = 12;
    private static final int MAX_ENTITY_FEATURES = 48;
    private static final long MIN_ENTITY_TOTAL = 3L;
    private static final double ENTITY_SCALE = 10.0D;
    private static final double CHUNK_SCALE = 256.0D;
    private static final double MIN_ENTITY_WEIGHT = 0.01D;
    private static final double MAX_ENTITY_WEIGHT = 100.0D;
    private static final double MIN_CHUNK_WEIGHT = 0.001D;
    private static final double MAX_CHUNK_WEIGHT = 20.0D;

    private final Path file;
    private final Object lock = new Object();
    private @Nullable ProfileState state;

    public IslandLagProfileStore(@NotNull Path file) {
        this.file = file;
    }

    public @NotNull LagWeights recordSamplesAndFit(@NotNull List<RegionLagSample> samples) {
        synchronized (lock) {
            ProfileState current = loadLocked();
            long now = System.currentTimeMillis();
            for (RegionLagSample sample : samples) {
                current.samples.add(StoredSample.from(sample));
            }
            trimSamples(current.samples);
            current.sampleCount = current.samples.size();
            current.updatedAt = now;

            LagWeights fitted = fit(current.samples);
            if (fitted != null) {
                current.weights = StoredWeights.from(fitted, now);
            }

            saveLocked(current);
            return current.weights.toRuntime();
        }
    }

    private ProfileState loadLocked() {
        if (state != null) {
            return state;
        }
        if (!Files.isRegularFile(file)) {
            state = new ProfileState();
            return state;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ProfileState loaded = GSON.fromJson(reader, ProfileState.class);
            state = loaded != null ? loaded : new ProfileState();
            state.normalize();
            return state;
        } catch (IOException | JsonParseException exception) {
            LOGGER.warn("Cannot read island lag profile {}, starting a new profile", file, exception);
            moveCorruptFile();
            state = new ProfileState();
            return state;
        }
    }

    private void saveLocked(@NotNull ProfileState current) {
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(current, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.warn("Cannot save island lag profile {}", file, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private void moveCorruptFile() {
        try {
            Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            LOGGER.warn("Cannot archive corrupt island lag profile {}", file, exception);
        }
    }

    private static void trimSamples(@NotNull List<StoredSample> samples) {
        int overflow = samples.size() - MAX_SAMPLES;
        if (overflow > 0) {
            samples.subList(0, overflow).clear();
        }
    }

    private static @Nullable LagWeights fit(@NotNull List<StoredSample> samples) {
        if (samples.size() < MIN_FIT_SAMPLES) {
            return null;
        }

        Map<String, Long> totals = new HashMap<>();
        for (StoredSample sample : samples) {
            if (sample.entities == null) {
                continue;
            }
            for (Map.Entry<String, Long> entry : sample.entities.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0L) {
                    continue;
                }
                totals.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }

        List<String> entityFeatures = totals.entrySet().stream()
                .filter(entry -> entry.getValue() >= MIN_ENTITY_TOTAL)
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_ENTITY_FEATURES)
                .map(Map.Entry::getKey)
                .toList();
        if (entityFeatures.isEmpty()) {
            return null;
        }

        int featureCount = 2 + entityFeatures.size();
        Map<String, Integer> featureIndex = new LinkedHashMap<>();
        for (int index = 0; index < entityFeatures.size(); index++) {
            featureIndex.put(entityFeatures.get(index), index + 2);
        }

        double[][] normalMatrix = new double[featureCount][featureCount];
        double[] normalVector = new double[featureCount];
        for (StoredSample sample : samples) {
            if (!Double.isFinite(sample.mspt)) {
                continue;
            }
            double[] features = new double[featureCount];
            features[0] = 1.0D;
            features[1] = Math.max(0L, sample.loadedChunks) / CHUNK_SCALE;
            for (Map.Entry<String, Integer> entry : featureIndex.entrySet()) {
                long count = sample.entities == null ? 0L : sample.entities.getOrDefault(entry.getKey(), 0L);
                features[entry.getValue()] = Math.max(0L, count) / ENTITY_SCALE;
            }
            double target = Math.max(0.0D, Math.min(100.0D, sample.mspt));
            for (int row = 0; row < featureCount; row++) {
                normalVector[row] += features[row] * target;
                for (int column = row; column < featureCount; column++) {
                    normalMatrix[row][column] += features[row] * features[column];
                }
            }
        }

        double ridge = Math.max(0.01D, samples.size() * 0.01D);
        for (int index = 1; index < featureCount; index++) {
            normalMatrix[index][index] += ridge;
        }
        for (int row = 0; row < featureCount; row++) {
            for (int column = 0; column < row; column++) {
                normalMatrix[row][column] = normalMatrix[column][row];
            }
        }

        double[] coefficients = solve(normalMatrix, normalVector);
        if (coefficients == null) {
            return null;
        }

        Map<String, Double> rawEntityWeights = new LinkedHashMap<>();
        List<Double> positiveEntityWeights = new ArrayList<>();
        for (int index = 0; index < entityFeatures.size(); index++) {
            double weight = coefficients[index + 2] / ENTITY_SCALE;
            if (!Double.isFinite(weight) || weight <= 0.0D) {
                continue;
            }
            rawEntityWeights.put(entityFeatures.get(index), weight);
            positiveEntityWeights.add(weight);
        }
        if (positiveEntityWeights.size() < 2) {
            return null;
        }

        positiveEntityWeights.sort(Double::compareTo);
        double median = positiveEntityWeights.get(positiveEntityWeights.size() / 2);
        if (!Double.isFinite(median) || median <= 1.0E-9D) {
            return null;
        }
        double scale = 1.0D / median;

        Map<EntityType, Double> entityWeights = new HashMap<>();
        for (Map.Entry<String, Double> entry : rawEntityWeights.entrySet()) {
            EntityType type = parseEntityType(entry.getKey());
            if (type == null) {
                continue;
            }
            entityWeights.put(type, clamp(entry.getValue() * scale,
                    MIN_ENTITY_WEIGHT, MAX_ENTITY_WEIGHT));
        }

        double rawChunkWeight = coefficients[1] / CHUNK_SCALE;
        double chunkWeight = rawChunkWeight > 0.0D
                ? clamp(rawChunkWeight * scale, MIN_CHUNK_WEIGHT, MAX_CHUNK_WEIGHT)
                : LagWeights.DEFAULT_CHUNK_WEIGHT;
        return new LagWeights(entityWeights, chunkWeight, coefficients[0], samples.size());
    }

    private static double @Nullable [] solve(double[][] matrix, double[] vector) {
        int size = vector.length;
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(matrix[pivot][column]) < 1.0E-12D) {
                return null;
            }
            if (pivot != column) {
                double[] row = matrix[pivot];
                matrix[pivot] = matrix[column];
                matrix[column] = row;
                double value = vector[pivot];
                vector[pivot] = vector[column];
                vector[column] = value;
            }

            double diagonal = matrix[column][column];
            for (int index = column; index < size; index++) {
                matrix[column][index] /= diagonal;
            }
            vector[column] /= diagonal;

            for (int row = 0; row < size; row++) {
                if (row == column) {
                    continue;
                }
                double factor = matrix[row][column];
                if (factor == 0.0D) {
                    continue;
                }
                for (int index = column; index < size; index++) {
                    matrix[row][index] -= factor * matrix[column][index];
                }
                vector[row] -= factor * vector[column];
            }
        }
        return vector;
    }

    private static @Nullable EntityType parseEntityType(@NotNull String name) {
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class ProfileState {
        private int version = FILE_VERSION;
        private long updatedAt;
        private int sampleCount;
        private List<StoredSample> samples = new ArrayList<>();
        private StoredWeights weights = new StoredWeights();

        private void normalize() {
            version = FILE_VERSION;
            if (samples == null) {
                samples = new ArrayList<>();
            }
            if (weights == null) {
                weights = new StoredWeights();
            }
            weights.normalize();
            sampleCount = samples.size();
        }
    }

    private static final class StoredSample {
        private String world;
        private String environment;
        private long tickRegionId;
        private double mspt;
        private int players;
        private long loadedChunks;
        private Map<String, Long> entities = new HashMap<>();
        private long timestamp;

        private static StoredSample from(@NotNull RegionLagSample sample) {
            StoredSample stored = new StoredSample();
            stored.world = sample.worldName();
            stored.environment = sample.environment().name();
            stored.tickRegionId = sample.tickRegionId();
            stored.mspt = sample.mspt();
            stored.players = sample.players();
            stored.loadedChunks = sample.loadedChunks();
            for (Map.Entry<EntityType, Long> entry : sample.entityCounts().entrySet()) {
                stored.entities.put(entry.getKey().name(), entry.getValue());
            }
            stored.timestamp = sample.timestamp();
            return stored;
        }
    }

    private static final class StoredWeights {
        private Map<String, Double> entities = new HashMap<>();
        private double chunk = LagWeights.DEFAULT_CHUNK_WEIGHT;
        private double baseline;
        private int fittedSamples;
        private long fittedAt;

        private static StoredWeights from(@NotNull LagWeights weights, long fittedAt) {
            StoredWeights stored = new StoredWeights();
            for (Map.Entry<EntityType, Double> entry : weights.entityWeights().entrySet()) {
                stored.entities.put(entry.getKey().name(), entry.getValue());
            }
            stored.chunk = weights.chunkWeight();
            stored.baseline = weights.baselineMspt();
            stored.fittedSamples = weights.fittedSamples();
            stored.fittedAt = fittedAt;
            return stored;
        }

        private void normalize() {
            if (entities == null) {
                entities = new HashMap<>();
            }
            if (!Double.isFinite(chunk) || chunk <= 0.0D) {
                chunk = LagWeights.DEFAULT_CHUNK_WEIGHT;
            }
            if (!Double.isFinite(baseline)) {
                baseline = 0.0D;
            }
        }

        private LagWeights toRuntime() {
            Map<EntityType, Double> runtimeWeights = new HashMap<>();
            for (Map.Entry<String, Double> entry : entities.entrySet()) {
                EntityType type = parseEntityType(entry.getKey());
                if (type != null && entry.getValue() != null && Double.isFinite(entry.getValue())) {
                    runtimeWeights.put(type, entry.getValue());
                }
            }
            return new LagWeights(runtimeWeights, chunk, baseline, fittedSamples);
        }
    }
}
