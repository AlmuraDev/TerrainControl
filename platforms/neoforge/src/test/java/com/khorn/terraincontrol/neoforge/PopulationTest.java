package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.generator.ChunkBuffer;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * TerrainControl's population on Almura's converted worlds, without a game: on chunks of TerrainControl's own terrain, it must run
 * through every resource and custom object of the world without failing, and place blocks.
 */
final class PopulationTest
{
    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    /** Chunks of TerrainControl's terrain, made when first asked for */
    private static class TerrainLevel implements PopulationLevel
    {
        final NeoForgeWorld world;
        final Map<Long, BlockState[]> chunks = new HashMap<>();
        final Map<String, Integer> placed = new TreeMap<>();
        final Map<String, Integer> features = new TreeMap<>();
        int blockEntities;
        int flowing;
        /** The chunk 26.3 is decorating, and how often population reached further than 26.3 lets it (a chunk around it) */
        int decoratingX, decoratingZ;
        final java.util.List<String> tooFar = new java.util.ArrayList<>();

        void checkReach(final BlockPos pos, final String what)
        {
            if (Math.abs((pos.getX() >> 4) - this.decoratingX) > 1 || Math.abs((pos.getZ() >> 4) - this.decoratingZ) > 1)
            {
                this.tooFar.add(what + " " + pos + " while decorating chunk " + this.decoratingX + ", " + this.decoratingZ);
            }
        }

        final int size;

        TerrainLevel(final NeoForgeWorld world, final int size)
        {
            this.world = world;
            this.size = size;
        }

        BlockState[] chunk(final int x, final int z)
        {
            return this.chunks.computeIfAbsent((long) (x >> 4) << 32 | (z >> 4) & 0xFFFFFFFFL, key -> {
                final BlockState[] blocks = new BlockState[256 * 256];
                java.util.Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
                final ChunkCoordinate coordinate = ChunkCoordinate.fromChunkCoords(x >> 4, z >> 4);
                this.world.getTerrainGenerator().generate(new ChunkBuffer()
                {
                    @Override
                    public ChunkCoordinate getChunkCoordinate()
                    {
                        return coordinate;
                    }

                    @Override
                    public void setBlock(final int bx, final int by, final int bz, final LocalMaterialData material)
                    {
                        if (by >= 0 && by < 256)
                        {
                            blocks[by * 256 + (bz & 15) * 16 + (bx & 15)] = ((NeoForgeMaterialData) material).internalBlock();
                        }
                    }

                    @Override
                    public LocalMaterialData getBlock(final int bx, final int by, final int bz)
                    {
                        return NeoForgeMaterialData.of(by >= 0 && by < 256 ? blocks[by * 256 + (bz & 15) * 16 + (bx & 15)]
                                : Blocks.AIR.defaultBlockState());
                    }
                });
                return blocks;
            });
        }

        @Override
        public int getMinY()
        {
            return 0;
        }

        @Override
        public int getMaxY()
        {
            return 255;
        }

        /** Population may only reach the 2 by 2 chunks it populates; anything further is air here */
        boolean near(final int x, final int z)
        {
            return x >= -16 && z >= -16 && x < (this.size + 1) * 16 && z < (this.size + 1) * 16;
        }

        @Override
        public BlockState getBlockState(final BlockPos pos)
        {
            checkReach(pos, "read");
            return pos.getY() < 0 || pos.getY() > 255 || !near(pos.getX(), pos.getZ()) ? Blocks.AIR.defaultBlockState()
                    : chunk(pos.getX(), pos.getZ())[pos.getY() * 256 + (pos.getZ() & 15) * 16 + (pos.getX() & 15)];
        }

        @Override
        public void setBlock(final BlockPos pos, final BlockState state)
        {
            checkReach(pos, "write");
            if (pos.getY() < 0 || pos.getY() > 255 || !near(pos.getX(), pos.getZ()))
            {
                throw new IllegalStateException("Population wrote outside its chunks: " + pos);
            }
            chunk(pos.getX(), pos.getZ())[pos.getY() * 256 + (pos.getZ() & 15) * 16 + (pos.getX() & 15)] = state;
            this.placed.merge(NeoForgeMaterialData.of(state.getBlock().defaultBlockState()).getName(), 1, Integer::sum);
        }

        @Override
        public void setBlockEntity(final CompoundTag tag)
        {
            this.blockEntities++;
        }

        @Override
        public void scheduleFluidTick(final BlockPos pos, final Fluid fluid)
        {
            this.flowing++;
        }

        @Override
        public int getSurface(final int x, final int z)
        {
            if (!near(x, z))
            {
                return 0;
            }
            final BlockState[] blocks = chunk(x, z);
            for (int y = 255; y >= 0; y--)
            {
                if (!blocks[y * 256 + (z & 15) * 16 + (x & 15)].isAir())
                {
                    return y + 1;
                }
            }
            return 0;
        }

        @Override
        public boolean placeFeature(final ResourceKey<Feature> feature, final RandomSource random, final BlockPos pos)
        {
            // Planted within reach (how far the feature itself reaches is RegionPopulationLevel's to guard)
            checkReach(pos, "feature " + feature.identifier().getPath() + " at");
            // 26.3's features need a real level; counted only
            this.features.merge(feature.identifier().getPath(), 1, Integer::sum);
            return true;
        }
    }

