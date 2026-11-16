package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.generator.ChunkBuffer;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * TerrainControl's worlds on 26.3 without a game: the 1.12 vanilla biomes as 26.3's, and Almura's converted configs
 * (ServerConfigs-26.3) loaded and generating terrain, with 26.3's own biomes (the registry data generation builds).
 */
final class WorldGenerationTest
{
    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    private static String biome(final int legacyId)
    {
        return VanillaBiomes.byLegacyId(legacyId).identifier().toString();
    }

    @Test
    void legacyBiomesBecome26_3Biomes()
    {
        assertEquals("minecraft:plains", biome(1));
        // Renamed by 1.13 and 1.18
        assertEquals("minecraft:windswept_hills", biome(3));
        assertEquals("minecraft:swamp", biome(6));
        assertEquals("minecraft:snowy_plains", biome(12));
        assertEquals("minecraft:dark_forest", biome(29));
        assertEquals("minecraft:badlands", biome(37));
        // Gone in 1.18, merged into others
        assertEquals("minecraft:snowy_plains", biome(13));
        assertEquals("minecraft:windswept_hills", biome(20));
    }

    /** A plain buffer, as the generator's own height lookups use */
    private static final class Buffer implements ChunkBuffer
    {
        final ChunkCoordinate coordinate;
        final BlockState[] blocks = new BlockState[1024 * 256];

        Buffer(final int chunkX, final int chunkZ)
        {
            this.coordinate = ChunkCoordinate.fromChunkCoords(chunkX, chunkZ);
            java.util.Arrays.fill(this.blocks, Blocks.AIR.defaultBlockState());
        }

        @Override
        public ChunkCoordinate getChunkCoordinate()
        {
            return this.coordinate;
        }

        @Override
        public void setBlock(final int x, final int y, final int z, final LocalMaterialData material)
        {
            if (y >= 0 && y < 1024)
            {
                this.blocks[y * 256 + (z & 15) * 16 + (x & 15)] = ((NeoForgeMaterialData) material).internalBlock();
            }
        }

        @Override
        public LocalMaterialData getBlock(final int x, final int y, final int z)
        {
            return NeoForgeMaterialData.of(y >= 0 && y < 1024 ? this.blocks[y * 256 + (z & 15) * 16 + (x & 15)] : Blocks.AIR.defaultBlockState());
        }

        int surface(final int x, final int z)
        {
            for (int y = 1023; y >= 0; y--)
            {
                if (!this.blocks[y * 256 + z * 16 + x].isAir())
                {
                    return y;
                }
            }
            return -1;
        }
    }

    private static NeoForgeWorld load(final String name)
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final File dir = TestEngine.configs().resolve("worlds").resolve(name).toFile();
        assumeTrue(dir.isDirectory(), "No world " + name);
        final HolderGetter<Biome> biomes = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
        final NeoForgeWorld world = new NeoForgeWorld(name, dir, biomes);
        world.initialize(20261115L);
        return world;
    }

    @Test
    void keystoneGeneratesTerrain()
    {
        final NeoForgeWorld world = load("Keystone");
        final Map<String, Integer> blocks = new HashMap<>();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        boolean bottomSolid = true;
        for (int chunkX = -2; chunkX <= 2; chunkX++)
        {
            for (int chunkZ = -2; chunkZ <= 2; chunkZ++)
            {
                final Buffer buffer = new Buffer(chunkX, chunkZ);
                world.getTerrainGenerator().generate(buffer);
                bottomSolid &= !buffer.blocks[0].isAir() && !buffer.blocks[255].isAir();
                for (final BlockState state : buffer.blocks)
                {
                    blocks.merge(NeoForgeMaterialData.of(state).getName(), 1, Integer::sum);
                }
                for (int x = 0; x < 16; x++)
                {
                    for (int z = 0; z < 16; z++)
                    {
                        lowest = Math.min(lowest, buffer.surface(x, z));
                        highest = Math.max(highest, buffer.surface(x, z));
                    }
                }
            }
        }
        System.out.println("Keystone, 5 by 5 chunks: surface from y " + lowest + " to " + highest + "; blocks " + blocks);
        assertTrue(blocks.getOrDefault("minecraft:stone", 0) > 0, "stone");
        // The bottom layer is the world's bedrock block (Keystone's BedrockobBlock is stone)
        assertTrue(bottomSolid, "bedrock layer at y 0");
        // TerrainControl's 1.12 heights: below its height cap, above the bedrock
        assertTrue(lowest > 0 && highest < world.getHeightCap(), lowest + " to " + highest);

        final LocalBiome biome = world.getCalculatedBiome(8, 8);
        final Identifier saved = ((NeoForgeBiome) biome).getSavedBiome().unwrapKey().orElseThrow().identifier();
        System.out.println("Keystone at 8, 8: " + biome.getName() + ", saved as " + saved + "; saved biomes " + world.getSavedBiomes().size());
        assertEquals("minecraft", saved.getNamespace());
    }

    /**
     * A world 1024 high (WorldHeightCapBits and WorldHeightScaleBits 10, as Glacier and Yellowstone will be): Keystone so set
     * generates its terrain 4 times as high.
     */
    @Test
    void worldsGenerate1024High() throws Exception
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final java.nio.file.Path from = TestEngine.configs().resolve("worlds").resolve("Keystone");
        final java.nio.file.Path to = java.nio.file.Files.createTempDirectory("tc-tall").resolve("TallKeystone");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(from))
        {
            for (final java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator)
            {
                java.nio.file.Files.copy(file, to.resolve(from.relativize(file).toString()));
            }
        }
        final java.nio.file.Path ini = to.resolve("WorldConfig.ini");
        java.nio.file.Files.writeString(ini, java.nio.file.Files.readString(ini, java.nio.charset.StandardCharsets.ISO_8859_1)
                .replace("WorldHeightScaleBits: 8", "WorldHeightScaleBits: 10")
                .replace("WorldHeightCapBits: 8", "WorldHeightCapBits: 10"), java.nio.charset.StandardCharsets.ISO_8859_1);

        final NeoForgeWorld world = new NeoForgeWorld("TallKeystone", to.toFile(),
                VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME));
        world.initialize(20261115L);
        assertEquals(1024, world.getHeightCap());
        int highest = 0;
        for (int chunkX = -2; chunkX <= 2; chunkX++)
        {
            final Buffer buffer = new Buffer(chunkX, 0);
            world.getTerrainGenerator().generate(buffer);
            for (int x = 0; x < 16; x++)
            {
                for (int z = 0; z < 16; z++)
                {
                    highest = Math.max(highest, buffer.surface(x, z));
                }
            }
        }
        System.out.println("Keystone 1024 high: highest surface y " + highest);
        assertTrue(highest > 256 && highest < 1024, "highest surface " + highest);
    }
}
