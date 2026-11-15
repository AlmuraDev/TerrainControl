package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.neoforge.NeoForgeWorld;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TerrainControl world's chunks for 26.3. A dimension uses it as its generator:
 * {@code "generator": {"type": "terraincontrol:terraincontrol", "world": "Keystone"}}, where "world" names the folder in
 * config/TerrainControl/worlds.
 *
 * <p>26.3's terrain step is TerrainControl's chunk generation (terrain, surface, caves and canyons, all in one as on 1.12). Its
 * features step is TerrainControl's population (resources, custom objects, trees), in place of 26.3's own features (they would
 * plant the trees and ores of the vanilla biomes the chunks are saved as).</p>
 */
public final class TCChunkGenerator extends ChunkGenerator
{
    public static final MapCodec<TCChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("world").forGetter(generator -> generator.source.worldName()),
            RegistryOps.<Biome, TCChunkGenerator>retrieveGetter(Registries.BIOME)
    ).apply(i, i.stable(TCChunkGenerator::new)));

    /** Chunks generated only to answer height questions (structures, spawning), kept for a while */
    private static final int COLUMN_CACHE_SIZE = 256;

    private final TCBiomeSource source;
    private final Map<Long, BlockState[]> columnChunks = new ConcurrentHashMap<>();

    private TCChunkGenerator(final String worldName, final HolderGetter<Biome> biomes)
    {
        this(new TCBiomeSource(worldName, biomes));
    }

    private TCChunkGenerator(final TCBiomeSource source)
    {
        super(source);
        this.source = source;
    }

    private NeoForgeWorld world()
    {
        return this.source.world();
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec()
    {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(final HolderLookup<StructureSet> structureSets, final RandomState randomState,
            final long legacyLevelSeed)
    {
        // Made when the level is: the first time TerrainControl gets the seed
        world().initialize(legacyLevelSeed);
        return super.createState(structureSets, randomState, legacyLevelSeed);
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(final ChunkAccess chunk, final Blender blender, final RandomState randomState,
            final StructureManager structureManager, final BiomeManager biomeManager, @Nullable final WorldGenRegion carverBiomeRegion,
            final Set<Holder<Biome>> possibleBiomes)
    {
        final NeoForgeWorld world = world();
        world.lock().lock();
        try
        {
            world.getTerrainGenerator().generate(new ChunkAccessBuffer(chunk));
        } finally
        {
            world.lock().unlock();
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void applyBiomeDecoration(final WorldGenLevel level, final ChunkAccess chunk, final StructureManager structureManager)
    {
        // TerrainControl's population, instead of the features of the vanilla biomes the chunks are saved as
        final NeoForgeWorld world = world();
        world.lock().lock();
        try
        {
            world.populate(new RegionPopulationLevel(level, this), chunk.getPos());
        } finally
        {
            world.lock().unlock();
        }
    }

    @Override
    public void spawnOriginalMobs(final WorldGenRegion worldGenRegion)
    {
        // TerrainControl's population mobs come with population
    }

    /**
     * Gets the blocks of the chunk around a column, generating it on its own when it is not at hand.
     *
     * @param x The column's x.
     * @param z The column's z.
     * @param heightAccessor The level's heights.
     * @return The chunk's blocks, by (y - min y) * 256 + z * 16 + x.
     */
    private BlockState[] columnChunk(final int x, final int z, final LevelHeightAccessor heightAccessor)
    {
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        final long key = (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
        BlockState[] blocks = this.columnChunks.get(key);
        if (blocks != null)
        {
            return blocks;
        }
        final ArrayChunkBuffer buffer = new ArrayChunkBuffer(chunkX, chunkZ, heightAccessor.getMinY(), heightAccessor.getHeight());
        final NeoForgeWorld world = world();
        world.lock().lock();
        try
        {
            world.getTerrainGenerator().generate(buffer);
        } finally
        {
            world.lock().unlock();
        }
        blocks = buffer.blocks();
        if (this.columnChunks.size() >= COLUMN_CACHE_SIZE)
        {
            this.columnChunks.clear();
        }
        this.columnChunks.put(key, blocks);
        return blocks;
    }

    @Override
    public int getBaseHeight(final int x, final int z, final Heightmap.Types type, final LevelHeightAccessor heightAccessor,
            final RandomState randomState)
    {
        final BlockState[] blocks = columnChunk(x, z, heightAccessor);
        for (int y = heightAccessor.getMaxY(); y >= heightAccessor.getMinY(); y--)
        {
            if (type.isOpaque().test(blocks[(y - heightAccessor.getMinY()) * 256 + (z & 15) * 16 + (x & 15)]))
            {
                return y + 1;
            }
        }
        return heightAccessor.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(final int x, final int z, final LevelHeightAccessor heightAccessor, final RandomState randomState)
    {
        final BlockState[] blocks = columnChunk(x, z, heightAccessor);
        final BlockState[] column = new BlockState[heightAccessor.getHeight()];
        for (int i = 0; i < column.length; i++)
        {
            final BlockState state = blocks[i * 256 + (z & 15) * 16 + (x & 15)];
            column[i] = state == null ? Blocks.AIR.defaultBlockState() : state;
        }
        return new NoiseColumn(heightAccessor.getMinY(), column);
    }

    @Override
    public int getGenDepth()
    {
        return world().getHeightCap();
    }

    @Override
    public int getSeaLevel()
    {
        return world().getConfigs().getWorldConfig().waterLevelMax;
    }

    @Override
    public int getMinY()
    {
        return 0;
    }

    @Override
    public void addDebugScreenInfo(final List<String> result, final RandomState randomState, final BlockPos feetPos,
            final SamplerContext samplerContext)
    {
        final NeoForgeWorld world = world();
        if (world.isInitialized())
        {
            final LocalBiome biome = world.getCalculatedBiome(feetPos.getX(), feetPos.getZ());
            result.add("TerrainControl " + world.getName() + ": " + biome.getName());
        }
    }
}
