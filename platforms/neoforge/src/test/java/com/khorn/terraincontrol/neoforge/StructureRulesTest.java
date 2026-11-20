package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
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
        // 26.3's newer overworld structures as the world has its rare buildings (RareBuildingsEnabled true); its Nether ones never
        assertTrue(TCStructures.inWorld(orilla, structure("trial_chambers").unwrapKey().orElseThrow()));
        assertTrue(TCStructures.inWorld(orilla, structure("ruined_portal").unwrapKey().orElseThrow()));
        assertFalse(TCStructures.inWorld(orilla, structure("bastion_remnant").unwrapKey().orElseThrow()));
    }

    private static JsonObject encoded(final Structure structure)
    {
        return Structure.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, REGISTRIES), structure).getOrThrow().getAsJsonObject();
    }

    @Test
    void villagesAsBigAsVillageSize()
    {
        // Tollana: VillageSize 3, villages 6 more pieces deep (12) and 36 blocks further out (116, 26.3's limit with the ground's edge)
        final Holder<Structure> plains = structure("village_plains");
        final Structure sized = TCStructures.sized(load("Tollana"), plains, REGISTRIES);
        final JsonObject json = encoded(sized);
        assertEquals(12, json.get("size").getAsInt());
        final var distance = json.get("max_distance_from_center");
        assertEquals(116, distance.isJsonObject() ? distance.getAsJsonObject().get("horizontal").getAsInt() : distance.getAsInt());
        // Keystone: VillageSize 1
        assertEquals(8, encoded(TCStructures.sized(load("Keystone"), plains, REGISTRIES)).get("size").getAsInt());
        // VillageSize 0, and what is not a village: as 26.3 has them
        assertSame(plains.value(), TCStructures.sized(load("Orilla"), plains, REGISTRIES));
        assertSame(structure("mineshaft").value(), TCStructures.sized(load("Tollana"), structure("mineshaft"), REGISTRIES));
    }

    // ----- The structures newer than 1.12 -----

    private static final String[] NEWER = {"pillager_outpost", "shipwreck", "shipwreck_beached", "ocean_ruin_cold", "ocean_ruin_warm",
            "buried_treasure", "ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp",
            "ruined_portal_mountain", "ruined_portal_ocean", "trail_ruins", "ancient_city", "trial_chambers", "village_snowy"};

    @Test
    void newerStructuresAsTheWorldHasRareBuildings()
    {
        // Atlantis has its rare buildings, the parks and Zeal left them out (and say nothing of the newer ones)
        final NeoForgeWorld atlantis = load("Atlantis");
        final NeoForgeWorld glacier = load("Glacier");
        for (final String name : NEWER)
        {
            final ResourceKey<Structure> key = structure(name).unwrapKey().orElseThrow();
            if (!name.equals("village_snowy"))
            {
                assertTrue(TCStructures.inWorld(atlantis, key), "Atlantis " + name);
                assertFalse(TCStructures.inWorld(glacier, key), "Glacier " + name);
            }
        }
        assertTrue(TCStructures.inWorld(atlantis, structure("abandoned_camp_forest").unwrapKey().orElseThrow()));
        assertFalse(TCStructures.inWorld(glacier, structure("abandoned_camp_forest").unwrapKey().orElseThrow()));
        // 26.3's Nether and End ones never
        for (final String name : new String[] {"bastion_remnant", "nether_fossil", "end_city", "ruined_portal_nether"})
        {
            assertFalse(TCStructures.inWorld(atlantis, structure(name).unwrapKey().orElseThrow()), name);
        }
    }

    @Test
    void everyVariantIsOneOf26_3s()
    {
        for (final com.khorn.terraincontrol.configuration.BiomeConfig.AbandonedCampType camp
                : com.khorn.terraincontrol.configuration.BiomeConfig.AbandonedCampType.values())
        {
            if (camp.ordinal() != 0 && camp != com.khorn.terraincontrol.configuration.BiomeConfig.AbandonedCampType.disabled)
            {
                structure("abandoned_camp_" + TCStructures.snake(camp.name()));
            }
        }
        for (final com.khorn.terraincontrol.configuration.BiomeConfig.RuinedPortalType portal
                : com.khorn.terraincontrol.configuration.BiomeConfig.RuinedPortalType.values())
        {
            if (portal.ordinal() > 1 && portal != com.khorn.terraincontrol.configuration.BiomeConfig.RuinedPortalType.disabled)
            {
                structure("ruined_portal_" + portal.name());
            }
        }
    }

    @Test
    void undergroundStructuresAsHighAboveTheBottomAsIn26_3()
    {
        final NeoForgeWorld atlantis = load("Atlantis");
        final var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, REGISTRIES);
        final String city = Structure.DIRECT_CODEC.encodeStart(ops, TCStructures.sized(atlantis, structure("ancient_city"), REGISTRIES))
                .getOrThrow().getAsJsonObject().get("start_height").toString();
        final String chambers = Structure.DIRECT_CODEC.encodeStart(ops, TCStructures.sized(atlantis, structure("trial_chambers"), REGISTRIES))
                .getOrThrow().getAsJsonObject().get("start_height").toString();
        System.out.println("ancient_city " + city + ", trial_chambers " + chambers);
        // 26.3: -27, and -40 to -20, its bottom at -64
        assertTrue(city.contains("\"above_bottom\":37") && !city.contains("absolute"), city);
        assertTrue(chambers.contains("\"above_bottom\":24") && chambers.contains("\"above_bottom\":44") && !chambers.contains("absolute"), chambers);
    }

    private static boolean allows(final NeoForgeWorld world, final BiomeConfig config, final String structure, final TCStructures.Ground ground)
    {
        for (final LocalBiome biome : world.getConfigs().getBiomeArray())
        {
            if (biome != null && biome.getBiomeConfig() == config)
            {
                return TCStructures.inBiome(world, structure(structure), ((NeoForgeBiome) biome).getSavedBiome(), () -> ground);
            }
        }
        throw new IllegalArgumentException(config.getName());
    }

    @Test
    void theGroundDecidesWhereAutoIs()
    {
        final NeoForgeWorld atlantis = load("Atlantis");
        final BiomeConfig plains = atlantis.getBiomeByName("Plains").getBiomeConfig();
        assertEquals(BiomeConfig.StructureRule.auto, plains.pillagerOutposts);
        final TCStructures.Ground sea = new TCStructures.Ground(64, 40, 63, false);
        final TCStructures.Ground beach = new TCStructures.Ground(65, 65, 63, true);
        final TCStructures.Ground land = new TCStructures.Ground(72, 72, 63, false);
        final TCStructures.Ground mountain = new TCStructures.Ground(140, 140, 63, false);
        final TCStructures.Ground pond = new TCStructures.Ground(64, 60, 63, false);
        assertTrue(allows(atlantis, plains, "shipwreck", sea));
        assertFalse(allows(atlantis, plains, "shipwreck", pond));
        assertFalse(allows(atlantis, plains, "shipwreck", land));
        assertTrue(allows(atlantis, plains, "shipwreck_beached", beach));
        assertTrue(allows(atlantis, plains, "buried_treasure", beach));
        assertFalse(allows(atlantis, plains, "buried_treasure", land));
        assertTrue(allows(atlantis, plains, "ocean_ruin_cold", sea));
        assertFalse(allows(atlantis, plains, "ocean_ruin_warm", sea));
        assertTrue(allows(atlantis, plains, "pillager_outpost", land));
        assertFalse(allows(atlantis, plains, "pillager_outpost", sea));
        assertFalse(allows(atlantis, plains, "pillager_outpost", beach));
        assertTrue(allows(atlantis, plains, "ruined_portal", land));
        assertTrue(allows(atlantis, plains, "ruined_portal_ocean", sea));
        assertTrue(allows(atlantis, plains, "ruined_portal_mountain", mountain));
        assertFalse(allows(atlantis, plains, "ruined_portal", sea));
        assertTrue(allows(atlantis, plains, "ancient_city", mountain));
        assertFalse(allows(atlantis, plains, "ancient_city", land));
        assertTrue(allows(atlantis, plains, "trial_chambers", land));
        assertFalse(allows(atlantis, plains, "trial_chambers", sea));
        // Without the ground, none of them
        assertFalse(allows(atlantis, plains, "pillager_outpost", null));
        // A biome's say over the ground's
        plains.pillagerOutposts = BiomeConfig.StructureRule.disabled;
        assertFalse(allows(atlantis, plains, "pillager_outpost", land));
        plains.shipwreckType = BiomeConfig.ShipwreckType.beached;
        assertTrue(allows(atlantis, plains, "shipwreck_beached", land));
        assertFalse(allows(atlantis, plains, "shipwreck", sea));
    }
}
