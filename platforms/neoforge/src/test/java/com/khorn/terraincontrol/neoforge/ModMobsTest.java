package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.configuration.WeightedMobSpawnGroup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Other mods' mobs as the biome configs name them, in 26.3's ids; and the spawn lists read from the biome files alone are those
 * TerrainControl's full loading of the world has.
 */
final class ModMobsTest
{
    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    @Test
    void namesAs26_3Has()
    {
        assertEquals("mocreatures:deer", ModMobs.modMob("mocreatures.deer", 0.8F).toString());
        assertEquals("mocreatures:silverskeleton", ModMobs.modMob("mocreatures:silverskeleton", 0.8F).toString());
        // 26.3's own mobs: in the exported biomes already
        assertNull(ModMobs.modMob("zombie", 0.8F));
        assertNull(ModMobs.modMob("minecraft:zombie", 0.8F));
        assertNull(ModMobs.modMob("minecraft.zombie", 0.8F));
        // 1.12's one scorpion
        assertEquals("mocreatures:dirtscorpion", ModMobs.modMob("mocreatures.scorpion", 0.8F).toString());
        assertEquals("mocreatures:frostscorpion", ModMobs.modMob("mocreatures.scorpion", 0.0F).toString());
    }

    private static String names(final List<WeightedMobSpawnGroup> groups)
    {
        return groups == null ? "" : groups.stream()
                .map(g -> g.getInternalName().toLowerCase() + "/" + g.getWeight() + "/" + g.getMin() + "/" + g.getMax())
                .sorted().collect(Collectors.joining(","));
    }

    @Test
    void spawnsAsTheWorldLoadsThem()
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        for (final String name : List.of("Atlantis", "Orilla", "Keystone"))
        {
            final NeoForgeWorld world = new NeoForgeWorld(name, TestEngine.configs().resolve("worlds").resolve(name).toFile(),
                    VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME));
            final ModMobs.WorldSpawns read = ModMobs.read(TestEngine.configs().toFile(), name);
            assertEquals(world.getConfigs().getWorldConfig().monstersEnabled, read.monsters(), name);
            int checked = 0;
            for (final LocalBiome biome : world.getConfigs().getBiomeArray())
            {
                if (biome == null)
                {
                    continue;
                }
                final BiomeConfig config = biome.getBiomeConfig();
                final ModMobs.BiomeSpawns spawns = ModMobs.spawns(read, NeoForgeBiome.slug(config.getName()));
                assertNotNull(spawns, name + "/" + config.getName());
                final String where = name + "/" + config.getName();
                assertEquals(config.biomeTemperature, spawns.temperature(), where);
                assertEquals(names(config.spawnMonsters), names(spawns.monsters()), where);
                assertEquals(names(config.spawnCreatures), names(spawns.creatures()), where);
                assertEquals(names(config.spawnWaterCreatures), names(spawns.waterCreatures()), where);
                assertEquals(names(config.spawnAmbientCreatures), names(spawns.ambient()), where);
                checked++;
            }
            assertTrue(checked > 0, name);
        }
    }
}
