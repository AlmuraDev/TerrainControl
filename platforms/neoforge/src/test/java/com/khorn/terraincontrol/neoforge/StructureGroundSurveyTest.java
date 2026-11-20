package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.configuration.BiomeConfig;
import com.khorn.terraincontrol.generator.ChunkBuffer;
import com.khorn.terraincontrol.neoforge.generator.TCStructures;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where the newer structures would go on TerrainControl's own terrain, world by world: the ground of chunks across each world
 * (sea, beach, dry land, mountain) and in how many of them each structure may start. Prints a table, and checks only that a world
 * with them on has somewhere for them.
 */
final class StructureGroundSurveyTest
{
    private static final HolderLookup.Provider REGISTRIES = VanillaRegistries.createWorldLookup();
    private static final String[] STRUCTURES = {"pillager_outpost", "shipwreck", "shipwreck_beached", "ocean_ruin_cold",
            "ocean_ruin_warm", "buried_treasure", "ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp",
            "ruined_portal_mountain", "ruined_portal_ocean", "trail_ruins", "ancient_city", "trial_chambers", "camps"};

    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    /** The ground in the middle of a chunk, from TerrainControl's terrain, as TCChunkGenerator measures it */
    private static TCStructures.Ground ground(final NeoForgeWorld world, final int chunkX, final int chunkZ)
    {
        final int height = world.getHeightCap();
        final BlockState[] column = new BlockState[height];
        java.util.Arrays.fill(column, Blocks.AIR.defaultBlockState());
        final ChunkCoordinate coordinate = ChunkCoordinate.fromChunkCoords(chunkX, chunkZ);
        world.getTerrainGenerator().generate(new ChunkBuffer()
        {
            @Override
            public ChunkCoordinate getChunkCoordinate()
            {
                return coordinate;
            }

            @Override
            public void setBlock(final int x, final int y, final int z, final LocalMaterialData material)
            {
                if ((x & 15) == 8 && (z & 15) == 8 && y >= 0 && y < height)
                {
                    column[y] = ((NeoForgeMaterialData) material).internalBlock();
                }
            }

            @Override
            public LocalMaterialData getBlock(final int x, final int y, final int z)
            {
                return NeoForgeMaterialData.of((x & 15) == 8 && (z & 15) == 8 && y >= 0 && y < height ? column[y]
                        : Blocks.AIR.defaultBlockState());
            }
        });
        int surface = 0;
        int floor = 0;
        for (int y = height - 1; y >= 0; y--)
        {
            if (surface == 0 && !column[y].isAir())
            {
                surface = y + 1;
            }
            if (!column[y].isAir() && column[y].getFluidState().isEmpty() && !column[y].canBeReplaced())
            {
                floor = y + 1;
                break;
            }
        }
        final BlockState top = column[Math.max(0, floor - 1)];
        return new TCStructures.Ground(surface, floor, world.getConfigs().getWorldConfig().waterLevelMax,
                top.is(Blocks.SAND) || top.is(Blocks.RED_SAND) || top.is(Blocks.GRAVEL));
    }

    private static Holder<Structure> structure(final String name)
    {
        return REGISTRIES.lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace(name)));
    }

    private static String shortName(final String name)
    {
        return name.replace("ruined_portal", "rp").replace("shipwreck", "sw").replace("ocean_ruin", "or").replace("pillager_", "")
                .replace("buried_treasure", "treasure").replace("trial_chambers", "chambers");
    }

    @Test
    void whereTheNewerStructuresGo()
    {
        assumeTrue(TestEngine.configs() != null, "No converted configs (ServerConfigs-26.3)");
        final List<String> camps = new ArrayList<>();
        for (final BiomeConfig.AbandonedCampType camp : BiomeConfig.AbandonedCampType.values())
        {
            if (camp != BiomeConfig.AbandonedCampType.auto && camp != BiomeConfig.AbandonedCampType.disabled)
            {
                camps.add("abandoned_camp_" + TCStructures.snake(camp.name()));
            }
        }
        final StringBuilder table = new StringBuilder("\nSURVEY world        chunks  sea beach  dry mount |");
        for (final String s : STRUCTURES)
        {
            table.append(' ').append(shortName(s));
        }
        for (final File folder : TestEngine.configs().resolve("worlds").toFile().listFiles(File::isDirectory))
        {
            if (!new File(folder, "WorldConfig.ini").isFile())
            {
                continue;
            }
            final String name = folder.getName();
            final NeoForgeWorld world = new NeoForgeWorld(name, folder, REGISTRIES.lookupOrThrow(Registries.BIOME));
            world.initialize(20261119L);
            final Map<String, Integer> allowed = new TreeMap<>();
            int chunks = 0;
            int sea = 0;
            int beach = 0;
            int dry = 0;
            int mountain = 0;
            for (int i = 0; i < 12; i++)
            {
                for (int j = 0; j < 12; j++)
                {
                    final int cx = (i - 6) * 23;
                    final int cz = (j - 6) * 29;
                    final TCStructures.Ground g = ground(world, cx, cz);
                    chunks++;
                    final int depth = g.surface() - g.floor();
                    final boolean isDry = depth == 0;
                    sea += depth >= 10 && g.floor() <= g.seaLevel() - 10 ? 1 : 0;
                    beach += g.sandy() && depth <= 2 && g.floor() >= g.seaLevel() - 2 && g.floor() <= g.seaLevel() + 4 ? 1 : 0;
                    dry += isDry ? 1 : 0;
                    mountain += isDry && g.surface() >= g.seaLevel() + 50 ? 1 : 0;
                    final Holder<Biome> biome = ((NeoForgeBiome) world.getCalculatedBiome((cx << 4) + 8, (cz << 4) + 8)).getSavedBiome();
                    for (final String s : STRUCTURES)
                    {
                        final boolean ok = s.equals("camps")
                                ? camps.stream().anyMatch(c -> TCStructures.inBiome(world, structure(c), biome, () -> g))
                                : TCStructures.inBiome(world, structure(s), biome, () -> g);
                        if (ok)
                        {
                            allowed.merge(s, 1, Integer::sum);
                        }
                    }
                }
            }
            table.append(String.format("%nSURVEY %-12s %6d %4d %5d %4d %5d |", name, chunks, sea, beach, dry, mountain));
            for (final String s : STRUCTURES)
            {
                table.append(String.format(" %" + shortName(s).length() + "d", allowed.getOrDefault(s, 0)));
            }
            if (world.getConfigs().getWorldConfig().trialChambersEnabled)
            {
                assertTrue(allowed.getOrDefault("trial_chambers", 0) + allowed.getOrDefault("ruined_portal", 0) > 0,
                        name + " has nowhere for them");
            }
        }
        System.out.println(table);
    }
}
