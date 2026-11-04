package com.khorn.terraincontrol.generator;

import com.khorn.terraincontrol.LocalWorld;
import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.configuration.ConfigProvider;
import com.khorn.terraincontrol.customobjects.CustomObject;
import com.khorn.terraincontrol.customobjects.bo3.BO3;
import com.khorn.terraincontrol.customobjects.bo3.BO3PlaceableFunction;
import com.khorn.terraincontrol.logging.LogMarker;
import com.khorn.terraincontrol.util.ChunkCoordinate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Places BO3 objects at fixed world positions listed in the world's FixedObjects.txt, one line per object:
 * <pre>ObjectName x y z</pre>
 * (x, y, z) is where the object's origin (0,0,0) goes. Each populated chunk places only the object blocks in its
 * own population area (the 16x16 columns starting 8 blocks in), so every block is set exactly once, no extra
 * chunks are loaded, and an object larger than a chunk is completed as its neighbours populate.
 */
final class FixedObjectPlacer
{
    private static final class Entry
    {
        final String name;
        final BO3PlaceableFunction[] blocks;
        final int x, y, z, minX, maxX, minZ, maxZ;

        Entry(String name, BO3PlaceableFunction[] blocks, int x, int y, int z)
        {
            this.name = name;
            this.blocks = blocks;
            this.x = x;
            this.y = y;
            this.z = z;
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (BO3PlaceableFunction block : blocks)
            {
                x0 = Math.min(x0, block.x);
                x1 = Math.max(x1, block.x);
                z0 = Math.min(z0, block.z);
                z1 = Math.max(z1, block.z);
            }
            this.minX = x + x0;
            this.maxX = x + x1;
            this.minZ = z + z0;
            this.maxZ = z + z1;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    FixedObjectPlacer(ConfigProvider configs)
    {
        File file = new File(configs.getWorldConfig().settingsDir, "FixedObjects.txt");
        if (!file.isFile())
            return;
        List<String> lines;
        try
        {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e)
        {
            TerrainControl.log(LogMarker.WARN, "Could not read {}: {}", file, e.getMessage());
            return;
        }
        for (String line : lines)
        {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            String[] parts = line.split("\\s+");
            if (parts.length != 4)
            {
                TerrainControl.log(LogMarker.WARN, "FixedObjects.txt: expected 'ObjectName x y z', got '{}'", line);
                continue;
            }
            CustomObject object = configs.getCustomObjects().getObjectByName(parts[0]);
            if (!(object instanceof BO3))
            {
                TerrainControl.log(LogMarker.WARN, "FixedObjects.txt: no BO3 object named {}", parts[0]);
                continue;
            }
            try
            {
                entries.add(new Entry(parts[0], ((BO3) object).getSettings().blocks[0], Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
            } catch (NumberFormatException e)
            {
                TerrainControl.log(LogMarker.WARN, "FixedObjects.txt: bad coordinates in '{}'", line);
            }
        }
        TerrainControl.log(LogMarker.INFO, "Loaded {} fixed objects from {}", entries.size(), file);
    }

    /** Places the blocks of every listed object that fall in this chunk's population area. */
    void populate(LocalWorld world, Random random, ChunkCoordinate chunkCoord)
    {
        if (entries.isEmpty())
            return;
        int areaX0 = chunkCoord.getChunkX() * 16 + 8, areaZ0 = chunkCoord.getChunkZ() * 16 + 8;
        int areaX1 = areaX0 + 15, areaZ1 = areaZ0 + 15;
        for (Entry entry : entries)
        {
            if (entry.maxX < areaX0 || entry.minX > areaX1 || entry.maxZ < areaZ0 || entry.minZ > areaZ1)
                continue;
            for (BO3PlaceableFunction block : entry.blocks)
            {
                int bx = entry.x + block.x, bz = entry.z + block.z, by = entry.y + block.y;
                if (bx < areaX0 || bx > areaX1 || bz < areaZ0 || bz > areaZ1 || by < 0 || by > 255)
                    continue;
                block.spawn(world, random, bx, by, bz);
            }
        }
    }
}
