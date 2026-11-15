package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.khorn.terraincontrol.TerrainControl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The converter on lines from Almura's 1.12 configs, and (with -PconvertFrom=... -PconvertTo=...) on a whole folder of them.
 */
final class ConfigConverterTest
{
    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    private static String bc(final String line)
    {
        return new ConfigConverter().line(line, false);
    }

    @Test
    void blocksToPlaceBecomeBlockStates()
    {
        assertEquals("SurfaceBlock: minecraft:grass_block", bc("SurfaceBlock: minecraft:grass"));
        assertEquals("SurfaceBlock: minecraft:white_terracotta", bc("SurfaceBlock: minecraft:stained_hardened_clay:0"));
        assertEquals("GroundBlock: minecraft:terracotta", bc("GroundBlock: minecraft:hardened_clay"));
        assertEquals("SurfaceBlock: minecraft:dirt_path", bc("SurfaceBlock: minecraft:grass_path"));
        // 1.12's "water" was still water, its flowing water "flowing_water"
        assertEquals("WaterBlock: minecraft:water", bc("WaterBlock: minecraft:water"));
        assertEquals("IceBlock: minecraft:ice", bc("IceBlock: ICE"));
    }

    @Test
    void blocksToCheckForTakeAllTheirVariants()
    {
        assertEquals("SourceBlocks: minecraft:air,minecraft:water", bc("SourceBlocks: AIR,STATIONARY_WATER"));
        assertEquals("SourceBlocks: minecraft:granite,minecraft:coarse_dirt,minecraft:dirt,minecraft:podzol,minecraft:red_sand",
                bc("SourceBlocks: minecraft:stone:1,minecraft:dirt:1,minecraft:dirt,minecraft:sand:1"));
        // Numbers 1.12 had no block for were air, already in the list
        assertEquals("SourceBlocks: minecraft:air,minecraft:stone,minecraft:granite,minecraft:polished_granite,minecraft:diorite,"
                + "minecraft:polished_diorite,minecraft:andesite,minecraft:polished_andesite", bc("SourceBlocks: AIR,1,342,397:1"));
        assertEquals("ExtrudeThroughBlocks: minecraft:air", bc("ExtrudeThroughBlocks: minecraft:air"));
    }

    @Test
    void resourcesConvertOnlyTheirBlocks()
    {
        assertEquals("Ore(minecraft:granite,33,10,100.0,0,79,minecraft:stone,minecraft:granite,minecraft:polished_granite,"
                        + "minecraft:diorite,minecraft:polished_diorite,minecraft:andesite,minecraft:polished_andesite)",
                bc("Ore(minecraft:stone:1,33,10,100.0,0,79,minecraft:stone)"));
        assertEquals("UnderWaterOre(minecraft:sand,7,3,100.0,minecraft:dirt,minecraft:coarse_dirt,minecraft:podzol,minecraft:grass_block)",
                bc("UnderWaterOre(minecraft:sand,7,3,100.0,minecraft:dirt,minecraft:grass)"));
        assertEquals("SmallLake(minecraft:water,4,7.0,8,119)", bc("SmallLake(minecraft:water,4,7.0,8,119)"));
        // Plant types stay plant types; a double plant's default state is its lower half
        assertEquals("Grass(Fern,NotGrouped,80,100.0,minecraft:grass_block)", bc("Grass(Fern,NotGrouped,80,100.0,minecraft:grass)"));
        assertEquals("Plant(minecraft:rose_bush,2,100.0,0,128,minecraft:grass_block)",
                bc("Plant(minecraft:double_plant:4,2,100.0,0,128,minecraft:grass)"));
        // Not a resource with blocks
        assertEquals("Tree(10,HugeTaiga2,8,HugeTaiga1,30)", bc("Tree(10,HugeTaiga2,8,HugeTaiga1,30)"));
    }

    @Test
    void customObjectsConvertTheirBlocks()
    {
        assertEquals("B(-8,10,-4,minecraft:stone)", bc("B(-8,10,-4,STONE)"));
        assertEquals("Block(0,0,1,minecraft:water[level=8])", bc("Block(0,0,1,WATER:8)"));
        assertEquals("Block(0,1,0,minecraft:chest[facing=east],chest.nbt)", bc("Block(0,1,0,minecraft:chest:5,chest.nbt)"));
        assertEquals("Block(0,0,0,minecraft:oak_log[axis=x])", bc("Block(0,0,0,LOG:4)"));
        assertEquals("RB(0,0,0,minecraft:mossy_cobblestone,50,minecraft:chest,loot.nbt,10)",
                bc("RB(0,0,0,MOSSY_COBBLESTONE,50,minecraft:chest:2,loot.nbt,10)"));
        // Skulls, whose kind 1.12 kept in their block entity
        assertEquals("B(0,0,0,minecraft:skeleton_skull)", bc("B(0,0,0,SKULL:1)"));
        assertEquals("Block(0,0,0,minecraft:skeleton_wall_skull[facing=east],skull.nbt)", bc("Block(0,0,0,minecraft:skull:5,skull.nbt)"));
        assertEquals("BlockCheck(0,-1,0,minecraft:grass_block,minecraft:dirt,minecraft:coarse_dirt,minecraft:podzol)",
                bc("BlockCheck(0,-1,0,GRASS,DIRT)"));
    }

