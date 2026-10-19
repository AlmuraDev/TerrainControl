package com.khorn.terraincontrol.customobjects;

import com.khorn.terraincontrol.LocalWorld;
import com.khorn.terraincontrol.generator.resource.CustomStructureGen;
import com.khorn.terraincontrol.util.ChunkCoordinate;
import com.khorn.terraincontrol.util.helpers.RandomHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Each world has a cache of unfinished structures. This class is the cache.
 *
 */
public class CustomObjectStructureCache
{
    private static final int MAX_NO_STRUCTURE_ENTRIES = 20000;

    private Map<ChunkCoordinate, CustomObjectStructure> structureCache;
    /**
     * Chunks where we already know no structure starts, see {@link #getStructureStart(int, int)}.
     */
    private final Set<ChunkCoordinate> noStructureCache = new HashSet<ChunkCoordinate>();
    private LocalWorld world;

    public CustomObjectStructureCache(LocalWorld world)
    {
        this.world = world;
        this.structureCache = new HashMap<ChunkCoordinate, CustomObjectStructure>();
    }

    public void reload(LocalWorld world)
    {
        this.world = world;
        structureCache.clear();
        noStructureCache.clear();
    }

    public CustomObjectStructure getStructureStart(int chunkX, int chunkZ)
    {
        ChunkCoordinate coord = ChunkCoordinate.fromChunkCoords(chunkX, chunkZ);
        CustomObjectStructure structureStart = structureCache.get(coord);

        // Clear cache if needed
        if (structureCache.size() > 400)
        {
            structureCache.clear();
        }

        if (structureStart != null)
        {
            return structureStart;
        }

        // Already known that no structure starts here
        // (Checked after the clear above, so the cache is still cleared at exactly the same moments)
        if (noStructureCache.contains(coord))
        {
            return null;
        }

        // No structure found, create one
        Random random = RandomHelper.getRandomForCoords(chunkX ^ 2, (chunkZ + 1) * 2, world.getSeed());
        CustomStructureGen structureGen = world.getBiome(chunkX * 16 + 15, chunkZ * 16 + 15).getBiomeConfig().structureGen;
        if (structureGen != null)
        {
            CustomObjectCoordinate customObject = structureGen.getRandomObjectCoordinate(random, chunkX, chunkZ);
            if (customObject != null)
            {
                structureStart = new CustomObjectStructure(world, customObject);
                structureCache.put(coord, structureStart);
                return structureStart;
            }
        }

        // Remember that no structure starts here. That only depends on the seed, the coords and the
        // calculated biome, so it's the same answer every time (but not with saved biomes, which change).
        // Kept apart from structureCache so it doesn't count towards its 400 limit: clearing that
        // recreates structures with a fresh Random, which would change what gets generated.
        if (!world.getConfigs().getWorldConfig().populateUsingSavedBiomes)
        {
            if (noStructureCache.size() > MAX_NO_STRUCTURE_ENTRIES)
            {
                noStructureCache.clear();
            }
            noStructureCache.add(coord);
        }
        return null;
    }
}
