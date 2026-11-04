package com.khorn.terraincontrol.generator.biome.layers;

import com.khorn.terraincontrol.LocalWorld;
import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.configuration.WorldConfig;
import com.khorn.terraincontrol.generator.biome.ArraysCache;
import com.khorn.terraincontrol.logging.LogMarker;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

public class LayerFromImage extends Layer
{

    private short[] biomeMap;
    private int mapHeight;
    private int mapWidth;
    private int fillBiome = 0;
    private int xOffset;
    private int zOffset;
    private WorldConfig.ImageMode imageMode;

    public LayerFromImage(long seed, Layer childLayer, WorldConfig config, LocalWorld world)
    {
        super(seed);
        this.child = childLayer;
        xOffset = config.imageXOffset;
        zOffset = config.imageZOffset;
        this.imageMode = config.imageMode;
        this.fillBiome = world.getBiomeByName(config.imageFillBiome).getIds().getGenerationId();

        // Read from file. The image is converted one row at a time straight into a short[] of
        // biome ids (rotated as it goes), so large maps never hold a full int[] copy of the pixels.
        final File image = new File(config.settingsDir, config.imageFile);
        try
        {
            final BufferedImage map = ImageIO.read(image);
            if (map == null)
                throw new IOException("Unsupported image format");

            final int imageWidth = map.getWidth();
            final int imageHeight = map.getHeight();
            final boolean swapSides = config.imageOrientation == WorldConfig.ImageOrientation.West
                    || config.imageOrientation == WorldConfig.ImageOrientation.East;
            this.mapWidth = swapSides ? imageHeight : imageWidth;
            this.mapHeight = swapSides ? imageWidth : imageHeight;
            this.biomeMap = new short[imageWidth * imageHeight];

            // Paletted images can be looked up per palette index instead of per colour
            short[] paletteBiomes = null;
            if (map.getColorModel() instanceof IndexColorModel)
            {
                IndexColorModel palette = (IndexColorModel) map.getColorModel();
                paletteBiomes = new short[palette.getMapSize()];
                for (int i = 0; i < paletteBiomes.length; i++)
                    paletteBiomes[i] = toBiome(config, palette.getRGB(i), fillBiome);
            }

            final Raster raster = map.getRaster();
            final int[] row = new int[imageWidth];
            for (int y = 0; y < imageHeight; y++)
            {
                if (paletteBiomes != null)
                    raster.getSamples(0, y, imageWidth, 1, 0, row);
                else
                    map.getRGB(0, y, imageWidth, 1, row, 0, imageWidth);

                for (int x = 0; x < imageWidth; x++)
                {
                    short biome;
                    if (paletteBiomes != null)
                        biome = row[x] < paletteBiomes.length ? paletteBiomes[row[x]] : (short) fillBiome;
                    else
                        biome = toBiome(config, row[x], fillBiome);

                    int index;
                    switch (config.imageOrientation)
                    {
                        case South:
                            // Rotate picture 180 degrees
                            index = (imageHeight - 1 - y) * imageWidth + imageWidth - 1 - x;
                            break;
                        case West:
                            // Rotate picture CW
                            index = x * imageHeight + imageHeight - 1 - y;
                            break;
                        case East:
                            // Rotate picture CCW
                            index = (imageWidth - 1 - x) * imageHeight + y;
                            break;
                        default:
                            // North: nothing to rotate
                            index = y * imageWidth + x;
                            break;
                    }
                    this.biomeMap[index] = biome;
                }
            }
        } catch (IOException ioexception)
        {
            TerrainControl.log(LogMarker.FATAL, "Could not read biome map {}: {}", image, ioexception.getMessage());
            // Fall back to a 1x1 map of the fill biome so generation can continue
            this.mapWidth = 1;
            this.mapHeight = 1;
            this.biomeMap = new short[] {(short) fillBiome};
        }
    }