    @Test
    void replacedBlocksReplaceEveryVariant()
    {
        assertEquals("ReplacedBlocks: (minecraft:air,minecraft:lava,1,10)", bc("ReplacedBlocks: (minecraft:air,minecraft:lava,1,10)"));
        assertEquals("ReplacedBlocks: (minecraft:dirt,minecraft:stone,81,255),(minecraft:coarse_dirt,minecraft:stone,81,255),"
                + "(minecraft:podzol,minecraft:stone,81,255)", bc("ReplacedBlocks: (minecraft:dirt,minecraft:stone,81,255)"));
        assertEquals("ReplacedBlocks: None", bc("ReplacedBlocks: None"));
    }

    @Test
    void surfaceLayersConvertTheirBlocks()
    {
        assertEquals("SurfaceAndGroundControl: minecraft:podzol, minecraft:dirt, -0.95, minecraft:coarse_dirt, minecraft:dirt, 1.75",
                bc("SurfaceAndGroundControl: minecraft:dirt:2, minecraft:dirt, -0.95, minecraft:dirt:1, minecraft:dirt, 1.75"));
        assertEquals("SurfaceAndGroundControl: Mesa", bc("SurfaceAndGroundControl: Mesa"));
    }

    @Test
    void bo2sConvertTheirNumbers()
    {
        final ConfigConverter converter = new ConfigConverter();
        assertEquals("0,-2,0:minecraft:oak_leaves", converter.line("0,-2,0:18.0", true));
        assertEquals("0,0,0:minecraft:spruce_log", converter.line("0,0,0:17.1", true));
        assertEquals("1,2,3:minecraft:oak_log#2@50", converter.line("1,2,3:17#2@50", true));
        assertEquals("spawnOnBlockType=minecraft:grass_block,minecraft:dirt,minecraft:coarse_dirt,minecraft:podzol",
                converter.line("spawnOnBlockType=2,3", true));
        assertEquals("collisionBlockType=All", converter.line("collisionBlockType=All", true));
        assertEquals("rarity=100", converter.line("rarity=100", true));
    }

    @Test
    void commentsAndLineBreaksStay()
    {
        final ConfigConverter converter = new ConfigConverter();
        assertEquals("# SurfaceBlock: minecraft:grass\r\nSurfaceBlock: minecraft:grass_block\r\nBiomeHeight: 0.2\n",
                converter.file("test.bc", "# SurfaceBlock: minecraft:grass\r\nSurfaceBlock: minecraft:grass\r\nBiomeHeight: 0.2\n", false));
        assertEquals(List.of(), converter.report.unresolved);
    }

    @Test
    void whatItCannotReadStaysAndIsReported()
    {
        final ConfigConverter converter = new ConfigConverter();
        assertEquals("SurfaceBlock: almura:no_such_block", converter.line("SurfaceBlock: almura:no_such_block", false));
        assertEquals(1, converter.report.failed);
    }

    /**
     * Converts a whole folder: {@code gradlew :platforms:neoforge:test --tests *ConfigConverterTest -PconvertFrom=<1.12 configs>
     * -PconvertTo=<new folder>}.
     */
    @Test
    void convertFolder() throws Exception
    {
        final String from = System.getProperty("tc.convert.from", "");
        final String to = System.getProperty("tc.convert.to", "");
        assumeTrue(!from.isEmpty() && !to.isEmpty(), "No -PconvertFrom and -PconvertTo given");
        final ConfigConverter converter = new ConfigConverter();
        converter.convert(Path.of(from), Path.of(to));
        System.out.println(converter.report.summary());
        assumeTrue(Files.exists(Path.of(to, "conversion-report.txt")));
    }

    /**
     * Checks converted configs: {@code gradlew :platforms:neoforge:test --tests *ConfigConverterTest -PconvertCheck=<folder>}. Every
     * block TerrainControl reads from them must read as a 26.3 block state.
     */
    @Test
    void checkFolder() throws Exception
    {
        final String folder = System.getProperty("tc.convert.check", "");
        assumeTrue(!folder.isEmpty(), "No -PconvertCheck given");
        final ConfigConverter checker = new ConfigConverter();
        checker.check(Path.of(folder));
        System.out.println("Checked " + checker.report.files + " configs, " + checker.report.converted + " blocks, "
                + checker.report.failed + " no 26.3 block state");
        checker.report.unresolved.stream().limit(20).forEach(System.out::println);
        assertEquals(0, checker.report.failed, "Blocks that are no 26.3 block state");
    }
}
