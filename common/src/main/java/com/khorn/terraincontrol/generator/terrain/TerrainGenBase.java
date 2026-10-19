package com.khorn.terraincontrol.generator.terrain;

import com.khorn.terraincontrol.LocalBiome;
import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.LocalWorld;
import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.generator.ChunkBuffer;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;

import java.util.Random;

public abstract class TerrainGenBase
{
    // Hardcoded materials that cannot be changed for now
    protected final LocalMaterialData air = TerrainControl.toLocalMaterialData(DefaultMaterial.AIR, 0);
    protected final LocalMaterialData lava = TerrainControl.toLocalMaterialData(DefaultMaterial.STATIONARY_LAVA, 0);

    protected int checkAreaSize = 8;
    protected Random random = new Random();
    protected LocalWorld world;
    /**
     * Biomes of the chunk being generated, indexed x + z * 16, or null to look them up through the world.
     * @see #setGeneratingChunkBiomes(LocalBiome[])
     */
    private LocalBiome[] generatingChunkBiomes;
    private final long worldLong1;
    private final long worldLong2;

    public TerrainGenBase(LocalWorld world)
    {
        this.world = world;
        this.random.setSeed(this.world.getSeed());
        worldLong1 = this.random.nextLong();
        worldLong2 = this.random.nextLong();
    }

    public void generate(ChunkBuffer chunkBuffer)
    {
        int i = this.checkAreaSize;
        ChunkCoordinate chunkCoord = chunkBuffer.getChunkCoordinate();
        int chunkX = chunkCoord.getChunkX();
        int chunkZ = chunkCoord.getChunkZ();

        for (int x = chunkX - i; x <= chunkX + i; x++)
            for (int z = chunkZ - i; z <= chunkZ + i; z++)
            {
                long l3 = x * worldLong1;
                long l4 = z * worldLong2;
                this.random.setSeed(l3 ^ l4 ^ this.world.getSeed());
                generateChunk(ChunkCoordinate.fromChunkCoords(x, z), chunkBuffer);
            }
    }

    /**
     * Gives the biomes of the chunk that is about to be generated, so that {@link #getGeneratingChunkBiome(ChunkBuffer, int, int)}
     * doesn't have to go through the world's biome cache for every column of every cave step.
     * @param biomes The biomes, indexed x + z * 16, exactly as {@link LocalWorld#getBiome(int, int)} would return them.
     *               Null to always use the world.
     */
    public void setGeneratingChunkBiomes(LocalBiome[] biomes)
    {
        this.generatingChunkBiomes = biomes;
    }

    /**
     * Gets the biome of a column in the chunk being generated.
     * @param generatingChunkBuffer The chunk being generated.
     * @param localX X in the chunk, 0 to 15.
     * @param localZ Z in the chunk, 0 to 15.
     * @return The biome, the same as {@link LocalWorld#getBiome(int, int)} returns.
     */
    protected LocalBiome getGeneratingChunkBiome(ChunkBuffer generatingChunkBuffer, int localX, int localZ)
    {
        if (this.generatingChunkBiomes != null)
        {
            LocalBiome biome = this.generatingChunkBiomes[localX + localZ * 16];
            if (biome != null)
            {
                return biome;
            }
        }
        ChunkCoordinate generatingChunk = generatingChunkBuffer.getChunkCoordinate();
        return this.world.getBiome(generatingChunk.getBlockX() + localX, generatingChunk.getBlockZ() + localZ);
    }

    /**
     * Generates the structure for the given chunk. The terrain generator
     * calls this method for all chunks not more than {@link #checkAreaSize}
     * chunks away on either axis from the generatingChunk.
     *
     * @param currentChunk          The chunk we're searching.
     * @param generatingChunkBuffer The chunk that is currently being
     *                              generated.
     */
    protected abstract void generateChunk(ChunkCoordinate currentChunk, ChunkBuffer generatingChunkBuffer);
}