    private static short toBiome(WorldConfig config, int rgb, int fillBiome)
    {
        Integer biome = config.biomeColorMap == null ? null : config.biomeColorMap.get(rgb & 0x00FFFFFF);
        return (short) (biome != null ? biome : fillBiome);
    }

    @Override
    public int[] getInts(ArraysCache cache, int x, int z, int xSize, int zSize)
    {
        int[] resultBiomes = cache.getArray(xSize * zSize);

        switch (this.imageMode)
        {
            case Repeat:
                for (int zi = 0; zi < zSize; zi++)
                    for (int xi = 0; xi < xSize; xi++)
                    {
                        int Buffer_x = (x + xi - xOffset) % this.mapWidth;
                        int Buffer_z = (z + zi - zOffset) % this.mapHeight;

                        // Take care of negatives
                        if (Buffer_x < 0)
                            Buffer_x += this.mapWidth;
                        if (Buffer_z < 0)
                            Buffer_z += this.mapHeight;
                        resultBiomes[(xi + zi * xSize)] = this.biomeMap[Buffer_x + Buffer_z * this.mapWidth];
                    }
                return resultBiomes;
            case Mirror:
                // Improved repeat mode
                for (int zi = 0; zi < zSize; zi++)
                    for (int xi = 0; xi < xSize; xi++)
                    {
                        int Buffer_xq = (x + xi - xOffset) % (2 * this.mapWidth);
                        int Buffer_zq = (z + zi - zOffset) % (2 * this.mapHeight);
                        if (Buffer_xq < 0)
                            Buffer_xq += 2 * this.mapWidth;
                        if (Buffer_zq < 0)
                            Buffer_zq += 2 * this.mapHeight;
                        int Buffer_x = Buffer_xq % this.mapWidth;
                        int Buffer_z = Buffer_zq % this.mapHeight;
                        if (Buffer_xq >= this.mapWidth)
                            Buffer_x = this.mapWidth - 1 - Buffer_x;
                        if (Buffer_zq >= this.mapHeight)
                            Buffer_z = this.mapHeight - 1 - Buffer_z;
                        resultBiomes[(xi + zi * xSize)] = this.biomeMap[Buffer_x + Buffer_z * this.mapWidth];
                    }
                return resultBiomes;
            case ContinueNormal:
                int[] childBiomes = null;
                if (this.child != null)
                    childBiomes = this.child.getInts(cache, x, z, xSize, zSize);
                for (int zi = 0; zi < zSize; zi++)
                    for (int xi = 0; xi < xSize; xi++)
                    {
                        int Buffer_x = x + xi - xOffset;
                        int Buffer_z = z + zi - zOffset;
                        if (Buffer_x < 0 || Buffer_x >= this.mapWidth || Buffer_z < 0 || Buffer_z >= this.mapHeight)
                        {
                            if (childBiomes != null)
                                resultBiomes[(xi + zi * xSize)] = childBiomes[(xi + zi * xSize)];
                            else
                                resultBiomes[(xi + zi * xSize)] = this.fillBiome;
                        } else
                            resultBiomes[(xi + zi * xSize)] = this.biomeMap[Buffer_x + Buffer_z * this.mapWidth];
                    }
                break;
            case FillEmpty:
                // Some fastened version
                for (int zi = 0; zi < zSize; zi++)
                    for (int xi = 0; xi < xSize; xi++)
                    {
                        int Buffer_x = x + xi - xOffset;
                        int Buffer_z = z + zi - zOffset;
                        if (Buffer_x < 0 || Buffer_x >= this.mapWidth || Buffer_z < 0 || Buffer_z >= this.mapHeight)
                            resultBiomes[(xi + zi * xSize)] = this.fillBiome;
                        else
                            resultBiomes[(xi + zi * xSize)] = this.biomeMap[Buffer_x + Buffer_z * this.mapWidth];
                    }
                break;
        }
        return resultBiomes;
    }

}
