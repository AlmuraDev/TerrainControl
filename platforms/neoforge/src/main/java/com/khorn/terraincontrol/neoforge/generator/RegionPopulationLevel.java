package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.neoforge.PopulationLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluid;

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
        return this.level.registryAccess().lookupOrThrow(Registries.FEATURE).get(feature)
                .map(holder -> holder.value().place(this.level, this.generator, random, pos))
                .orElse(false);
    }
}
