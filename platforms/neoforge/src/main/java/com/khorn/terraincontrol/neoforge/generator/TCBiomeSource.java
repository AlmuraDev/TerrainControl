package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.generator.biome.OutputType;
import com.khorn.terraincontrol.neoforge.NeoForgeBiome;
import com.khorn.terraincontrol.neoforge.NeoForgeEngine;
import com.khorn.terraincontrol.neoforge.NeoForgeWorld;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * A TerrainControl world's biomes for 26.3: TerrainControl's biome generator (biome groups, an image map), saved as each TerrainControl
 * biome's 26.3 biome.
 *
 * <p>TerrainControl's biomes are the same at every height, and 26.3 asks for them every 4 blocks, so they are worked out once per
 * chunk column and kept for a while.</p>
 */
public final class TCBiomeSource extends BiomeSource implements BiomeResolver
{
    public static final MapCodec<TCBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("world").forGetter(source -> source.worldName),
            RegistryOps.<Biome, TCBiomeSource>retrieveGetter(Registries.BIOME)
    ).apply(i, i.stable(TCBiomeSource::new)));

    /** Chunk columns kept; when there are more, all are forgotten */
    private static final int CACHE_SIZE = 4096;

    private final String worldName;
    private final HolderGetter<Biome> biomes;
    private final Map<Long, Holder<Biome>[]> columns = new ConcurrentHashMap<>();

    TCBiomeSource(final String worldName, final HolderGetter<Biome> biomes)
    {
        this.worldName = worldName;
        this.biomes = biomes;
    }

    /**
     * Gets the TerrainControl world, loading its configs the first time.
     *
     * @return The world.
     */
    NeoForgeWorld world()
    {
        return NeoForgeEngine.get().loadWorld(this.worldName, this.biomes);
    }

    String worldName()
    {
        return this.worldName;
    }

    HolderGetter<Biome> biomes()
    {
        return this.biomes;
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
        return this;
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
        final NeoForgeWorld world = world();
        final Holder<Biome>[] column = new Holder[16];
        if (!world.isInitialized())
        {
            // Asked before the level is made (no seed yet): its first biome, for now
            java.util.Arrays.fill(column, world.getSavedBiomes().get(0));
            return column;
        }
        final int[] ids;
        world.lock().lock();
        try
        {
            ids = world.getBiomeGenerator().getBiomes(null, chunkX << 4, chunkZ << 4, 16, 16, OutputType.DEFAULT_FOR_WORLD);
        } finally
        {
            world.lock().unlock();
        }
        for (int quartZ = 0; quartZ < 4; quartZ++)
        {
            for (int quartX = 0; quartX < 4; quartX++)
            {
                // The middle of each 4 by 4 square
                final LocalBiome biome = world.getBiomeById(ids[(quartZ * 4 + 2) * 16 + quartX * 4 + 2]);
                column[quartZ << 2 | quartX] = ((NeoForgeBiome) biome).getSavedBiome();
            }
        }
        return column;
    }
}
