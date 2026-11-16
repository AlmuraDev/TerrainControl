package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.neoforge.generator.TCStructures;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Which of 26.3's structures TerrainControl's worlds have, and where, as their configs say: on Almura's converted worlds.
 */
final class StructureRulesTest
{
    private static final HolderLookup.Provider REGISTRIES = VanillaRegistries.createWorldLookup();

    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    /** A world with its custom biomes in the registry, as the exported datapack puts them on a server */
    private static NeoForgeWorld load(final String name)
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final HolderGetter<Biome> vanilla = REGISTRIES.lookupOrThrow(Registries.BIOME);
        final HolderGetter<Biome> biomes = new HolderGetter<>()
        {
            @Override
            public Optional<Holder.Reference<Biome>> get(final ResourceKey<Biome> id)
            {
                return id.identifier().getNamespace().equals("terraincontrol")
                        ? Optional.of(Holder.Reference.createStandAlone(this, id)) : vanilla.get(id);
            }

            @Override
            public Optional<HolderSet.Named<Biome>> get(final TagKey<Biome> id)
            {
                return vanilla.get(id);
            }
        };
        return new NeoForgeWorld(name, TestEngine.configs().resolve("worlds").resolve(name).toFile(), biomes);
    }

    private static Holder<Structure> structure(final String name)
    {
        return REGISTRIES.lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace(name)));
    }

    /** A biome of the world whose config says so, as the 26.3 biome its chunks are saved as */
    private static Holder<Biome> savedBiomeWhere(final NeoForgeWorld world, final java.util.function.Predicate<BiomeConfig> where)
    {
        for (final LocalBiome biome : world.getConfigs().getBiomeArray())
        {
            if (biome != null && where.test(biome.getBiomeConfig()))
            {
                return ((NeoForgeBiome) biome).getSavedBiome();
            }
        }
        return null;
    }

    @Test
    void biomeConfigsChooseTheStructures()
    {
        final NeoForgeWorld cemaria = load("Cemaria");
        final Holder<Biome> woodVillages = savedBiomeWhere(cemaria, config -> config.villageType == BiomeConfig.VillageType.wood);
        final Holder<Biome> noVillages = savedBiomeWhere(cemaria, config -> config.villageType == BiomeConfig.VillageType.disabled);
        assumeTrue(woodVillages != null && noVillages != null);
        System.out.println("Cemaria: wood villages in " + woodVillages.unwrapKey().orElseThrow().identifier()
                + ", none in " + noVillages.unwrapKey().orElseThrow().identifier());
        assertTrue(TCStructures.inBiome(cemaria, structure("village_plains"), woodVillages));
        assertFalse(TCStructures.inBiome(cemaria, structure("village_desert"), woodVillages));
        assertFalse(TCStructures.inBiome(cemaria, structure("village_plains"), noVillages));
        // Strongholds where the biome config says StrongholdsEnabled
        final Holder<Biome> strongholds = savedBiomeWhere(cemaria, config -> config.strongholdsEnabled);
        final Holder<Biome> noStrongholds = savedBiomeWhere(cemaria, config -> !config.strongholdsEnabled);
        if (strongholds != null)
        {
            assertTrue(TCStructures.inBiome(cemaria, structure("stronghold"), strongholds));
        }
        if (noStrongholds != null)
        {
            assertFalse(TCStructures.inBiome(cemaria, structure("stronghold"), noStrongholds));
        }

        final Holder<Biome> mesaMineshafts = savedBiomeWhere(cemaria, config -> config.mineshaftType == BiomeConfig.MineshaftType.mesa);
        if (mesaMineshafts != null)
        {
            assertTrue(TCStructures.inBiome(cemaria, structure("mineshaft_mesa"), mesaMineshafts));
            assertFalse(TCStructures.inBiome(cemaria, structure("mineshaft"), mesaMineshafts));
        }
    }

    private static net.minecraft.world.level.levelgen.structure.placement.StructurePlacement spacedPlacement(final NeoForgeWorld world,
            final String set)
    {
        final Holder<net.minecraft.world.level.levelgen.structure.StructureSet> vanilla = REGISTRIES.lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.withDefaultNamespace(set)));
        return TCStructures.spaced(world, vanilla).value().placement();
    }

    @Test
    void worldConfigsSpaceTheStructures()
    {
        final NeoForgeWorld cemaria = load("Cemaria");
        final var config = cemaria.getConfigs().getWorldConfig();
        final var villages = (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)
                spacedPlacement(cemaria, "villages");
        System.out.println("Cemaria villages every " + villages.spacing() + " chunks, at least " + villages.separation() + " in (VillageDistance "
                + config.villageDistance + ")");
        assertTrue(villages.spacing() == config.villageDistance && villages.separation() == 8);

        final var monuments = (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)
                spacedPlacement(cemaria, "ocean_monuments");
        assertTrue(monuments.spacing() == config.oceanMonumentGridSize
                && monuments.spacing() - monuments.separation() == config.oceanMonumentRandomOffset + 1);

        final var strongholds = (net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement)
                spacedPlacement(cemaria, "strongholds");
        System.out.println("Cemaria strongholds: " + strongholds.count() + " in rings " + strongholds.distance() + " apart");
        assertTrue(strongholds.count() == config.strongholdCount && strongholds.spread() == config.strongholdSpread);

        final var mineshafts = (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)
                spacedPlacement(cemaria, "mineshafts");
        assertTrue(mineshafts.frequency() == (float) (cemaria.getMaxMineshaftRarity() / 100.0));
    }

    @Test
    void worldConfigsChooseWhichStructuresAtAll()
    {
        final NeoForgeWorld orilla = load("Orilla");
        // Orilla: VillagesEnabled true, MineshaftsEnabled false
        assertTrue(TCStructures.inWorld(orilla, structure("village_plains").unwrapKey().orElseThrow()));
        assertFalse(TCStructures.inWorld(orilla, structure("mineshaft").unwrapKey().orElseThrow()));
        // 26.3's newer structures never come into TerrainControl's worlds
        assertFalse(TCStructures.inWorld(orilla, structure("trial_chambers").unwrapKey().orElseThrow()));
        assertFalse(TCStructures.inWorld(orilla, structure("ruined_portal").unwrapKey().orElseThrow()));
    }
}
