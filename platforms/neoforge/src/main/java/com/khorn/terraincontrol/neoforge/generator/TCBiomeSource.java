package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.generator.biome.OutputType;
import com.khorn.terraincontrol.neoforge.NeoForgeBiome;
import com.khorn.terraincontrol.neoforge.NeoForgeEngine;
import com.khorn.terraincontrol.neoforge.NeoForgeWorld;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * A TerrainControl world's biomes for 26.3: TerrainControl's biome generator (biome groups, an image map), saved as each TerrainControl
 * biome's 26.3 biome.
 *
 * <p>Each level using this source has its own TerrainControl world (its own seed), told apart by the level's RandomState: the chunk
 * generator fills chunks from {@link #resolverFor(RandomState)}, and the biome searches here remember the level they search. TerrainControl's biomes are the same at every height, and 26.3 asks for them
 * every 4 blocks, so each level's resolver works them out once per chunk column and keeps them for a while.</p>
 */
public final class TCBiomeSource extends BiomeSource
{
    public static final MapCodec<TCBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("world").forGetter(source -> source.worldName),
            RegistryOps.<Biome, TCBiomeSource>retrieveGetter(Registries.BIOME)
    ).apply(i, i.stable(TCBiomeSource::new)));

    /** Chunk columns kept per level; when there are more, all are forgotten */
    private static final int CACHE_SIZE = 4096;

    private final String worldName;
    private final HolderGetter<Biome> biomes;
    private final Map<RandomState, Resolver> resolvers = Collections.synchronizedMap(new WeakHashMap<>());
    /** The level a biome search here is for, as 26.3 hands only a climate sampler on to {@link #createResolver(Climate.Sampler)} */
    private static final ThreadLocal<RandomState> SEARCHING = new ThreadLocal<>();

    TCBiomeSource(final String worldName, final HolderGetter<Biome> biomes)
    {
        this.worldName = worldName;
        this.biomes = biomes;
    }

    /** The first level's TerrainControl world: what does not depend on a level is read from its configs */
    private volatile NeoForgeWorld firstLevel;

    /**
     * Gets the TerrainControl world's configs, for what does not depend on a level (heights, sea level, its biomes): the first
     * level's, or before there is a level, loaded once (and then handed to the first level, see {@link #createLevelWorld()}), so
     * a level's configs are read once, not twice.
     *
     * @return The world.
     */
    NeoForgeWorld world()
    {
        final NeoForgeWorld level = this.firstLevel;
        return level != null ? level : NeoForgeEngine.get().loadWorld(this.worldName, this.biomes);
    }

    /**
     * Gets a TerrainControl world of its own for a new level: the configs loaded before there was a level, when no level has
     * them yet, or else loaded again.
     *
     * @return The world.
     */
    NeoForgeWorld createLevelWorld()
    {
        final NeoForgeWorld loaded = NeoForgeEngine.get().takeLoaded(this.worldName);
        return loaded != null ? loaded : NeoForgeEngine.get().createWorld(this.worldName, this.biomes);
    }

    /**
     * Gives a level its TerrainControl world, so its biomes come from the level's own seed.
     *
     * @param randomState The level's RandomState.
     * @param world       Its TerrainControl world.
     */
    void bind(final RandomState randomState, final NeoForgeWorld world)
    {
        this.resolvers.put(randomState, new Resolver(world));
        if (this.firstLevel == null)
        {
            this.firstLevel = world;
        }
    }

    /**
     * Runs something for a level, so the biomes it asks this source for come from that level.
     *
     * @param randomState The level's RandomState.
     * @param task        What to run.
     */
    void forLevel(final RandomState randomState, final Runnable task)
    {
        final RandomState before = SEARCHING.get();
        SEARCHING.set(randomState);
        try
        {
            task.run();
        } finally
        {
            SEARCHING.set(before);
        }
    }

    /**
     * Gets a level's biomes.
     *
     * @param randomState The level's RandomState.
     * @return Its biomes.
     */
    BiomeResolver resolverFor(final RandomState randomState)
    {
        final Resolver resolver = randomState == null ? null : this.resolvers.get(randomState);
        if (resolver != null)
        {
            return resolver;
        }
        synchronized (this.resolvers)
        {
            if (!this.resolvers.isEmpty())
            {
                // Asked without the level: the first level's
                return this.resolvers.values().iterator().next();
            }
        }
        // Asked before any level is made (no seed yet): the world's first biome, for now
        final Holder<Biome> first = world().getSavedBiomes().get(0);
        return (quartX, quartY, quartZ) -> first;
    }

    @Override
    public @Nullable Pair<BlockPos, Holder<Biome>> findClosestBiome3d(final BlockPos origin, final int searchRadius,
            final int sampleResolutionHorizontal, final int sampleResolutionVertical, final Predicate<Holder<Biome>> allowed,
            final RandomState randomState, final LevelReader level)
    {
        SEARCHING.set(randomState);
        try
        {
            return super.findClosestBiome3d(origin, searchRadius, sampleResolutionHorizontal, sampleResolutionVertical, allowed,
                    randomState, level);
        } finally
        {
            SEARCHING.remove();
        }
    }

    @Override
    public @Nullable Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(final int originX, final int originY, final int originZ,
            final int searchRadius, final int skipSteps, final Predicate<Holder<Biome>> allowed, final RandomSource random,
            final boolean findClosest, final RandomState randomState)
    {
        SEARCHING.set(randomState);
        try
        {
            return super.findBiomeHorizontal(originX, originY, originZ, searchRadius, skipSteps, allowed, random, findClosest,
                    randomState);
        } finally
        {
            SEARCHING.remove();
        }
    }

    String worldName()
    {
        return this.worldName;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec()
    {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes()
    {
        return world().getSavedBiomes().stream();
    }

    @Override
    public BiomeResolver createResolver(final Climate.Sampler sampler)
    {
        return resolverFor(SEARCHING.get());
    }

    /** One level's biomes */
    private static final class Resolver implements BiomeResolver
    {
        private final NeoForgeWorld world;
        private final Map<Long, Holder<Biome>[]> columns = new ConcurrentHashMap<>();

        Resolver(final NeoForgeWorld world)
        {
            this.world = world;
        }

        @Override
        public Holder<Biome> getNoiseBiome(final int quartX, final int quartY, final int quartZ)
        {
            final int chunkX = quartX >> 2;
            final int chunkZ = quartZ >> 2;
            final long key = (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
            Holder<Biome>[] column = this.columns.get(key);
            if (column == null)
            {
                column = column(chunkX, chunkZ);
                if (this.columns.size() >= CACHE_SIZE)
                {
                    this.columns.clear();
                }
                this.columns.put(key, column);
            }
            return column[(quartZ & 3) << 2 | quartX & 3];
        }

        @SuppressWarnings("unchecked")
        private Holder<Biome>[] column(final int chunkX, final int chunkZ)
        {
            final Holder<Biome>[] column = new Holder[16];
            final int[] ids;
            // This thread's own biome generator (NeoForgeWorld)
            ids = this.world.getBiomeGenerator().getBiomes(null, chunkX << 4, chunkZ << 4, 16, 16, OutputType.DEFAULT_FOR_WORLD);
            for (int quartZ = 0; quartZ < 4; quartZ++)
            {
                for (int quartX = 0; quartX < 4; quartX++)
                {
                    // The middle of each 4 by 4 square
                    final LocalBiome biome = this.world.getBiomeById(ids[(quartZ * 4 + 2) * 16 + quartX * 4 + 2]);
                    column[quartZ << 2 | quartX] = ((NeoForgeBiome) biome).getSavedBiome();
                }
            }
            return column;
        }
    }
}
