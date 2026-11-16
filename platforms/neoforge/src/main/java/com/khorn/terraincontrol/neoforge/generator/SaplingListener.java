package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.generator.resource.SaplingGen;
import com.khorn.terraincontrol.generator.resource.SaplingType;
import com.khorn.terraincontrol.neoforge.NeoForgeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;

import java.util.Map;
import java.util.Random;

/**
 * Saplings and mushrooms in TerrainControl's worlds grow into their biome's own trees, as the biome configs' Sapling(...) say, as on
 * 1.12. Where a biome has none for the sapling, 26.3's tree grows as usual.
 *
 * <p>Four saplings in a square grow the big trees (spruce: HugeRedwood, jungle: BigJungle, dark oak: DarkOak), as on 1.12.</p>
 */
public final class SaplingListener
{
    /** The saplings and mushrooms, as 1.12's sapling types */
    private static final Map<Block, SaplingType> SMALL = Map.of(Blocks.OAK_SAPLING, SaplingType.Oak, Blocks.SPRUCE_SAPLING,
            SaplingType.Redwood, Blocks.BIRCH_SAPLING, SaplingType.Birch, Blocks.JUNGLE_SAPLING, SaplingType.SmallJungle,
            Blocks.ACACIA_SAPLING, SaplingType.Acacia, Blocks.RED_MUSHROOM, SaplingType.RedMushroom, Blocks.BROWN_MUSHROOM,
            SaplingType.BrownMushroom);
    private static final Map<Block, SaplingType> BIG = Map.of(Blocks.SPRUCE_SAPLING, SaplingType.HugeRedwood, Blocks.JUNGLE_SAPLING,
            SaplingType.BigJungle, Blocks.DARK_OAK_SAPLING, SaplingType.DarkOak);

    private SaplingListener()
    {
    }

    /**
     * Grows a sapling or mushroom into its biome's tree, instead of 26.3's.
     *
     * @param event NeoForge's event for a sapling or mushroom growing.
     */
    public static void onGrow(final BlockGrowFeatureEvent event)
    {
        if (!(event.getLevel() instanceof final ServerLevel level)
                || !(level.getChunkSource().getGenerator() instanceof final TCChunkGenerator generator))
        {
            return;
        }
        final NeoForgeWorld world = generator.world(level.getChunkSource().randomState());
        BlockPos pos = event.getPos();
        final BlockState sapling = level.getBlockState(pos);
        final Block block = sapling.getBlock();
        SaplingType type = null;
        if (BIG.containsKey(block))
        {
            final BlockPos corner = fourSaplings(level, pos, block);
            if (corner != null)
            {
                pos = corner;
                type = BIG.get(block);
            }
        }
        if (type == null)
        {
            type = SMALL.get(block);
        }
        if (type == null)
        {
            return;
        }
        final LocalBiome biome = world.getCalculatedBiome(pos.getX(), pos.getZ());
        final SaplingGen gen = biome == null ? null : biome.getBiomeConfig().getSaplingGen(type);
        if (gen == null)
        {
            return;
        }
        event.setCanceled(true);

        final boolean wide = type.requiresFourSaplings();
        final BlockPos[] spots = wide ? new BlockPos[] {pos, pos.offset(1, 0, 0), pos.offset(0, 0, 1), pos.offset(1, 0, 1)} : new BlockPos[] {pos};
        for (final BlockPos spot : spots)
        {
            level.setBlock(spot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        final Random random = new Random(event.getRandom().nextLong());
        final BlockPos at = pos;
        final boolean grown = world.placeLive(new LevelPopulationLevel(level), () -> {
            for (int attempt = 0; attempt < 10; attempt++)
            {
                if (gen.growSapling(world, random, wide, at.getX(), at.getY(), at.getZ()))
                {
                    return true;
                }
            }
            return false;
        });
        if (!grown)
        {
            // As on 1.12: the saplings stay
            for (final BlockPos spot : spots)
            {
                level.setBlock(spot, sapling, Block.UPDATE_ALL);
            }
        }
    }

    /** The north-west corner of a square of four of the same sapling around pos, or null */
    private static BlockPos fourSaplings(final ServerLevel level, final BlockPos pos, final Block sapling)
    {
        for (int dx = 0; dx >= -1; dx--)
        {
            for (int dz = 0; dz >= -1; dz--)
            {
                final BlockPos corner = pos.offset(dx, 0, dz);
                if (level.getBlockState(corner).is(sapling) && level.getBlockState(corner.offset(1, 0, 0)).is(sapling)
                        && level.getBlockState(corner.offset(0, 0, 1)).is(sapling) && level.getBlockState(corner.offset(1, 0, 1)).is(sapling))
                {
                    return corner;
                }
            }
        }
        return null;
    }
}
