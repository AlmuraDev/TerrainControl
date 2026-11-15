package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.BiomeIds;
import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * One of a TerrainControl world's biomes on 26.3.
 *
 * <p>TerrainControl generates with its own biome configs; the chunks are saved with {@link #getSavedBiome()}, a 26.3 biome. For
 * the biomes with a ReplaceToBiomeName (most of Almura's) that is the 1.12 vanilla biome they name, as 26.3 has it.</p>
 */
public final class NeoForgeBiome implements LocalBiome
{
    private final BiomeConfig config;
    private final BiomeIds ids;
    private final boolean custom;
    private final Holder<Biome> savedBiome;

    NeoForgeBiome(final BiomeConfig config, final BiomeIds ids, final boolean custom, final Holder<Biome> savedBiome)
    {
        this.config = config;
        this.ids = ids;
        this.custom = custom;
        this.savedBiome = savedBiome;
    }

    /**
     * Gets the 26.3 biome the chunks are saved with.
     *
     * @return The biome.
     */
    public Holder<Biome> getSavedBiome()
    {
        return this.savedBiome;
    }

    @Override
    public boolean isCustom()
    {
        return this.custom;
    }

    @Override
    public String getName()
    {
        return this.config.getName();
    }

    @Override
    public BiomeIds getIds()
    {
        return this.ids;
    }

    @Override
    public float getTemperatureAt(final int x, final int y, final int z)
    {
        // As 1.12's Biome.getTemperature: colder above y 64, by 0.05 every 30 blocks (1.12 also added some noise, left out here)
        final float temperature = this.config.biomeTemperature;
        return y > 64 ? temperature - (y - 64) * 0.05F / 30.0F : temperature;
    }

    @Override
    public BiomeConfig getBiomeConfig()
    {
        return this.config;
    }

    @Override
    public String toString()
    {
        return getName() + " " + this.ids;
    }
}
