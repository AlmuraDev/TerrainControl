package com.khorn.terraincontrol.util;

import com.khorn.terraincontrol.LocalMaterialData;

public class MaterialSetEntry
{
    private final LocalMaterialData material;
    private final boolean includesBlockData;

    public MaterialSetEntry(LocalMaterialData material, boolean includesBlockData)
    {
        this.material = material;
        this.includesBlockData = includesBlockData;
    }

    @Override
    public boolean equals(Object other)
    {
        // Uses hashCode, as it is guaranteed to be unique for this class
        if (other instanceof MaterialSetEntry)
        {
            return other.hashCode() == hashCode();
        }
        return false;
    }

    /**
     * Gets the hashCode of this entry, which is equal to either
     * {@link LocalMaterialData#hashCode()} or
     * {@link LocalMaterialData#hashCodeWithoutBlockData()}. This means that
     * the hashCode is unique.
     *
     * @return The unique hashCode.
     */
    @Override
    public int hashCode()
    {
        if (includesBlockData)
        {
            return material.hashCode();
        } else
        {
            return material.hashCodeWithoutBlockData();
        }
    }

    @Override
    public String toString()
    {
        String output = material.toString();
        if (includesBlockData && output.indexOf('[') < 0)
        {
            // A check of the block's default state: turn "minecraft:oak_log" back into "minecraft:oak_log[]" (material.toString
            // leaves the brackets out for a default state), or it would read back as a check of any oak log
            return output + "[]";
        }
        return output;
    }

    /**
     * Rotates this check 90 degrees. If block data was ignored in this check,
     * it will still be ignored, otherwise the block data will be rotated too.
     * 
     * @return The rotated check.
     */
    public MaterialSetEntry rotate()
    {
        if (!includesBlockData)
        {
            // Don't rotate block data
            return new MaterialSetEntry(material, false);
        } else
        {
            // Actually rotate block data, to maintain check correctness
            return new MaterialSetEntry(material.rotate(), true);
        }
    }
}
