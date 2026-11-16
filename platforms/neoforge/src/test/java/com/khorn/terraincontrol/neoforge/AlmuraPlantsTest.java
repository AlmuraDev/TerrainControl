package com.khorn.terraincontrol.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * Almura's wild plants without Almura (as in these tests): tall grass and ferns stay as they are, other blocks are never touched.
 */
final class AlmuraPlantsTest
{
    @Test
    void withoutAlmuraNothingChanges()
    {
        final Random random = new Random(1);
        for (int i = 0; i < 10_000; i++)
        {
            // Temperate (crops, flowers) and snowy (ice roses): Almura's blocks are not there, so grass stays grass
            assertEquals(Blocks.SHORT_GRASS.defaultBlockState(), AlmuraPlants.wild(Blocks.SHORT_GRASS.defaultBlockState(), 0.8F, random));
            assertEquals(Blocks.FERN.defaultBlockState(), AlmuraPlants.wild(Blocks.FERN.defaultBlockState(), 0.0F, random));
            assertEquals(Blocks.STONE.defaultBlockState(), AlmuraPlants.wild(Blocks.STONE.defaultBlockState(), 0.8F, random));
        }
    }
}
