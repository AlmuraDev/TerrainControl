package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.configuration.ReplacedBlocksMatrix;
import com.khorn.terraincontrol.exception.InvalidConfigException;
import com.khorn.terraincontrol.neoforge.util.NBTHelper;
import com.khorn.terraincontrol.util.MaterialSet;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The materials against 26.3's real blocks: the 1.12 table TerrainControl's code uses, the configs' block states, and the material
 * sets and block replacing built on them.
 */
final class MaterialsTest
{
    @BeforeAll
    static void startEngine()
    {
        TestEngine.start();
    }

    private static BlockState legacy(final DefaultMaterial material, final int data)
    {
        return ((NeoForgeMaterialData) TerrainControl.toLocalMaterialData(material, data)).internalBlock();
    }

    private static NeoForgeMaterialData read(final String name) throws InvalidConfigException
    {
        return (NeoForgeMaterialData) TerrainControl.readMaterial(name);
    }

    @Test
    void everyLegacyBlockHasA26_3Block()
    {
        final List<String> missing = new ArrayList<>();
        for (final DefaultMaterial material : DefaultMaterial.values())
        {
            if (material.id < 0 || material.id >= 256)
            {
                continue;
            }
            try
            {
                LegacyMaterials.get().state(material, 0);
            } catch (IllegalArgumentException e)
            {
                missing.add(material.name());
            }
        }
        // UNKNOWN_BLOCK is TerrainControl's stand-in, no block of 1.12's; a 1.12 skull's kind was in its block entity, so the
        // flattening table has no block state for it either (Minecraft fills it in from the block entity when it upgrades a chunk)
        assertEquals(List.of("SKULL", "UNKNOWN_BLOCK"), missing);
    }

    @Test
    void legacyDataBecomesBlockStates()
    {
        // The mesa colours, as MesaSurfaceGenerator asks for them
        // (26.3 keeps its coloured blocks in colour collections, so these go by name)
        assertEquals("minecraft:orange_terracotta", NeoForgeMaterialData.of(legacy(DefaultMaterial.STAINED_CLAY, 1)).getName());
        assertEquals("minecraft:red_terracotta", NeoForgeMaterialData.of(legacy(DefaultMaterial.STAINED_CLAY, 14)).getName());
        assertEquals("minecraft:red_wool", NeoForgeMaterialData.of(legacy(DefaultMaterial.WOOL, 14)).getName());
        assertEquals(Blocks.TERRACOTTA.defaultBlockState(), legacy(DefaultMaterial.HARD_CLAY, 0));
        assertEquals(Blocks.COARSE_DIRT.defaultBlockState(), legacy(DefaultMaterial.DIRT, 1));
        assertEquals(Blocks.RED_SAND.defaultBlockState(), legacy(DefaultMaterial.SAND, 1));
        // Renamed since 1.13: tall grass's grass is short_grass, the grass block is grass_block
        assertEquals(Blocks.SHORT_GRASS.defaultBlockState(), legacy(DefaultMaterial.LONG_GRASS, 1));
        assertEquals(Blocks.GRASS_BLOCK, legacy(DefaultMaterial.GRASS, 0).getBlock());
        // Snow 4 high (data 3), as FrozenSurfaceHelper asks for it
        assertEquals(4, legacy(DefaultMaterial.SNOW, 3).getValue(BlockStateProperties.LAYERS));
        // A vine on its south side (data 1), as VinesGen asks for it
        assertTrue(legacy(DefaultMaterial.VINE, 1).getValue(BlockStateProperties.SOUTH));
        assertFalse(legacy(DefaultMaterial.VINE, 1).getValue(BlockStateProperties.NORTH));
        // Logs keep their direction
        assertEquals(Direction.Axis.X, legacy(DefaultMaterial.LOG, 4).getValue(BlockStateProperties.AXIS));
        assertEquals(Blocks.AZURE_BLUET, legacy(DefaultMaterial.RED_ROSE, 3).getBlock());
        assertEquals(Blocks.LAVA, legacy(DefaultMaterial.STATIONARY_LAVA, 0).getBlock());
    }

    @Test
    void blocksKnowTheir1_12Block() throws InvalidConfigException
    {
        final NeoForgeMaterialData water = read("minecraft:water");
        assertEquals(DefaultMaterial.WATER, water.toDefaultMaterial());
        // Still and flowing water both became minecraft:water
        assertTrue(water.isMaterial(DefaultMaterial.WATER));
        assertTrue(water.isMaterial(DefaultMaterial.STATIONARY_WATER));
        assertFalse(water.isMaterial(DefaultMaterial.LAVA));
        assertTrue(water.isLiquid());
        assertFalse(water.isSolid());

        assertEquals(DefaultMaterial.STAINED_CLAY, read("minecraft:orange_terracotta").toDefaultMaterial());
        assertTrue(read("minecraft:grass_block").isMaterial(DefaultMaterial.GRASS));
        assertTrue(read("minecraft:grass_block").isSolid());
        assertTrue(read("minecraft:oak_leaves").canSnowFallOn());
        // Blocks 1.12 did not have
        assertEquals(DefaultMaterial.UNKNOWN_BLOCK, read("minecraft:deepslate").toDefaultMaterial());
        assertTrue(read("minecraft:deepslate").isSolid());
        assertTrue(read("minecraft:air").isAir());
        assertTrue(read("minecraft:gravel").canFall());
    }