    private static TerrainLevel populate(final String name, final int size)
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final File dir = TestEngine.configs().resolve("worlds").resolve(name).toFile();
        assumeTrue(dir.isDirectory(), "No world " + name);
        final HolderGetter<Biome> biomes = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
        final NeoForgeWorld world = new NeoForgeWorld(name, dir, biomes);
        world.initialize(20261115L);
        final TerrainLevel level = new TerrainLevel(world, size);
        world.lock().lock();
        try
        {
            for (int chunkX = 0; chunkX < size; chunkX++)
            {
                for (int chunkZ = 0; chunkZ < size; chunkZ++)
                {
                    level.decoratingX = chunkX;
                    level.decoratingZ = chunkZ;
                    world.populate(level, new ChunkPos(chunkX, chunkZ));
                }
            }
        } finally
        {
            world.lock().unlock();
        }
        System.out.println(name + ", " + size + " by " + size + " chunks populated: blocks " + level.placed + "; 26.3 features "
                + level.features + "; block entities " + level.blockEntities + "; flowing " + level.flowing);
        level.tooFar.stream().limit(5).forEach(System.out::println);
        assertTrue(level.tooFar.isEmpty(), level.tooFar.size() + " reads or writes further than a chunk from the chunk decorated");
        return level;
    }

    /**
     * Which objects population spawns around a spot: {@code -Dtc.objectsAt=<world>,<x>,<z>,<seed>} (with -PobjectsAt=...), for finding
     * where something seen in a world comes from.
     */
    @Test
    void objectsAt()
    {
        final String spec = System.getProperty("tc.objectsAt", "");
        assumeTrue(!spec.isEmpty() && TestEngine.configs() != null, "No -PobjectsAt");
        final String[] parts = spec.split(",");
        final String name = parts[0];
        final int x = Integer.parseInt(parts[1]);
        final int z = Integer.parseInt(parts[2]);
        final long seed = Long.parseLong(parts[3]);
        final NeoForgeWorld world = new NeoForgeWorld(name, TestEngine.configs().resolve("worlds").resolve(name).toFile(),
                VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME));
        world.initialize(seed);
        System.out.println(name + " at " + x + ", " + z + ": biome " + world.getCalculatedBiome(x, z).getName());
        final java.util.List<String> spawned = new java.util.ArrayList<>();
        com.khorn.terraincontrol.TerrainControl.registerEventHandler(new com.khorn.terraincontrol.events.EventHandler()
        {
            @Override
            public boolean canCustomObjectSpawn(final com.khorn.terraincontrol.customobjects.CustomObject object,
                    final com.khorn.terraincontrol.LocalWorld localWorld, final int ox, final int oy, final int oz, final boolean isCancelled)
            {
                if (localWorld == world && Math.abs(ox - x) <= 32 && Math.abs(oz - z) <= 32)
                {
                    spawned.add(object.getName() + " at " + ox + ", " + oy + ", " + oz + " in " + world.getCalculatedBiome(ox, oz).getName());
                }
                return true;
            }
        });
        // A level of TerrainControl's terrain around the spot, populated as 26.3 would
        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        final TerrainLevel level = new TerrainLevel(world, 1)
        {
            @Override
            boolean near(final int bx, final int bz)
            {
                return Math.abs((bx >> 4) - chunkX) <= 4 && Math.abs((bz >> 4) - chunkZ) <= 4;
            }
        };
        world.lock().lock();
        try
        {
            for (int cx = chunkX - 2; cx <= chunkX + 2; cx++)
            {
                for (int cz = chunkZ - 2; cz <= chunkZ + 2; cz++)
                {
                    level.decoratingX = cx;
                    level.decoratingZ = cz;
                    world.populate(level, new ChunkPos(cx, cz));
                }
            }
        } finally
        {
            world.lock().unlock();
        }
        spawned.forEach(System.out::println);
    }

    /** A birch sapling in Atlantis's TemperateHills grows its Sapling(Birch...) tree, in a live level (no chunk limit) */
    @Test
    void saplingsGrowTheirBiomesTrees()
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final NeoForgeWorld world = new NeoForgeWorld("Atlantis", TestEngine.configs().resolve("worlds/Atlantis").toFile(),
                VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME));
        world.initialize(20261115L);
        final com.khorn.terraincontrol.LocalBiome hills = world.getBiomeByName("TemperateHills");
        assumeTrue(hills != null, "Atlantis has no TemperateHills");
        final var gen = hills.getBiomeConfig().getSaplingGen(com.khorn.terraincontrol.generator.resource.SaplingType.Birch);
        assertTrue(gen != null, "TemperateHills grows no birch saplings");

        final TerrainLevel level = new TerrainLevel(world, 2);
        final int y = level.getSurface(8, 8);
        // A sapling's tree: TerrainControl's Forest tree (26.3's birch); in the test, 26.3's features are counted, not grown
        final boolean grown = world.placeLive(level, () -> gen.growSapling(world, new java.util.Random(1), false, 8, y, 8));
        System.out.println("Atlantis TemperateHills: birch sapling grown " + grown + ", features " + level.features);
        assertTrue(grown && level.features.getOrDefault("birch", 0) == 1);
    }

    @Test
    void keystonePopulates()
    {
        final TerrainLevel level = populate("Keystone", 4);
        assertTrue(level.placed.values().stream().mapToInt(Integer::intValue).sum() > 0, "population placed no blocks");
    }

    @Test
    void cemariaPopulates()
    {
        // Almura's busiest world: 449 biomes, 2613 objects
        final TerrainLevel level = populate("Cemaria", 4);
        assertTrue(level.placed.values().stream().mapToInt(Integer::intValue).sum() > 0, "population placed no blocks");
    }
}
