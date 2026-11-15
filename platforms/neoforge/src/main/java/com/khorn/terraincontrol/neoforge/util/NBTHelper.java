package com.khorn.terraincontrol.neoforge.util;

import com.khorn.terraincontrol.TerrainControl;
import com.khorn.terraincontrol.logging.LogMarker;
import com.khorn.terraincontrol.util.NamedBinaryTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Map;

/**
 * Converts between TerrainControl's {@link NamedBinaryTag} and Minecraft's tags.
 *
 * <p>TerrainControl's tags know the twelve types of 1.12; a long array (tag type 12, added in 1.13) has no TerrainControl type and is
 * left out, with a warning.</p>
 */
public final class NBTHelper
{
    /**
     * Converts a Minecraft compound tag into a TerrainControl one.
     *
     * @param name The name to give it.
     * @param tag  The Minecraft tag.
     * @return The TerrainControl tag.
     */
    public static NamedBinaryTag toNamedBinaryTag(final String name, final CompoundTag tag)
    {
        final NamedBinaryTag compound = new NamedBinaryTag(NamedBinaryTag.Type.TAG_Compound, name,
                new NamedBinaryTag[] {new NamedBinaryTag(NamedBinaryTag.Type.TAG_End, null, null)});
        for (final Map.Entry<String, Tag> entry : tag.entrySet())
        {
            final NamedBinaryTag child = toNamedBinaryTag0(entry.getKey(), entry.getValue());
            if (child != null)
            {
                compound.addTag(child);
            }
        }
        return compound;
    }

    private static NamedBinaryTag toNamedBinaryTag0(final String name, final Tag tag)
    {
        return switch (tag)
        {
            case ByteTag(byte value) -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_Byte, name, value);
            case ShortTag(short value) -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_Short, name, value);
            case IntTag(int value) -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_Int, name, value);
            case LongTag(long value) -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_Long, name, value);
            case FloatTag(float value) -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_Float, name, value);
            case DoubleTag(double value) -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_Double, name, value);
            case StringTag(String value) -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_String, name, value);
            case ByteArrayTag array -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_Byte_Array, name, array.getAsByteArray());
            case IntArrayTag array -> new NamedBinaryTag(NamedBinaryTag.Type.TAG_Int_Array, name, array.getAsIntArray());
            case ListTag list -> toNamedBinaryTagList(name, list);
            case CompoundTag compound -> toNamedBinaryTag(name, compound);
            default ->
            {
                TerrainControl.log(LogMarker.WARN, "Leaving out NBT tag {}: TerrainControl has no tag of type {}", name, tag.getId());
                yield null;
            }
        };
    }

    private static NamedBinaryTag toNamedBinaryTagList(final String name, final ListTag list)
    {
        if (list.isEmpty())
        {
            // As on 1.12: an empty list has no element type to give it
            return null;
        }
        final byte typeId = list.get(0).getId();
        if (typeId < 0 || typeId >= NamedBinaryTag.Type.values().length)
        {
            TerrainControl.log(LogMarker.WARN, "Leaving out NBT list {}: TerrainControl has no tag of type {}", name, typeId);
            return null;
        }
        final NamedBinaryTag result = new NamedBinaryTag(name, NamedBinaryTag.Type.values()[typeId]);
        for (final Tag element : list)
        {
            if (element.getId() != typeId)
            {
                // 26.3's lists may mix types; TerrainControl's may not
                TerrainControl.log(LogMarker.WARN, "Leaving out an element of NBT list {}: its type {} is not the list's {}", name,
                        element.getId(), typeId);
                continue;
            }
            final NamedBinaryTag child = toNamedBinaryTag0(null, element);
            if (child != null)
            {
                result.addTag(child);
            }
        }
        return result;
    }

    /**
     * Converts a TerrainControl compound tag into a Minecraft one.
     *
     * @param compound The TerrainControl tag.
     * @return The Minecraft tag.
     */
    public static CompoundTag toCompoundTag(final NamedBinaryTag compound)
    {
        final CompoundTag tag = new CompoundTag();
        for (final NamedBinaryTag child : (NamedBinaryTag[]) compound.getValue())
        {
            final Tag converted = toTag(child);
            if (converted != null)
            {
                tag.put(child.getName(), converted);
            }
        }
        return tag;
    }

    private static Tag toTag(final NamedBinaryTag tag)
    {
        return switch (tag.getType())
        {
            case TAG_End -> null;
            case TAG_Byte -> ByteTag.valueOf((Byte) tag.getValue());
            case TAG_Short -> ShortTag.valueOf((Short) tag.getValue());
            case TAG_Int -> IntTag.valueOf((Integer) tag.getValue());
            case TAG_Long -> LongTag.valueOf((Long) tag.getValue());
            case TAG_Float -> FloatTag.valueOf((Float) tag.getValue());
            case TAG_Double -> DoubleTag.valueOf((Double) tag.getValue());
            case TAG_Byte_Array -> new ByteArrayTag((byte[]) tag.getValue());
            case TAG_String -> StringTag.valueOf((String) tag.getValue());
            case TAG_Int_Array -> new IntArrayTag((int[]) tag.getValue());
            case TAG_List ->
            {
                final ListTag list = new ListTag();
                for (final NamedBinaryTag element : (NamedBinaryTag[]) tag.getValue())
                {
                    final Tag converted = toTag(element);
                    if (converted != null)
                    {
                        list.add(converted);
                    }
                }
                yield list;
            }
            case TAG_Compound -> toCompoundTag(tag);
        };
    }

    private NBTHelper()
    {
    }
}
