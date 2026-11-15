package com.khorn.terraincontrol.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.Fluid;

/**
 * What TerrainControl's population needs of the level it populates: in the game 26.3's features step
 * ({@link com.khorn.terraincontrol.neoforge.generator.TCChunkGenerator}), in the tests chunks of TerrainControl's own terrain.
 */
public interface PopulationLevel
{
    int getMinY();

    int getMaxY();

    BlockState getBlockState(BlockPos pos);

    /**
     * Sets a block, telling the clients but not the neighbours (as 1.12's TerrainControl did).
     *
     * @param pos   Where.
     * @param state The block.
     */
    void setBlock(BlockPos pos, BlockState state);

    /**
     * Gives a block a block entity, loaded with its chunk.
     *
     * @param tag The block entity's data, with its x, y and z.
     */
    void setBlockEntity(CompoundTag tag);

    /**
     * Makes a fluid flow once its chunk is loaded.
     *
     * @param pos   Where.
     * @param fluid The fluid.
     */
    void scheduleFluidTick(BlockPos pos, Fluid fluid);

    /**
     * Gets the y above the highest block that is not air.
     *
     * @param x The x.
     * @param z The z.
     * @return The y.
     */
    int getSurface(int x, int z);

    /**
     * Places one of 26.3's features (a tree, a dungeon).
     *
     * @param feature The feature.
     * @param random  The random.
     * @param pos     Where.
     * @return Whether it was placed.
     */
    boolean placeFeature(ResourceKey<Feature> feature, RandomSource random, BlockPos pos);
}
