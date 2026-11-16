package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.configuration.WorldConfig;
import com.khorn.terraincontrol.neoforge.NeoForgeWorld;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Which of 26.3's structures a TerrainControl world has, as 1.12's TerrainControl decided it.
 *
 * <p>A world has 1.12's structures only (villages, mineshafts, strongholds, temples, swamp huts, igloos, ocean monuments, mansions,
 * nether fortresses), each as its WorldConfig enables them; 26.3's newer ones (trial chambers, ruined portals...) do not come into
 * TerrainControl's worlds. Where one may start is the biome config's say (VillageType, MineshaftType, RareBuildingType), not 26.3's
 * biome tags, which TerrainControl's own biomes are not in; strongholds may start in any biome; ocean monuments, mansions and
 * fortresses follow 26.3's biome tags, as 1.12 followed its vanilla biomes for them.</p>
 */
public final class TCStructures
{
    private static String name(final ResourceKey<Structure> structure)
    {
        return structure.identifier().getNamespace().equals("minecraft") ? structure.identifier().getPath() : "";
    }

    /**
     * Gets whether a world has a structure at all.
     *
     * @param world     The world.
     * @param structure The structure.
     * @return Whether its WorldConfig enables it.
     */
    public static boolean inWorld(final NeoForgeWorld world, final ResourceKey<Structure> structure)
    {
        final WorldConfig config = world.getConfigs().getWorldConfig();
        return switch (name(structure))
        {
            case "village_plains", "village_desert", "village_taiga", "village_savanna" -> config.villagesEnabled;
            case "desert_pyramid", "jungle_pyramid", "swamp_hut", "igloo" -> config.rareBuildingsEnabled;
            case "mineshaft", "mineshaft_mesa" -> config.mineshaftsEnabled;
            case "stronghold" -> config.strongholdsEnabled;
            case "monument" -> config.oceanMonumentsEnabled;
            case "mansion" -> config.mansionsEnabled;
            case "fortress" -> config.netherFortressesEnabled;
            default -> false;
        };
    }

    /**
     * Gets whether a structure may start in a biome of a world.
     *
     * @param world     The world.
     * @param structure The structure.
     * @param biome     The 26.3 biome where it would start.
     * @return Whether it may.
     */
    public static boolean inBiome(final NeoForgeWorld world, final Holder<Structure> structure, final Holder<Biome> biome)
    {
        final String name = structure.unwrapKey().map(TCStructures::name).orElse("");
        final BiomeConfig config = world.getBiomeConfig(biome);
        if (config == null)
        {
            return false;
        }
        return switch (name)
        {
            case "village_plains" -> config.villageType == BiomeConfig.VillageType.wood;
            case "village_desert" -> config.villageType == BiomeConfig.VillageType.sandstone;
            case "village_taiga" -> config.villageType == BiomeConfig.VillageType.taiga;
            case "village_savanna" -> config.villageType == BiomeConfig.VillageType.savanna;
            case "desert_pyramid" -> config.rareBuildingType == BiomeConfig.RareBuildingType.desertPyramid;
            case "jungle_pyramid" -> config.rareBuildingType == BiomeConfig.RareBuildingType.jungleTemple;
            case "swamp_hut" -> config.rareBuildingType == BiomeConfig.RareBuildingType.swampHut;
            case "igloo" -> config.rareBuildingType == BiomeConfig.RareBuildingType.igloo;
            case "mineshaft" -> config.mineshaftType == BiomeConfig.MineshaftType.normal;
            case "mineshaft_mesa" -> config.mineshaftType == BiomeConfig.MineshaftType.mesa;
            case "stronghold" -> true;
            case "monument", "mansion", "fortress" -> structure.value().biomes().contains(biome);
            default -> false;
        };
    }

    private TCStructures()
    {
    }
}
