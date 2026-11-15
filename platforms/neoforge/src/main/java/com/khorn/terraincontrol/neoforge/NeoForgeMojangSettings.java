package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.configuration.WeightedMobSpawnGroup;
import com.khorn.terraincontrol.configuration.standard.MojangSettings;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultBiome;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/**
 * The defaults of a 1.12 vanilla biome, for the settings its biome config leaves out.
 *
 * <p>1.12 read them from its own biomes; 26.3's biomes no longer have a height or a top block, so this gives the base temperature
 * of the 26.3 biome and TerrainControl's usual defaults for the rest. Almura's configs set all of these, so this only matters for a
 * biome config that is missing settings.</p>
 */
final class NeoForgeMojangSettings implements MojangSettings
{
    private final DefaultBiome biome;
    private final Holder<Biome> handle;

    NeoForgeMojangSettings(final DefaultBiome biome, final Holder<Biome> handle)
    {
        this.biome = biome;
        this.handle = handle;
    }

    @Override
    public float getTemperature()
    {
        return this.handle.value().getBaseTemperature();
    }

    @Override
    public float getWetness()
    {
        return 0.5F;
    }

    @Override
    public float getSurfaceHeight()
    {
        return 0.1F;
    }

    @Override
    public float getSurfaceVolatility()
    {
        return 0.2F;
    }

    @Override
    public LocalMaterialData getSurfaceBlock()
    {
        return NeoForgeMaterialData.ofDefaultMaterial(switch (this.biome)
        {
            case DESERT, DESERT_HILLS, DESERT_MOUNTAINS, BEACH -> DefaultMaterial.SAND;
            case MUSHROOM_ISLAND, MUSHROOM_SHORE -> DefaultMaterial.MYCEL;
            case STONE_BEACH -> DefaultMaterial.STONE;
            default -> DefaultMaterial.GRASS;
        }, 0);
    }

    @Override
    public LocalMaterialData getGroundBlock()
    {
        return NeoForgeMaterialData.ofDefaultMaterial(switch (this.biome)
        {
            case DESERT, DESERT_HILLS, DESERT_MOUNTAINS, BEACH -> DefaultMaterial.SAND;
            case STONE_BEACH -> DefaultMaterial.STONE;
            default -> DefaultMaterial.DIRT;
        }, 0);
    }

    @Override
    public List<WeightedMobSpawnGroup> getMobSpawnGroup(final EntityCategory entityCategory)
    {
        return List.of();
    }
}
