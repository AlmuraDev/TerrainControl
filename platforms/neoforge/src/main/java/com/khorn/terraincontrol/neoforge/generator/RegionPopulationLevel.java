package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.neoforge.PopulationLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluid;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;

/**
 * Population's level in the game: the level of 26.3's features step.
 */
final class RegionPopulationLevel implements PopulationLevel
{
    /** Tell the clients, not the neighbours, as 1.12's TerrainControl did */
    private static final int FLAGS = 2 | 16;

    private final WorldGenLevel level;
    private final ChunkGenerator generator;

    RegionPopulationLevel(final WorldGenLevel level, final ChunkGenerator generator)
    {
        this.level = level;
        this.generator = generator;
    }

    @Override
    public int getMinY()
    {
        return this.level.getMinY();
    }

    @Override
    public int getMaxY()
    {
        return this.level.getMaxY();
    }

    @Override
    public BlockState getBlockState(final BlockPos pos)
    {
        return this.level.getBlockState(pos);
    }

    @Override
    public void setBlock(final BlockPos pos, final BlockState state)
    {
        this.level.setBlock(pos, state, FLAGS);
        if (shapedByNeighbours(state))
        {
            // 1.12 joined these up as it drew them; 26.3 keeps the joins in the block, so have the game work them out from the
            // neighbours once the chunk is done (its neighbours are placed by then)
            this.level.getChunk(pos).markPosForPostProcessing(pos);
        }
    }

    /**
     * Gets whether a block's state follows its neighbours: glass panes, iron bars and fences join up, walls rise, stairs turn
     * corners, chests pair up.
     *
     * @param state The block.
     * @return Whether it does.
     */
    static boolean shapedByNeighbours(final BlockState state)
    {
        final Block block = state.getBlock();
        return block instanceof CrossCollisionBlock || block instanceof WallBlock || block instanceof StairBlock
                || block instanceof FenceGateBlock || block instanceof ChestBlock || block instanceof RedstoneWireBlock
                || block instanceof TripWireBlock;
    }

    @Override
    public void setBlockEntity(final CompoundTag tag)
    {
        this.level.getChunk(new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0))).setBlockEntityNbt(tag);
    }

    @Override
    public void scheduleFluidTick(final BlockPos pos, final Fluid fluid)
    {
        this.level.scheduleTick(pos, fluid, 0);
    }

    @Override
    public int getSurface(final int x, final int z)
    {
        return this.level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
    }

    @Override
    public boolean placeFeature(final ResourceKey<Feature> feature, final RandomSource random, final BlockPos pos)
    {
        final WorldGenLevel guarded = guarded();
        return this.level.registryAccess().lookupOrThrow(Registries.FEATURE).get(feature)
                .map(holder -> holder.value().place(guarded, this.generator, random, pos))
                .orElse(false);
    }

    /**
     * Gets the level as 26.3's features may see it when TerrainControl plants them. 26.3 lets the features step reach one chunk around
     * the chunk it decorates; TerrainControl plants trees and dungeons anywhere in the 2 by 2 chunks it populates (as 1.12 did), and a
     * big tree near the edge reads and writes further than 26.3 allows. Beyond that reach the level reads as bedrock and takes no
     * blocks, so such a tree does not grow there instead of reaching out of bounds.
     *
     * @return The guarded level; the level itself when it is no WorldGenRegion.
     */
    private WorldGenLevel guarded()
    {
        if (!(this.level instanceof final WorldGenRegion region))
        {
            return this.level;
        }
        final int centerX = region.getCenter().x();
        final int centerZ = region.getCenter().z();
        final BlockState outside = Blocks.BEDROCK.defaultBlockState();
        final InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof final BlockPos pos
                    && (Math.abs((pos.getX() >> 4) - centerX) > 1 || Math.abs((pos.getZ() >> 4) - centerZ) > 1))
            {
                switch (method.getName())
                {
                    case "getBlockState":
                        return outside;
                    case "getFluidState":
                        return Fluids.EMPTY.defaultFluidState();
                    case "isStateAtPosition":
                        return ((Predicate<BlockState>) args[1]).test(outside);
                    case "isFluidAtPosition":
                        return ((Predicate<FluidState>) args[1]).test(Fluids.EMPTY.defaultFluidState());
                    case "setBlock", "setBlockAndUpdate", "removeBlock", "destroyBlock", "ensureCanWrite":
                        return false;
                    default:
                        break;
                }
            }
            try
            {
                return method.invoke(region, args);
            } catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
        };
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, handler);
    }
}
