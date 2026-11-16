package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The biome exporter: Orilla's custom biomes as 26.3 biomes that 26.3's own biome codec reads, and (with -PbiomesTo=&lt;datapack&gt;)
 * every world's into a datapack.
 */
final class BiomeExportTest
{
    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    private static NeoForgeWorld load(final HolderLookup.Provider registries, final String name)
    {
        return new NeoForgeWorld(name, TestEngine.configs().resolve("worlds").resolve(name).toFile(),
                registries.lookupOrThrow(Registries.BIOME));
    }

    /** Reads every biome a datapack holds with 26.3's biome codec, as the server will */
    private static int decodeAll(final HolderLookup.Provider registries, final Path datapack) throws Exception
    {
        final RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        int decoded = 0;
        try (Stream<Path> files = Files.walk(datapack.resolve("data/terraincontrol/worldgen/biome")))
        {
            for (final Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator)
            {
                final var result = Biome.DIRECT_CODEC.parse(ops, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
                assertTrue(result.isSuccess(), file + ": " + result.error().map(Object::toString).orElse(""));
                decoded++;
            }
        }
        return decoded;
    }

    @Test
    void orillasBiomesAre26_3Biomes() throws Exception
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final HolderLookup.Provider registries = VanillaRegistries.createWorldLookup();
        final Path datapack = Files.createTempDirectory("tc-biomes");
        final BiomeExporter exporter = new BiomeExporter();
        exporter.export(load(registries, "Orilla"), datapack);
        System.out.println("Orilla: " + exporter.biomes + " biomes; mobs left out " + exporter.skippedMobs);
        assertTrue(exporter.biomes > 0);
        assertEquals(exporter.biomes, decodeAll(registries, datapack));
        assertTrue(Files.exists(datapack.resolve("data/terraincontrol/worldgen/biome/orilla/erodedcoast.json")));

        // What Orilla's map is made of
        final NeoForgeWorld world = load(registries, "Orilla");
        world.initialize(1L);
        final java.util.Map<String, Integer> seen = new java.util.TreeMap<>();
        for (int x = -220; x < 220; x += 4)
        {
            for (int z = -220; z < 190; z += 4)
            {
                final com.khorn.terraincontrol.LocalBiome biome = world.getCalculatedBiome(x, z);
                seen.merge(biome.getName() + (biome.isCustom() ? " (custom)" : ""), 1, Integer::sum);
            }
        }
        System.out.println("Orilla's map: " + seen);
    }

    /** With its biomes in the registry (as the datapack puts them on a server), Orilla names them for other mods */
    @Test
    void orillasBiomesHaveTheirNames()
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final net.minecraft.core.HolderGetter<Biome> vanilla = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
        // The vanilla biomes, and any terraincontrol biome, as the exported datapack would give them
        final net.minecraft.core.HolderGetter<Biome> biomes = new net.minecraft.core.HolderGetter<>()
        {
            @Override
            public java.util.Optional<net.minecraft.core.Holder.Reference<Biome>> get(final net.minecraft.resources.ResourceKey<Biome> id)
            {
                return id.identifier().getNamespace().equals("terraincontrol")
                        ? java.util.Optional.of(net.minecraft.core.Holder.Reference.createStandAlone(this, id))
                        : vanilla.get(id);
            }

            @Override
            public java.util.Optional<net.minecraft.core.HolderSet.Named<Biome>> get(final net.minecraft.tags.TagKey<Biome> id)
            {
                return vanilla.get(id);
            }
        };
        final NeoForgeWorld world = new NeoForgeWorld("Orilla", TestEngine.configs().resolve("worlds/Orilla").toFile(), biomes);
        final var lakeshore = NeoForgeBiome.registryKey("Orilla", "Lakeshore");
        assertEquals("terraincontrol:orilla/lakeshore", lakeshore.identifier().toString());
        assertEquals(java.util.Optional.of("Lakeshore"), com.khorn.terraincontrol.neoforge.api.TerrainControlBiomes.displayName(lakeshore));
        assertEquals(lakeshore, ((NeoForgeBiome) world.getBiomeByName("Lakeshore")).getSavedBiome().unwrapKey().orElseThrow());
        assertEquals(java.util.Optional.empty(),
                com.khorn.terraincontrol.neoforge.api.TerrainControlBiomes.displayName(net.minecraft.world.level.biome.Biomes.PLAINS));
    }

    /**
     * Exports every world's custom biomes into a datapack: {@code gradlew :platforms:neoforge:test --tests *BiomeExportTest
     * -PbiomesTo=<datapack folder>}.
     */
    @Test
    void exportAll() throws Exception
    {
        final String to = System.getProperty("tc.biomes.to", "");
        assumeTrue(!to.isEmpty() && TestEngine.configs() != null, "No -PbiomesTo given");
        final HolderLookup.Provider registries = VanillaRegistries.createWorldLookup();
        final BiomeExporter exporter = new BiomeExporter();
        final List<Path> worlds;
        try (Stream<Path> dirs = Files.list(TestEngine.configs().resolve("worlds")))
        {
            worlds = dirs.filter(dir -> Files.exists(dir.resolve("WorldConfig.ini"))).sorted().toList();
        }
        for (final Path world : worlds)
        {
            final int before = exporter.biomes;
            exporter.export(load(registries, world.getFileName().toString()), Path.of(to));
            System.out.println(world.getFileName() + ": " + (exporter.biomes - before) + " biomes");
        }
        System.out.println("Exported " + exporter.biomes + " biomes; mobs left out " + exporter.skippedMobs);
        assertEquals(exporter.biomes, decodeAll(registries, Path.of(to)));
    }
}
