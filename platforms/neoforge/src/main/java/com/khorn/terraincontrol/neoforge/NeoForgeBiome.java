package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.BiomeIds;
import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * One of a TerrainControl world's biomes on 26.3.
 *
 * <p>TerrainControl generates with its own biome configs; the chunks are saved with {@link #getSavedBiome()}, a 26.3 biome: a custom
 * biome's own ({@link #registryKey(String, String)}), so the client sees it (its name, colours, rain), or a vanilla biome's for
 * TerrainControl's configs of the vanilla biomes. 1.12's ReplaceToBiomeName (a way round 1.12's 256 biome ids) no longer decides
 * what is saved.</p>
 */
public final class NeoForgeBiome implements LocalBiome
{
    /**
     * Gets the 26.3 biome a TerrainControl world's custom biome is saved as: terraincontrol:&lt;world&gt;/&lt;biome&gt;, both in lower case with
     * '+' as "plus" and anything else but letters, digits, '.', '-' and '_' as '_' ("terraincontrol:orilla/lakeshore2",
     * "terraincontrol:cemaria/bb_caverns_plus_m"). The biomes come from a
     * datapack the biome exporter writes from the configs (26.3's biomes are fixed when the server starts).
     *
     * @param world The world's name.
     * @param biome The biome's name.
     * @return The biome's key.
     */
    public static ResourceKey<Biome> registryKey(final String world, final String biome)
    {
        return ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terraincontrol", slug(world) + "/" + slug(biome)));
    }

    private static String slug(final String name)
    {
        // "+" is spelt out: Almura has both "bb_Caverns M" and "bb_Caverns+ M"
        final String slug = name.toLowerCase(java.util.Locale.ROOT).replace("+", "_plus_")
                .replaceAll("[^a-z0-9._-]+", "_").replaceAll("_{2,}", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "_" : slug;
    }

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
