package com.khorn.terraincontrol.neoforge;

import com.khorn.terraincontrol.LocalMaterialData;
import com.khorn.terraincontrol.exception.InvalidConfigException;
import com.khorn.terraincontrol.util.minecraftTypes.DefaultMaterial;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.regex.Pattern;

/**
 * A {@link LocalMaterialData} of one of 26.3's block states.
 */
public final class NeoForgeMaterialData implements LocalMaterialData
{
    /**
     * Added to a block state's id for {@link #hashCode()}, so that it never equals a block's id ({@link #hashCodeWithoutBlockData()}).
     */
    private static final int STATE_HASH_OFFSET = 1 << 24;

    /** 1.12's "block:data", like "minecraft:wool:14" or "WOOL:14" */
    private static final Pattern LEGACY_DATA = Pattern.compile(".*:\\d+");

    /**
     * Reads a block state from the configs: a block id, optionally with properties, like "minecraft:oak_log[axis=x]". Without a
     * namespace the block is Minecraft's.
     *
     * @param input The block state.
     * @return The material.
     * @throws InvalidConfigException When it is no 26.3 block state.
     */
    public static NeoForgeMaterialData ofString(final String input) throws InvalidConfigException
    {
        final String trimmed = input.trim();
        try
        {
            return of(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, trimmed, false).blockState());
        } catch (CommandSyntaxException e)
        {
            if (LEGACY_DATA.matcher(trimmed).matches() || !trimmed.equals(trimmed.toLowerCase()))
            {
                throw new InvalidConfigException("Unknown block state: " + trimmed + " (a 1.12 block name? Convert the configs to 26.3)");
            }
            throw new InvalidConfigException("Unknown block state: " + trimmed + " (" + e.getMessage() + ")");
        }
    }

    /**
     * Gets the material of a block state.
     *
     * @param state The block state.
     * @return The material.
     */
    public static NeoForgeMaterialData of(final BlockState state)
    {
        return new NeoForgeMaterialData(state);
    }

    /**
     * Gets the material of a 1.12 block and data value, for TerrainControl's code.
     *
     * @param material The 1.12 block.
     * @param data     Its data value.
     * @return The material.
     * @see LegacyMaterials
     */
    static NeoForgeMaterialData ofDefaultMaterial(final DefaultMaterial material, final int data)
    {
        return of(LegacyMaterials.get().state(material, data));
    }

    private final BlockState state;

    private NeoForgeMaterialData(final BlockState state)
    {
        this.state = state;
    }

    /**
     * Gets the block state.
     *
     * @return The block state.
     */
    public BlockState internalBlock()
    {
        return this.state;
    }

    @Override
    public String getName()
    {
        // The block, with the properties that differ from its default state: "minecraft:chest[facing=east]", not every property
        final Block block = this.state.getBlock();
        final BlockState defaultState = block.defaultBlockState();
        final StringBuilder name = new StringBuilder(BuiltInRegistries.BLOCK.getKey(block).toString());
        char separator = '[';
        for (final Property<?> property : this.state.getProperties())
        {
            if (!this.state.getValue(property).equals(defaultState.getValue(property)))
            {
                name.append(separator).append(property.getName()).append('=').append(valueName(this.state, property));
                separator = ',';
            }
        }
        return separator == '[' ? name.toString() : name.append(']').toString();
    }

    private static <T extends Comparable<T>> String valueName(final BlockState state, final Property<T> property)
    {
        return property.getName(state.getValue(property));
    }

    @Override
    public String toString()
    {
        return getName();
    }

    @Override
    public int getBlockId()
    {
        return BuiltInRegistries.BLOCK.getId(this.state.getBlock());
    }

    @Override
    public boolean isLiquid()
    {
        return this.state.liquid();
    }

    @Override
    public boolean isSolid()
    {
        // The 1.12 blocks stay as solid as TerrainControl had them, so the terrain comes out the same
        final DefaultMaterial defaultMaterial = toDefaultMaterial();
        if (defaultMaterial != DefaultMaterial.UNKNOWN_BLOCK)
        {
            return defaultMaterial.isSolid();
        }
        return this.state.isSolid();
    }

    @Override
    public boolean isAir()
    {
        return this.state.isAir();
    }

    @Override
    public DefaultMaterial toDefaultMaterial()
    {
        return LegacyMaterials.get().materialOf(this.state.getBlock());
    }

    @Override
    public boolean canSnowFallOn()
    {
        final DefaultMaterial defaultMaterial = toDefaultMaterial();
        if (defaultMaterial != DefaultMaterial.UNKNOWN_BLOCK)
        {
            return defaultMaterial.canSnowFallOn();
        }
        // As DefaultMaterial has it: solid blocks, and leaves
        return this.state.isSolid() || this.state.is(BlockTags.LEAVES);
    }

    @Override
    public boolean isMaterial(final DefaultMaterial material)
    {
        return LegacyMaterials.get().isOf(material, this.state.getBlock());
    }

    @Override
    public LocalMaterialData withDefaultBlockData()
    {
        final BlockState defaultState = this.state.getBlock().defaultBlockState();
        return defaultState == this.state ? this : of(defaultState);
    }

    @Override
    public LocalMaterialData rotate()
    {
        // North -> west -> south -> east, as the custom objects turn: (x, z) becomes (z, -x)
        final BlockState rotated = this.state.rotate(Rotation.COUNTERCLOCKWISE_90);
        return rotated == this.state ? this : of(rotated);
    }

    @Override
    public boolean canFall()
    {
        return this.state.getBlock() instanceof FallingBlock;
    }

    @Override
    public boolean equals(final Object other)
    {
        return this == other || other instanceof NeoForgeMaterialData material && material.state == this.state;
    }

    @Override
    public int hashCode()
    {
        return STATE_HASH_OFFSET + Block.getId(this.state);
    }

    @Override
    public int hashCodeWithoutBlockData()
    {
        return getBlockId();
    }
}
