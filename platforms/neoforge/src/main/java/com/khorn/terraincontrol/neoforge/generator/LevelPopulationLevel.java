package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.neoforge.PopulationLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluid;

/**
 * A live level for TerrainControl to place in, as when a sapling grows into one of a biome's custom trees: the loaded chunks only
 * (what is not loaded reads as air and takes no blocks, so a tree never loads or generates chunks), with block updates as usual.
 */
final class LevelPopulationLevel implements PopulationLevel
{
    private final ServerLevel level;

    LevelPopulationLevel(final ServerLevel level)
    {
        this.level = level;
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
        return this.level.isLoaded(pos) ? this.level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
    }

    @Override
    public void setBlock(final BlockPos pos, final BlockState state)
    {
        if (this.level.isLoaded(pos))
        {
            this.level.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }

    @Override
    public void setBlockEntity(final CompoundTag tag)
    {
        final BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
        if (!this.level.isLoaded(pos))
        {
            return;
        }
        final BlockEntity blockEntity = BlockEntity.loadStatic(pos, this.level.getBlockState(pos), tag, this.level.registryAccess());
        if (blockEntity != null)
        {
            this.level.setBlockEntity(blockEntity);
        }
    }

    @Override
    public void scheduleFluidTick(final BlockPos pos, final Fluid fluid)
    {
        this.level.scheduleTick(pos, fluid, 0);
    }

    @Override
    public int getSurface(final int x, final int z)
    {
        return this.level.isLoaded(new BlockPos(x, 0, z)) ? this.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) : this.level.getMinY();
    }

    @Override
    public boolean placeFeature(final ResourceKey<Feature> feature, final RandomSource random, final BlockPos pos)
    {
        return this.level.registryAccess().lookupOrThrow(Registries.FEATURE).get(feature)
                .map(holder -> holder.value().place(this.level, this.level.getChunkSource().getGenerator(), random, pos))
                .orElse(false);
    }
}
