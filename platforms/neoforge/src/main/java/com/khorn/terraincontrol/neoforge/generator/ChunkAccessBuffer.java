package com.khorn.terraincontrol.neoforge.generator;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.generator.ChunkBuffer;
import com.khorn.terraincontrol.neoforge.NeoForgeMaterialData;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * TerrainControl's chunk buffer over a 26.3 chunk being generated: blocks go straight into the chunk's sections, at the chunk's
 * own x and z (0 to 15) and the world's y.
 */
final class ChunkAccessBuffer implements ChunkBuffer
{
    private final ChunkAccess chunk;
    private final ChunkCoordinate chunkCoord;

    ChunkAccessBuffer(final ChunkAccess chunk)
    {
        this.chunk = chunk;
        this.chunkCoord = ChunkCoordinate.fromChunkCoords(chunk.getPos().x(), chunk.getPos().z());
    }

    @Override
    public ChunkCoordinate getChunkCoordinate()
    {
        return this.chunkCoord;
    }

    private LevelChunkSection section(final int blockY)
    {
        if (blockY < this.chunk.getMinY() || blockY > this.chunk.getMaxY())
        {
            return null;
        }
        return this.chunk.getSection(this.chunk.getSectionIndex(blockY));
    }

    @Override
    public void setBlock(final int blockX, final int blockY, final int blockZ, final LocalMaterialData material)
    {
        final LevelChunkSection section = section(blockY);
        if (section != null)
        {
            section.setBlockState(blockX & 15, blockY & 15, blockZ & 15, ((NeoForgeMaterialData) material).internalBlock(), false);
        }
    }

    @Override
    public LocalMaterialData getBlock(final int blockX, final int blockY, final int blockZ)
    {
        final LevelChunkSection section = section(blockY);
        return NeoForgeMaterialData.of(section == null
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : section.getBlockState(blockX & 15, blockY & 15, blockZ & 15));
    }
}
