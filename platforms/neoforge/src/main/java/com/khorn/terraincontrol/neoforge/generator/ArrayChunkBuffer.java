package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.generator.ChunkBuffer;
import com.khorn.terraincontrol.neoforge.NeoForgeMaterialData;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A chunk buffer of plain block states, for chunks TerrainControl generates only to know their heights.
 */
final class ArrayChunkBuffer implements ChunkBuffer
{
    private final ChunkCoordinate chunkCoord;
    private final int minY;
    private final int height;
    private final BlockState[] blocks;

    ArrayChunkBuffer(final int chunkX, final int chunkZ, final int minY, final int height)
    {
        this.chunkCoord = ChunkCoordinate.fromChunkCoords(chunkX, chunkZ);
        this.minY = minY;
        this.height = height;
        this.blocks = new BlockState[height * 256];
        java.util.Arrays.fill(this.blocks, Blocks.AIR.defaultBlockState());
    }

    /**
     * Gets the blocks, by (y - min y) * 256 + z * 16 + x.
     *
     * @return The blocks.
     */
    BlockState[] blocks()
    {
        return this.blocks;
    }

    @Override
    public ChunkCoordinate getChunkCoordinate()
    {
        return this.chunkCoord;
    }

    @Override
    public void setBlock(final int blockX, final int blockY, final int blockZ, final LocalMaterialData material)
    {
        final int y = blockY - this.minY;
        if (y >= 0 && y < this.height)
        {
            this.blocks[y * 256 + (blockZ & 15) * 16 + (blockX & 15)] = ((NeoForgeMaterialData) material).internalBlock();
        }
    }

    @Override
    public LocalMaterialData getBlock(final int blockX, final int blockY, final int blockZ)
    {
        final int y = blockY - this.minY;
        return NeoForgeMaterialData.of(y >= 0 && y < this.height
                ? this.blocks[y * 256 + (blockZ & 15) * 16 + (blockX & 15)]
                : Blocks.AIR.defaultBlockState());
    }
}