    @Test
    void configsName26_3BlockStates() throws InvalidConfigException
    {
        assertEquals(Blocks.STONE.defaultBlockState(), read("minecraft:stone").internalBlock());
        // No namespace: Minecraft's
        assertEquals(Blocks.STONE.defaultBlockState(), read("stone").internalBlock());
        assertEquals(Blocks.STONE.defaultBlockState(), read("minecraft:stone[]").internalBlock());
        assertEquals("minecraft:stone", read("minecraft:stone[]").getName());

        final NeoForgeMaterialData log = read("minecraft:oak_log[axis=x]");
        assertEquals(Direction.Axis.X, log.internalBlock().getValue(BlockStateProperties.AXIS));
        assertEquals("minecraft:oak_log[axis=x]", log.getName());
        assertEquals(log, read(log.getName()));
        assertEquals("minecraft:oak_log", log.withDefaultBlockData().getName());
    }

    @Test
    void configsWith1_12NamesFail()
    {
        // 1.12's block:data, TerrainControl's 1.12 names, and 1.12 ids 26.3 no longer has: all for the converter to fix
        for (final String name : List.of("minecraft:stone:1", "minecraft:wool:14", "STONE", "WATER:8", "minecraft:grass", "minecraft:log"))
        {
            assertThrows(InvalidConfigException.class, () -> read(name), name);
        }
    }

    @Test
    void customObjectsTurnNorthToWest() throws InvalidConfigException
    {
        final LocalMaterialData stairs = read("minecraft:oak_stairs[facing=east]");
        final LocalMaterialData turned = stairs.rotate();
        assertEquals(Direction.NORTH, ((NeoForgeMaterialData) turned).internalBlock().getValue(StairBlock.FACING));
        assertEquals(Direction.WEST, ((NeoForgeMaterialData) turned.rotate()).internalBlock().getValue(StairBlock.FACING));
        assertEquals(stairs, turned.rotate().rotate().rotate());
        // Unturnable blocks stay as they are
        final LocalMaterialData stone = read("minecraft:stone");
        assertEquals(stone, stone.rotate());
    }

    @Test
    void hashCodesTellStatesAndBlocksApart() throws InvalidConfigException
    {
        final LocalMaterialData x = read("minecraft:oak_log[axis=x]");
        final LocalMaterialData y = read("minecraft:oak_log[axis=y]");
        assertNotEquals(x.hashCode(), y.hashCode());
        assertEquals(x.hashCodeWithoutBlockData(), y.hashCodeWithoutBlockData());
        assertNotEquals(x.hashCode(), x.hashCodeWithoutBlockData());
    }

    @Test
    void materialSetsCheckBlocksOrStates() throws InvalidConfigException
    {
        final MaterialSet anyLog = new MaterialSet();
        anyLog.parseAndAdd("minecraft:oak_log");
        assertTrue(anyLog.contains(read("minecraft:oak_log[axis=x]")));
        assertTrue(anyLog.contains(read("minecraft:oak_log")));
        assertFalse(anyLog.contains(read("minecraft:birch_log")));

        final MaterialSet sidewaysLog = new MaterialSet();
        sidewaysLog.parseAndAdd("minecraft:oak_log[axis=x]");
        assertTrue(sidewaysLog.contains(read("minecraft:oak_log[axis=x]")));
        assertFalse(sidewaysLog.contains(read("minecraft:oak_log")));

        // A check of the default state only, written back so that it reads the same
        final MaterialSet uprightLog = new MaterialSet();
        uprightLog.parseAndAdd("minecraft:oak_log[]");
        assertTrue(uprightLog.contains(read("minecraft:oak_log")));
        assertFalse(uprightLog.contains(read("minecraft:oak_log[axis=x]")));
        assertEquals("minecraft:oak_log[]", uprightLog.toString());
    }

    @Test
    void replacedBlocksTakeBlockStatesWithCommas() throws InvalidConfigException
    {
        final ReplacedBlocksMatrix matrix = new ReplacedBlocksMatrix(
                "(minecraft:stone,minecraft:oak_stairs[facing=east,half=top],0,64),(minecraft:dirt,minecraft:sand)", 1023);
        final LocalMaterialData stone = read("minecraft:stone");
        final LocalMaterialData[] stoneByY = matrix.compiledInstructions[stone.getBlockId()];
        assertEquals(read("minecraft:oak_stairs[facing=east,half=top]"), stoneByY[64]);
        assertEquals(null, stoneByY[65]);
        assertEquals(read("minecraft:sand"), matrix.compiledInstructions[read("minecraft:dirt").getBlockId()][1023]);
    }

    @Test
    void nbtSurvivesTheTripThroughTerrainControl() throws Exception
    {
        final CompoundTag tag = TagParser.parseCompoundFully(
                "{Items:[{Slot:0b,id:\"minecraft:apple\",count:3}],Lock:\"\",x:5,scale:1.5d,flags:[I;1,2,3]}");
        assertEquals(tag, NBTHelper.toCompoundTag(NBTHelper.toNamedBinaryTag("", tag)));
        assertEquals(tag, NBTHelper.toCompoundTag(TerrainControl.getEngine().toNamedBinaryTag(tag.toString())));
    }
}
