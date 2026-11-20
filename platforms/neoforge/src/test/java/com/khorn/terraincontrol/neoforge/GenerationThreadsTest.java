package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.generator.ChunkBuffer;
import com.khorn.terraincontrol.generator.biome.OutputType;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TerrainControl's generation on several threads makes the same worlds as on one: on every converted world, the terrain (every
 * block of 1024 high chunks), the biomes and the population of chunks in several places, made one at a time in order and then
 * again on several threads at once in a shuffled order, with other threads looking biomes up all the while.
 *
 * <p>{@code -PgenRecord=<file>} writes what one thread makes to the file, {@code -PgenCompare=<file>} compares with such a file
 * (to compare one build of TerrainControl with another: record before a change, compare after it).</p>
 */
final class GenerationThreadsTest
{
    private static final long SEED = 20261119L;
    private static final int THREADS = 8;
    /** The chunks whose terrain and biomes are compared: 4 by 4 at each of these */
    private static final int[][] SPOTS = {{0, 0}, {37, -81}, {-150, 64}, {300, 220}};
    /** The chunks populated, 3 by 3 from here (the inner 2 by 2 compared, as population writes into the chunks after it) */
    private static final int[][] POPULATED = {{0, 0}, {37, -81}};

    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    private static NeoForgeWorld load(final String name)
    {
        final NeoForgeWorld world = new NeoForgeWorld(name, TestEngine.configs().resolve("worlds").resolve(name).toFile(),
                VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME));
        world.initialize(SEED);
        return world;
    }

    private static long hash(long hash, final long value)
    {
        hash ^= value;
        return hash * 0x100000001B3L;
    }

    /** A chunk of terrain, all of its height */
    private static BlockState[] terrain(final NeoForgeWorld world, final int chunkX, final int chunkZ)
    {
        final int height = world.getHeightCap();
        final BlockState[] blocks = new BlockState[256 * height];
        java.util.Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
        final ChunkCoordinate coordinate = ChunkCoordinate.fromChunkCoords(chunkX, chunkZ);
        world.getTerrainGenerator().generate(new ChunkBuffer()
        {
            @Override
            public ChunkCoordinate getChunkCoordinate()
            {
                return coordinate;
            }

            @Override
            public void setBlock(final int x, final int y, final int z, final LocalMaterialData material)
            {
                if (y >= 0 && y < height)
                {
                    blocks[y * 256 + (z & 15) * 16 + (x & 15)] = ((NeoForgeMaterialData) material).internalBlock();
                }
            }

            @Override
            public LocalMaterialData getBlock(final int x, final int y, final int z)
            {
                return NeoForgeMaterialData.of(y >= 0 && y < height ? blocks[y * 256 + (z & 15) * 16 + (x & 15)] : Blocks.AIR.defaultBlockState());
            }
        });
        return blocks;
    }

    private static long hashBlocks(final BlockState[] blocks)
    {
        long hash = 0xCBF29CE484222325L;
        for (final BlockState state : blocks)
        {
            hash = hash(hash, Block.BLOCK_STATE_REGISTRY.getId(state));
        }
        return hash;
    }

    /** The biomes of a chunk: the generator's (as terrain and TCBiomeSource read them) and the world's, block by block */
    private static long hashBiomes(final NeoForgeWorld world, final int chunkX, final int chunkZ)
    {
        long hash = 0xCBF29CE484222325L;
        for (final int id : world.getBiomeGenerator().getBiomes(null, chunkX << 4, chunkZ << 4, 16, 16, OutputType.DEFAULT_FOR_WORLD))
        {
            hash = hash(hash, id);
        }
        for (final int id : world.getBiomeGenerator().getBiomesUnZoomed(null, chunkX << 2, chunkZ << 2, 4, 4, OutputType.DEFAULT_FOR_WORLD))
        {
            hash = hash(hash, id);
        }
        for (int x = 0; x < 16; x++)
        {
            for (int z = 0; z < 16; z++)
            {
                hash = hash(hash, world.getCalculatedBiome((chunkX << 4) + x, (chunkZ << 4) + z).getIds().getGenerationId());
            }
        }
        return hash;
    }

    /** Terrain made as asked for (by another thread too, in the threaded run), then populated */
    private static final class Level implements PopulationLevel
    {
        final NeoForgeWorld world;
        final Map<Long, BlockState[]> chunks;
        final int height;
        final Map<String, Integer> features = new TreeMap<>();
        int blockEntities;
        int fluidTicks;

        Level(final NeoForgeWorld world, final Map<Long, BlockState[]> chunks)
        {
            this.world = world;
            this.chunks = chunks;
            this.height = world.getHeightCap();
        }

        BlockState[] chunk(final int chunkX, final int chunkZ)
        {
            return this.chunks.computeIfAbsent(ChunkPos.pack(chunkX, chunkZ), key -> terrain(this.world, chunkX, chunkZ));
        }

        @Override
        public int getMinY()
        {
            return 0;
        }

        @Override
        public int getMaxY()
        {
            return this.height - 1;
        }

        @Override
        public BlockState getBlockState(final BlockPos pos)
        {
            return pos.getY() < 0 || pos.getY() >= this.height ? Blocks.AIR.defaultBlockState()
                    : chunk(pos.getX() >> 4, pos.getZ() >> 4)[pos.getY() * 256 + (pos.getZ() & 15) * 16 + (pos.getX() & 15)];
        }

        @Override
        public void setBlock(final BlockPos pos, final BlockState state)
        {
            if (pos.getY() >= 0 && pos.getY() < this.height)
            {
                chunk(pos.getX() >> 4, pos.getZ() >> 4)[pos.getY() * 256 + (pos.getZ() & 15) * 16 + (pos.getX() & 15)] = state;
            }
        }

        @Override
        public void setBlockEntity(final CompoundTag tag)
        {
            this.blockEntities++;
        }

        @Override
        public void scheduleFluidTick(final BlockPos pos, final Fluid fluid)
        {
            this.fluidTicks++;
        }

        @Override
        public int getSurface(final int x, final int z)
        {
            final BlockState[] blocks = chunk(x >> 4, z >> 4);
            for (int y = this.height - 1; y >= 0; y--)
            {
                if (!blocks[y * 256 + (z & 15) * 16 + (x & 15)].isAir())
                {
                    return y + 1;
                }
            }
            return 0;
        }

        @Override
        public boolean placeFeature(final ResourceKey<Feature> feature, final RandomSource random, final BlockPos pos)
        {
            this.features.merge(feature.identifier().getPath() + "@" + pos.toShortString(), 1, Integer::sum);
            return true;
        }
    }

    /** Populates 3 by 3 chunks in order (as 26.3's one worldgen thread would), under the population lock */
    private static String populate(final NeoForgeWorld world, final Level level, final int fromX, final int fromZ)
    {
        for (int x = fromX; x < fromX + 3; x++)
        {
            for (int z = fromZ; z < fromZ + 3; z++)
            {
                world.lock().lock();
                try
                {
                    world.populate(level, new ChunkPos(x, z));
                } finally
                {
                    world.lock().unlock();
                }
            }
        }
        long hash = 0xCBF29CE484222325L;
        for (int x = fromX + 1; x < fromX + 3; x++)
        {
            for (int z = fromZ + 1; z < fromZ + 3; z++)
            {
                hash = hash(hash, hashBlocks(level.chunk(x, z)));
            }
        }
        return Long.toHexString(hash) + " features " + level.features.hashCode() + "/" + level.features.size() + " block entities "
                + level.blockEntities + " fluid ticks " + level.fluidTicks;
    }

    private static List<int[]> terrainChunks()
    {
        final List<int[]> chunks = new ArrayList<>();
        for (final int[] spot : SPOTS)
        {
            for (int x = 0; x < 4; x++)
            {
                for (int z = 0; z < 4; z++)
                {
                    chunks.add(new int[] {spot[0] + x, spot[1] + z});
                }
            }
        }
        return chunks;
    }

    /** One thread, in order: what TerrainControl made before it ran on several */
    private static Map<String, String> oneThread(final String name)
    {
        final NeoForgeWorld world = load(name);
        final Map<String, String> made = new TreeMap<>();
        world.lock().lock();
        try
        {
            for (final int[] chunk : terrainChunks())
            {
                made.put(name + " terrain " + chunk[0] + " " + chunk[1], Long.toHexString(hashBlocks(terrain(world, chunk[0], chunk[1]))));
                made.put(name + " biomes " + chunk[0] + " " + chunk[1], Long.toHexString(hashBiomes(world, chunk[0], chunk[1])));
            }
            for (final int[] spot : POPULATED)
            {
                made.put(name + " population " + spot[0] + " " + spot[1],
                        populate(world, new Level(world, new java.util.HashMap<>()), spot[0], spot[1]));
            }
        } finally
        {
            world.lock().unlock();
        }
        return made;
    }

    /** Several threads, shuffled, with biome lookups going on all the while */
    private static Map<String, String> threads(final String name) throws Exception
    {
        final NeoForgeWorld world = load(name);
        final Map<String, String> made = new ConcurrentHashMap<>();
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        final AtomicBoolean busy = new AtomicBoolean(true);
        try
        {
            final List<Future<?>> noise = new ArrayList<>();
            for (int i = 0; i < 2; i++)
            {
                final Random random = new Random(i);
                noise.add(pool.submit(() -> {
                    while (busy.get())
                    {
                        world.getCalculatedBiome(random.nextInt(20000) - 10000, random.nextInt(20000) - 10000);
                        world.getBiomeGenerator().getBiomes(null, (random.nextInt(2000) - 1000) << 4, (random.nextInt(2000) - 1000) << 4, 16, 16,
                                OutputType.DEFAULT_FOR_WORLD);
                    }
                }));
            }
            final List<Runnable> work = new ArrayList<>();
            for (final int[] chunk : terrainChunks())
            {
                work.add(() -> made.put(name + " terrain " + chunk[0] + " " + chunk[1], Long.toHexString(hashBlocks(terrain(world, chunk[0], chunk[1])))));
                work.add(() -> made.put(name + " biomes " + chunk[0] + " " + chunk[1], Long.toHexString(hashBiomes(world, chunk[0], chunk[1]))));
            }
            // Population on one thread at a time, as 26.3 runs it, its terrain made by the others meanwhile
            for (final int[] spot : POPULATED)
            {
                final Map<Long, BlockState[]> chunks = new ConcurrentHashMap<>();
                for (int x = spot[0] - 2; x < spot[0] + 6; x++)
                {
                    for (int z = spot[1] - 2; z < spot[1] + 6; z++)
                    {
                        final int cx = x;
                        final int cz = z;
                        work.add(() -> chunks.computeIfAbsent(ChunkPos.pack(cx, cz), key -> terrain(world, cx, cz)));
                    }
                }
                work.add(() -> made.put(name + " population " + spot[0] + " " + spot[1], populate(world, new Level(world, chunks), spot[0], spot[1])));
            }
            Collections.shuffle(work, new Random(name.hashCode()));
            final List<Future<?>> futures = new ArrayList<>();
            for (final Runnable task : work)
            {
                futures.add(pool.submit(task));
            }
            for (final Future<?> future : futures)
            {
                future.get();
            }
            busy.set(false);
            for (final Future<?> future : noise)
            {
                future.get();
            }
        } finally
        {
            busy.set(false);
            pool.shutdown();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        }
        return new TreeMap<>(made);
    }

    private static List<String> worlds()
    {
        final String only = System.getProperty("tc.gen.worlds", "");
        final List<String> names = new ArrayList<>();
        for (final File folder : TestEngine.configs().resolve("worlds").toFile().listFiles(File::isDirectory))
        {
            if (new File(folder, "WorldConfig.ini").isFile() && (only.isEmpty() || List.of(only.split(",")).contains(folder.getName())))
            {
                names.add(folder.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private static void write(final Path file, final Map<String, String> made) throws IOException
    {
        final List<String> lines = new ArrayList<>();
        made.forEach((key, value) -> lines.add(key + " = " + value));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static Map<String, String> read(final Path file) throws IOException
    {
        final Map<String, String> made = new TreeMap<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8))
        {
            final int split = line.indexOf(" = ");
            if (split > 0)
            {
                made.put(line.substring(0, split), line.substring(split + 3));
            }
        }
        return made;
    }

    /**
     * The Mesa surface's bands come from the world's seed, not from the first Mesa chunk made: Cemaria's Mesa chunks are the same
     * made first or last, on one thread or on several.
     */
    @Test
    void mesaBandsDoNotDependOnWhichChunkCameFirst() throws Exception
    {
        assumeTrue(TestEngine.configs() != null && TestEngine.configs().resolve("worlds/Cemaria").toFile().isDirectory(), "No Cemaria");
        // Mesa chunks of Cemaria
        // One Cemaria at a time (it is big): the one that finds them makes no terrain, so it is the first to make them too
        NeoForgeWorld finder = load("Cemaria");
        final List<int[]> mesa = new ArrayList<>();
        for (int x = -400; x <= 400 && mesa.size() < 8; x += 3)
        {
            for (int z = -400; z <= 400 && mesa.size() < 8; z += 7)
            {
                if (finder.getCalculatedBiome((x << 4) + 8, (z << 4) + 8).getBiomeConfig().surfaceAndGroundControl
                        instanceof com.khorn.terraincontrol.generator.surface.MesaSurfaceGenerator)
                {
                    mesa.add(new int[] {x, z});
                }
            }
        }
        assumeTrue(mesa.size() >= 2, "No Mesa in Cemaria's first chunks");
        System.out.println("Cemaria Mesa chunks: " + mesa.stream().map(c -> c[0] + "," + c[1]).toList());
        // In order on one thread, then the other way round on another world, then all at once on another
        final Map<String, Long> inOrder = new TreeMap<>();
        for (final int[] c : mesa)
        {
            inOrder.put(c[0] + "," + c[1], hashBlocks(terrain(finder, c[0], c[1])));
        }
        finder = null;
        NeoForgeWorld second = load("Cemaria");
        for (int i = mesa.size() - 1; i >= 0; i--)
        {
            final int[] c = mesa.get(i);
            assertEquals(inOrder.get(c[0] + "," + c[1]), hashBlocks(terrain(second, c[0], c[1])), "made in the other order: " + c[0] + "," + c[1]);
        }
        second = null;
        final NeoForgeWorld third = load("Cemaria");
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try
        {
            final List<Future<Long>> made = new ArrayList<>();
            for (final int[] c : mesa)
            {
                made.add(pool.submit(() -> hashBlocks(terrain(third, c[0], c[1]))));
            }
            for (int i = 0; i < mesa.size(); i++)
            {
                final int[] c = mesa.get(i);
                assertEquals(inOrder.get(c[0] + "," + c[1]), made.get(i).get(), "made on several threads: " + c[0] + "," + c[1]);
            }
        } finally
        {
            pool.shutdown();
        }
    }

    @Test
    void severalThreadsMakeTheSameWorlds() throws Exception
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final String record = System.getProperty("tc.gen.record", "");
        final String compare = System.getProperty("tc.gen.compare", "");
        final Map<String, String> one = new TreeMap<>();
        final Map<String, String> several = new TreeMap<>();
        for (final String name : worlds())
        {
            final long start = System.nanoTime();
            one.putAll(oneThread(name));
            final long between = System.nanoTime();
            if (record.isEmpty())
            {
                several.putAll(threads(name));
            }
            System.out.printf("%s: one thread %d ms, %d threads %d ms%n", name, (between - start) / 1_000_000, THREADS,
                    (System.nanoTime() - between) / 1_000_000);
        }
        if (!record.isEmpty())
        {
            write(Path.of(record), one);
            System.out.println("Recorded " + one.size() + " results to " + record);
            return;
        }
        int differences = 0;
        for (final Map.Entry<String, String> entry : one.entrySet())
        {
            if (!entry.getValue().equals(several.get(entry.getKey())))
            {
                System.out.println("DIFFERS on several threads: " + entry.getKey() + ": " + entry.getValue() + " vs " + several.get(entry.getKey()));
                differences++;
            }
        }
        if (!compare.isEmpty())
        {
            final Map<String, String> recorded = read(Path.of(compare));
            for (final Map.Entry<String, String> entry : recorded.entrySet())
            {
                if (one.containsKey(entry.getKey()) && !entry.getValue().equals(one.get(entry.getKey())))
                {
                    System.out.println("DIFFERS from the recording: " + entry.getKey() + ": " + entry.getValue() + " vs " + one.get(entry.getKey()));
                    differences++;
                }
            }
            assertTrue(recorded.keySet().containsAll(one.keySet()) || !System.getProperty("tc.gen.worlds", "").isEmpty(),
                    "The recording has not every result");
            System.out.println("Compared " + one.size() + " results with " + compare);
        }
        System.out.println("Compared " + one.size() + " results on one thread and " + THREADS + " threads");
        assertEquals(0, differences, differences + " results differ");
    }
}
